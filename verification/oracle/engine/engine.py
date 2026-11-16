"""The reference replay: bars close rules, orders fill on quotes, exits protect fills, costs are booked per fill.

Two paths, as backtest-model.md "Fills" describes them:

- `ticks` and `bars-ticks`: every fill resolves on a recorded tick (backtest-model.md:76-79 paper, :104-106
  mt5-sim; :86-88 `--bars` "signals come from bars, fills from real prints").
- `bars-synthetic`: a bar "has no prints between a bar's open, low, high and close" (backtest-model.md:83-85);
  it replays "its synthetic open, extremes (the open position's adverse one first) and close" (:90-91); "an exit
  the bar trades through fills at its level, and one the next bar opens beyond (a gap) fills at that open" (:84-85).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Optional

import rules
from model import Action, Fill, Instrument, Scenario, Tick
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
class Pending:
    """A resting entry (LIMIT, STOP or STOP_LIMIT before/after its stop traded)."""

    action: Action
    qty: Decimal
    kind: str  # LIMIT | STOP
    level: Decimal
    limit_after_stop: Optional[Decimal]
    label: str  # the entry's order type for the tape


@dataclass
class Position:
    side: str
    qty: Decimal
    entry: Decimal
    stop: Optional[Decimal]
    target: Optional[Decimal]


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


@dataclass
class State:
    scenario: Scenario
    inst: Instrument
    balance: Decimal
    tape: list = field(default_factory=list)
    pending: list = field(default_factory=list)
    position: Optional[Position] = None
    fired: set = field(default_factory=set)
    rejections: int = 0

    @property
    def mt5(self) -> bool:
        return self.scenario.broker == "mt5-sim"

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

    def open_position(self, p: Print, action: Action, qty: Decimal, price: Decimal, label: str) -> None:
        side = action.side
        assert side in (LONG, SHORT)
        if self.position is not None and self.position.side != side:
            # actions.md:93-97 and backtest-model.md:45-47: an opposite-side entry nets against the open
            # position; the oracle only models reductions (reduce actions), never reversals.
            raise AssertionError("oracle: opposite-side entry over an open position is out of scope")
        stop = target = None
        if action.exit is not None:
            stop = rules.stop_level(side, price, action.exit.stop, self.inst)
            target = rules.target_level(side, price, action.exit.target, self.inst)
        fill = self.book(p, side, qty, price, Decimal(0), "entry", label)
        fill.stop, fill.target = stop, target
        self.position = Position(side, qty, price, stop, target)

    def fire(self, bar: Bar, p: Print) -> None:
        """strategy-block.md:205,230: rules are evaluated on every candle close and are edge-triggered;
        conditions.md:231: "A market entry from a bar-close signal fills on the first executable price after
        that close: on the boundary in a bar backtest, a moment later on ticks"."""
        for i, action in enumerate(self.scenario.actions):
            if i in self.fired or action.bar_close != bar.close or not self._position_matches(action):
                continue
            self.fired.add(i)
            if action.kind == "entry":
                self._enter(action, bar, p)
            elif action.kind == "close":
                self._close(p, self.position.qty, "close", "CLOSE")
            elif action.kind == "reduce":
                qty = rules.quantize_volume(self.position.qty * action.fraction, self.inst)
                assert qty is not None
                self._close(p, qty, "close", "MARKET")
            else:
                raise ValueError(action.kind)

    def _position_matches(self, action: Action) -> bool:
        if action.requires is None:
            return True
        if action.requires == "flat":
            return self.position is None
        return self.position is not None and self.position.side == (LONG if action.requires == "long" else SHORT)

    def _enter(self, action: Action, bar: Bar, p: Print) -> None:
        stop_dist = None
        if action.exit is not None:
            stop_dist = rules.stop_distance(action.side, bar.close, action.exit.stop, self.inst)
        qty = rules.size(action.sizing, bar.close, self.balance, self.balance, stop_dist, self.inst)
        if qty is None:
            self.rejections += 1  # below volumeMin (sizing.md:220-224): "counted in the report's rejections"
            return
        ot = action.order_type
        if ot.kind == "MARKET":
            self.open_position(p, action, qty, self.fill_price(action.side, p, "MARKET", None), "MARKET")
        elif ot.kind == "LIMIT":
            self.pending.append(Pending(action, qty, "LIMIT", ot.price, None, "LIMIT"))
        elif ot.kind == "STOP":
            self.pending.append(Pending(action, qty, "STOP", ot.price, None, "STOP"))
        elif ot.kind == "STOP_LIMIT":
            self.pending.append(Pending(action, qty, "STOP", ot.price, ot.limit, "STOP_LIMIT"))
        else:
            raise ValueError(ot.kind)

    def work_pending(self, p: Print) -> None:
        """actions.md:104-123: a limit rests at its price, a stop "triggers when price hits" its level, a
        stop-limit rests a limit at the limit price once the stop trades. Triggers read the side's quote
        (rules.quote_for); fills price per venue (rules.paper_fill / rules.mt5_fill)."""
        still = []
        for o in self.pending:
            side = o.action.side
            quote = rules.quote_for(side, p.tick)
            if o.kind == "STOP" and rules.stop_touched(side, o.level, quote):
                if o.limit_after_stop is None:
                    self.open_position(p, o.action, o.qty, self.fill_price(side, p, "STOP", o.level), o.label)
                    continue
                o = Pending(o.action, o.qty, "LIMIT", o.limit_after_stop, None, o.label)
            if o.kind == "LIMIT" and rules.limit_touched(side, o.level, quote):
                self.open_position(p, o.action, o.qty, self.fill_price(side, p, "LIMIT", o.level), o.label)
                continue
            still.append(o)
        self.pending = still

    # --- exits ---------------------------------------------------------------------------------------------

    def work_exits(self, p: Print) -> None:
        """bracket.md:3: "when either side fills, the other auto-cancels"; backtest-model.md:77-79: the exit
        triggers on the first tick whose quote for its side reaches the level. The stop is a stop order (slipped on
        mt5-sim, backtest-model.md:144-147), the target a limit (never slipped)."""
        pos = self.position
        if pos is None:
            return
        exit_side = rules.opposite(pos.side)
        quote = rules.quote_for(exit_side, p.tick)
        if pos.stop is not None and rules.stop_touched(exit_side, pos.stop, quote):
            self._close(p, pos.qty, "stop", "STOP", pos.stop)
        elif pos.target is not None and rules.limit_touched(exit_side, pos.target, quote):
            self._close(p, pos.qty, "target", "LIMIT", pos.target)

    def _close(self, p: Print, qty: Decimal, reason: str, kind: str, level: Optional[Decimal] = None) -> None:
        pos = self.position
        assert pos is not None
        exit_side = rules.opposite(pos.side)
        price = self.fill_price(exit_side, p, "MARKET" if kind in ("CLOSE", "MARKET") else kind, level)
        gross = rules.gross_pnl(pos.side, pos.entry, price, qty, self.inst)
        self.book(p, exit_side, qty, price, gross, reason, kind)
        remaining = pos.qty - qty
        self.position = None if remaining == 0 else Position(pos.side, remaining, pos.entry, pos.stop, pos.target)


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
    for t in scenario.ticks:
        p = Print(t.ts, t)
        if bar is not None and t.ts >= bar.end:
            st.fire(bar, p)
            bar = None
        if bar is None:
            start = t.ts - (t.ts - scenario.t0_ms) % scenario.timeframe_ms
            bar = Bar(start, start + scenario.timeframe_ms, t.mid, t.mid, t.mid, t.mid)
        else:
            bar.update(t.mid)
        st.work_pending(p)
        st.work_exits(p)
    return st


