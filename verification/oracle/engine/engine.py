"""The reference replay: bars close rules, orders fill on quotes, exits protect fills, costs are booked per fill.

Two paths, as backtest-model.md "Fills" describes them:

- `ticks` and `bars-ticks`: every fill resolves on a recorded tick (backtest-model.md:76-79 paper, :120-124
  mt5-sim; :102-104 `--bars` "signals come from bars, fills from real prints").
- `bars-synthetic`: a bar "has no prints between a bar's open, low, high and close" (backtest-model.md:83-85);
  it replays "its synthetic open, extremes (the open position's adverse one first) and close" (:105-107); "an exit
  the bar trades through fills at its level, and one the next bar opens beyond (a gap) fills at that open" (:84-85),
  and "the same holds for every resting order, entries included" (:85-88). Each print carries the time :97-101 gives
  it: the open at the bar's start, the extremes a quarter and half of the bar later, the close 1 ms before its end.

Positions are tracked as legs: each filled entry is its own leg with its own exits (a hedging account; the leg-routed
positions of backtest-model.md:45-46: "each bracket, `TIMES` or `STACK` entry is its own leg"). On a netting
account fills on one side merge into one leg and an opposite fill reduces it (backtest-model.md:46-47).

On one print the oracle works, in order: resting entries (composite.work_pending), every open leg's exits
(work_exits), then the STACK_AT tiers of primaries still open (composite.work_tiers). See README "Tranche B".
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Optional

import composite
import rules
from model import Action, Exit, Fill, Instrument, Scenario, Tick
from rules import LONG, SHORT


@dataclass
class Bar:
    start: int
    end: int
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal

    def update(self, price: Decimal) -> None:
        self.high = max(self.high, price)
        self.low = min(self.low, price)
        self.close = price


@dataclass
class Leg:
    """One open position leg with its own protective levels and timers."""

    side: str
    qty: Decimal
    entry: Decimal
    stop: Optional[Decimal]
    target: Optional[Decimal]
    fill_ts: int
    trail: Optional[Decimal] = None  # armed trailing stop distance (bracket.md:152-167)
    arm_mfe: Optional[Decimal] = None
    armed: bool = False
    best: Optional[Decimal] = None  # running favourable extreme of the mark since fill
    worst: Optional[Decimal] = None  # running adverse extreme of the mark since fill
    deadline: Optional[int] = None  # EXIT AFTER (exit-after.md:9-11)
    tiers: list = field(default_factory=list)  # composite.TierState, primary legs only
    stack: Optional[object] = None  # composite.StackRun this leg belongs to
    tier_action: Optional[Action] = None  # the action whose STACK_AT tiers this primary carries
    cost: Decimal = Decimal(0)  # sum of fill price x qty, so a whole netted close is exact
    best_excursion: Decimal = Decimal(0)  # MFE of the mark against the fill (stack-at.md:27,93-94)
    worst_excursion: Decimal = Decimal(0)  # MAE likewise (stack-at.md:28)
    open_: bool = True


@dataclass
class Print:
    """One executable price. On ticks bid/ask come from the feed. On the synthetic path a print is mid-only:
    a level crossed by an extreme or the close fills at the level, one the bar's open is already beyond
    (`gap_open`) fills at that open (backtest-model.md:84-85)."""

    ts: int
    tick: Tick
    synthetic: bool = False
    gap_open: bool = False
    bar_start: Optional[int] = None
    bar_open: Optional[Decimal] = None
    ref: Optional[Decimal] = None  # where the move to this print started (actions.md:277-283): prior tick / bar open


@dataclass
class State:
    scenario: Scenario
    inst: Instrument
    balance: Decimal
    tape: list = field(default_factory=list)
    pending: list = field(default_factory=list)
    legs: list = field(default_factory=list)
    stacks: list = field(default_factory=list)
    fired: set = field(default_factory=set)
    rejections: int = 0
    next_group: int = 0

    @property
    def mt5(self) -> bool:
        return self.scenario.broker == "mt5-sim"

    @property
    def netting(self) -> bool:
        return self.scenario.position_mode == "netting"

    @property
    def open_legs(self) -> list:
        return [leg for leg in self.legs if leg.open_]

    # --- booking -------------------------------------------------------------------------------------------

    def book(self, p: Print, side: str, qty: Decimal, price: Decimal, gross: Decimal, reason: str, label: str) -> Fill:
        fee = rules.commission(qty, self.inst)
        realized = gross - fee
        self.balance += realized
        fill = Fill(p.ts, side, qty, price, realized, reason, label, p.bar_start)
        self.tape.append(fill)
        return fill

    def fill_price(self, side: str, p: Print, kind: str, level: Optional[Decimal]) -> Decimal:
        """kind: MARKET | STOP | LIMIT. Synthetic prints fill at the level they cross, or at the print on a gap."""
        if p.synthetic:
            return level if (level is not None and not p.gap_open) else p.tick.mid
        if self.mt5:
            limit = level if kind == "LIMIT" else None
            return rules.mt5_fill(side, p.tick, self.inst, slipped=kind in ("MARKET", "STOP"), limit=limit)
        return rules.paper_fill(p.tick)

    # --- entries -------------------------------------------------------------------------------------------

    def open_leg(self, p: Print, side: str, qty: Decimal, price: Decimal, label: str, ex: Optional[Exit]) -> Optional[Leg]:
        """Books an entry fill and opens its leg; returns None when the fill only reduced a netted position."""
        others = [leg for leg in self.open_legs if leg.side != side]
        if others and (ex is None or self.netting):
            return self._opposite_entry(p, side, qty, price, label, others)
        if others and len({leg.side for leg in self.open_legs}) > 1:
            raise AssertionError("oracle: more than one hedged side open is out of scope")
        same = [leg for leg in self.open_legs if leg.side == side]
        if same and self.netting:
            assert ex is None and len(same) == 1, "oracle: a netted add carries no exits of its own"
            leg = same[0]
            self.book(p, side, qty, price, Decimal(0), "entry", label)
            leg.cost += price * qty  # a netted position's entry is its volume-weighted average
            leg.qty += qty
            leg.entry = leg.cost / leg.qty
            return None
        stop = target = None
        leg = Leg(side, qty, price, None, None, p.ts)
        if ex is not None:
            stop = rules.stop_level(side, price, ex.stop, self.inst)
            target = rules.target_level(side, price, ex.target, self.inst)
            leg.stop, leg.target = stop, target
            if ex.stop.trail is not None:
                leg.trail, leg.arm_mfe = ex.stop.trail, ex.stop.after_mfe
        fill = self.book(p, side, qty, price, Decimal(0), "entry", label)
        fill.stop, fill.target = stop, target
        leg.best = leg.worst = p.tick.mid
        leg.cost = price * qty
        self.legs.append(leg)
        return leg

    def _opposite_entry(self, p: Print, side: str, qty: Decimal, price: Decimal, label: str, others: list) -> None:
        """actions.md:100-108: an opposite-side order smaller than the position reduces it (hedging: part of the
        oldest leg, closed by ticket); a larger one ends with the excess open on the other side. Hedging books
        that as a close of every opposite leg, oldest first, then a fresh entry for the excess, two deals at the same
        price; netting reverses the position in one fill that realises the closed part. An opposite entry carrying a
        BRACKET opens its own leg beside the old one on hedging (actions.md:109-110; handled by the caller)."""
        left = qty
        if self.netting:
            (leg,) = others
            closed = min(qty, leg.qty)
            if closed == qty:
                self.close_leg(p, leg, closed, "close", label, kind="MARKET")
                return None
            gross = rules.gross_pnl_on_cost(leg.side, leg.cost, price, closed, self.inst)
            self.book(p, side, qty, price, gross, "reverse", label)
            leg.qty, leg.open_, leg.tiers = Decimal(0), False, []
            new = Leg(side, qty - closed, price, None, None, p.ts, cost=price * (qty - closed))
            new.best = new.worst = p.tick.mid
            self.legs.append(new)
            return None
        for leg in others:
            if left == 0:
                break
            closed = min(left, leg.qty)
            self.close_leg(p, leg, closed, "close", label, kind="MARKET")
            left -= closed
        if left > 0:
            self.book(p, side, left, price, Decimal(0), "entry", label)
            new = Leg(side, left, price, None, None, p.ts, cost=price * left)
            new.best = new.worst = p.tick.mid
            self.legs.append(new)
        return None

    def fire(self, bar: Bar, p: Print) -> None:
        """strategy-block.md:205: "Multiple rules are evaluated in order on every candle close"; :230 edge-triggered;
        conditions.md:231: "A market entry from a bar-close signal fills on the first executable price after
        that close: on the boundary in a bar backtest, a moment later on ticks". Every rule reads the state the
        bar closed on (the conditions are evaluated before any of their orders can fill), then acts in order."""
        due = [
            (i, a)
            for i, a in enumerate(self.scenario.actions)
            if i not in self.fired and a.bar_close == bar.close and self._position_matches(a)
        ]
        for i, action in due:
            self.fired.add(i)
            if action.kind == "entry":
                composite.enter(self, action, bar, p)
            elif action.kind == "ocoEntry":
                composite.enter_oco(self, action, bar, p)
            elif action.kind == "close":
                for leg in self.open_legs:
                    self.close_leg(p, leg, leg.qty, "close", "CLOSE", kind="MARKET")
            elif action.kind == "reduce":
                (leg,) = self.open_legs
                qty = rules.quantize_volume(leg.qty * action.fraction, self.inst)
                assert qty is not None
                self.close_leg(p, leg, qty, "close", "MARKET", kind="MARKET")
            else:
                raise ValueError(action.kind)

    def _position_matches(self, action: Action) -> bool:
        if action.requires is None:
            return True
        legs = self.open_legs
        if action.requires == "flat":
            return not legs
        want = LONG if action.requires == "long" else SHORT
        return bool(legs) and all(leg.side == want for leg in legs)

    # --- exits ---------------------------------------------------------------------------------------------

    def work_exits(self, p: Print) -> None:
        """bracket.md:3: "when either side fills, the other auto-cancels"; backtest-model.md:77-79: the exit
        triggers on the first tick whose quote for its side reaches the level. The stop is a stop order (slipped on
        mt5-sim, backtest-model.md:164-167), the target a limit (never slipped). Every leg exits on its own
        (stack.md:119-122,139; stack-at.md:3,53). An armed trailing stop re-levels first (composite.trail_stop);
        an EXIT AFTER deadline closes what is still open at market (exit-after.md:9-11,27-34)."""
        for leg in list(self.open_legs):
            exit_side = rules.opposite(leg.side)
            quote = rules.quote_for(exit_side, p.tick)
            composite.trail_stop(leg, p.tick.mid)
            if leg.stop is not None and rules.stop_touched(exit_side, leg.stop, quote):
                self.close_leg(p, leg, leg.qty, "stop", "STOP", leg.stop)
            elif leg.target is not None and rules.limit_touched(exit_side, leg.target, quote):
                self.close_leg(p, leg, leg.qty, "target", "LIMIT", leg.target)
            elif leg.deadline is not None and p.ts >= leg.deadline:
                self.close_leg(p, leg, leg.qty, "close", "MARKET", kind="MARKET")

    def close_leg(
        self, p: Print, leg: Leg, qty: Decimal, reason: str, label: str, level: Optional[Decimal] = None, kind: str = ""
    ) -> None:
        exit_side = rules.opposite(leg.side)
        price = self.fill_price(exit_side, p, kind or label, level)
        if qty == leg.qty:
            gross = rules.gross_pnl_on_cost(leg.side, leg.cost, price, qty, self.inst)
        else:
            gross = rules.gross_pnl(leg.side, leg.entry, price, qty, self.inst)
            leg.cost -= leg.entry * qty
        self.book(p, exit_side, qty, price, gross, reason, label)
        leg.qty -= qty
        if leg.qty == 0:
            leg.open_ = False
            leg.tiers = []  # stack-at.md:71,83: a tier lives for its primary's lifecycle only

    def work(self, p: Print) -> None:
        composite.work_pending(self, p)
        self.work_exits(p)
        composite.work_tiers(self, p)  # stack-at.md:87-89: a primary closed on this tick has no tiers left
        composite.retire_stacks(self, p)


# --- replay loops -----------------------------------------------------------------------------------------


def bars_of(scenario: Scenario) -> list:
    bars: list = []
    for t in scenario.ticks:
        start = t.ts - (t.ts - scenario.t0_ms) % scenario.timeframe_ms
        if not bars or bars[-1].start != start:
            bars.append(Bar(start, start + scenario.timeframe_ms, t.mid, t.mid, t.mid, t.mid))
        else:
            bars[-1].update(t.mid)
    return bars


def replay_ticks(scenario: Scenario) -> State:
    """Rules close a bar on the first tick at or after the bar's end and act on that tick."""
    st = State(scenario, scenario.instrument, scenario.starting_balance)
    bar: Optional[Bar] = None
    prev: Optional[Tick] = None
    for t in scenario.ticks:
        if bar is not None and t.ts >= bar.end:
            st.fire(bar, Print(t.ts, t))
            bar = None
        if bar is None:
            start = t.ts - (t.ts - scenario.t0_ms) % scenario.timeframe_ms
            bar = Bar(start, start + scenario.timeframe_ms, t.mid, t.mid, t.mid, t.mid)
        else:
            bar.update(t.mid)
        st.work(Print(t.ts, t, bar_open=bar.open, ref=prev.mid if prev is not None else None))
        prev = t
    return st