def replay_synthetic(scenario: Scenario) -> State:
    """Each bar replays open, adverse extreme, other extreme, close (backtest-model.md:90-91); with no open
    position the order is open, low, high, close (backtest-data.md:25-27). The bar before it closes the rules."""
    assert scenario.broker == "paper", "backtest-model.md:100-101: mt5-sim refuses the synthetic path"
    st = State(scenario, scenario.instrument, scenario.starting_balance)
    prev: Optional[Bar] = None
    for bar in bars_of(scenario):
        open_print = Print(bar.start, Tick(bar.start, bar.open, bar.open), synthetic=True, gap_open=True, bar_start=bar.start)
        if prev is not None:
            st.fire(prev, open_print)
        st.work_pending(open_print)
        st.work_exits(open_print)
        adverse_first_low = st.position is None or st.position.side == LONG
        extremes = [bar.low, bar.high] if adverse_first_low else [bar.high, bar.low]
        for price in extremes + [bar.close]:
            p = Print(bar.start, Tick(bar.start, price, price), synthetic=True, bar_start=bar.start)
            st.work_pending(p)
            st.work_exits(p)
        prev = bar
    return st


def run(scenario: Scenario) -> State:
    if scenario.fill_mode == "bars-synthetic":
        return replay_synthetic(scenario)
    return replay_ticks(scenario)