def replay_synthetic(scenario: Scenario) -> State:
    """Each bar replays open, adverse extreme, other extreme, close (backtest-model.md:90-91); with no open
    position the order is open, low, high, close (backtest-data.md:25-27). The bar before it closes the rules."""
    assert scenario.broker == "paper", "backtest-model.md:116-117: mt5-sim refuses the synthetic path"
    st = State(scenario, scenario.instrument, scenario.starting_balance)
    prev: Optional[Bar] = None
    for bar in bars_of(scenario):
        tick = Tick(bar.start, bar.open, bar.open)
        open_print = Print(
            bar.start, tick, synthetic=True, gap_open=True, bar_start=bar.start, bar_open=bar.open, ref=bar.open
        )
        if prev is not None:
            st.fire(prev, open_print)
        st.work(open_print)
        legs = st.open_legs
        adverse_first_low = not legs or legs[0].side == LONG
        extremes = [bar.low, bar.high] if adverse_first_low else [bar.high, bar.low]
        quarter = (bar.end - bar.start) // 4
        times = [bar.start + quarter, bar.start + 2 * quarter, bar.end - 1]  # backtest-model.md:97-101
        for ts, price in zip(times, extremes + [bar.close]):
            tick = Tick(ts, price, price)
            st.work(Print(ts, tick, synthetic=True, bar_start=bar.start, bar_open=bar.open, ref=bar.open))
        prev = bar
    return st


def run(scenario: Scenario) -> State:
    if scenario.fill_mode == "bars-synthetic":
        return replay_synthetic(scenario)
    return replay_ticks(scenario)
