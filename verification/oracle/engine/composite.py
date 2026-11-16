"""Entries and the composite shapes built on them: resting entries, OTO children, STACK layers, STACK_AT tiers,
trailing entries, armed trailing stops, EXIT AFTER timers and OCO_ENTRY pairs. Each function cites its doc lines.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Optional

import rules
from model import Action, Exit, Level, Tier
from rules import LONG


@dataclass
class StackRun:
    """One STACK ladder: the seed's fill anchors every rung (stack.md:200); unfilled layers cancel when the trade
    exits or WITHIN after the seed fill (stack.md:3,19-20,146-156,201)."""

    deadline: int
    legs: list = field(default_factory=list)
    layers: list = field(default_factory=list)  # Pending, in ladder order


@dataclass
class TierState:
    tier: Tier
    deadline: int
    armed: bool = False
    anchor: Optional[Decimal] = None  # recoil: the worst adverse mark since the tier armed
    done: bool = False


@dataclass
class Pending:
    """A resting entry: LIMIT, STOP (STOP_LIMIT before its stop traded), or an engine-held TRAILING entry."""

    side: str
    qty: Decimal
    kind: str  # LIMIT | STOP | TRAIL
    level: Optional[Decimal]
    label: str  # the entry's order type for the tape
    action: Optional[Action] = None  # None for an OTO child or a STACK layer
    exit: Optional[Exit] = None
    limit_after_stop: Optional[Decimal] = None
    group: Optional[int] = None  # OCO_ENTRY pair
    stack: Optional[StackRun] = None
    signal_price: Optional[Decimal] = None
    trail_by: Optional[Decimal] = None
    trail_pct: Optional[Decimal] = None
    extreme: Optional[Decimal] = None
    live: bool = True


def enter(st, action: Action, bar, p, group: Optional[int] = None) -> None:
    """Sizing from the signal bar (README Q1) then the order: MARKET fills now; LIMIT/STOP/STOP_LIMIT rest
    (actions.md:104-123); TRAILING rests engine-held (add-stop-loss.md:79-83)."""
    ex = action.exit
    ot = action.order_type
    # sizing.md:61-71: the entry estimate is the signal close for a market order, the order's own price otherwise.
    # A TRAILING entry has no price of its own; every scenario sizes it in fixed lots, where the price is unused.
    price = bar.close if ot.kind == "TRAILING" else rules.sizing_price(ot, bar.close)
    stop_dist = rules.stop_distance(action.side, price, ex.stop, st.inst) if ex is not None else None
    if action.stack is not None and action.stack.seed_qty is not None:
        qty = rules.quantize_volume(action.stack.seed_qty, st.inst)
    else:
        qty = rules.size(action.sizing, price, st.balance, st.balance, stop_dist, st.inst)
    if qty is None:
        st.rejections += 1  # below volumeMin (sizing.md:241-243): "counted in the report's rejections"
        return
    side = action.side
    if ot.kind == "MARKET":
        price = st.fill_price(side, p, "MARKET", None)
        filled(st, p, Pending(side, qty, "MARKET", None, "MARKET", action, ex, signal_price=bar.close), price)
        return
    base = Pending(side, qty, ot.kind, ot.price, ot.kind, action, ex, group=group, signal_price=bar.close)
    if ot.kind == "STOP_LIMIT":
        base.kind, base.limit_after_stop, base.label = "STOP", ot.limit, "STOP_LIMIT"
    elif ot.kind == "TRAILING":
        base.kind, base.label, base.level = "TRAIL", "TRAILING", None
        base.trail_by, base.trail_pct, base.extreme = ot.trail_by, ot.trail_pct, p.tick.mid
    st.pending.append(base)


def enter_oco(st, action: Action, bar, p) -> None:
    """actions.md:139-154: "Submits two pending entry orders linked one-cancels-other. When either leg fills,
    the broker auto-cancels the other." Each leg carries its own BRACKET."""
    st.next_group += 1
    for leg in action.legs:
        enter(st, leg, bar, p, group=st.next_group)


def filled(st, p, o: Pending, price: Decimal) -> None:
    """Opens the leg, then arms what hangs off the fill: EXIT AFTER from the fill (exit-after.md:9-11,35-36), the
    STACK ladder off the seed fill (stack.md:200), STACK_AT tiers (stack-at.md:29,187) and ON_FILL children
    (oto.md:3,51)."""
    leg = st.open_leg(p, o.side, o.qty, price, o.label, o.exit)
    if leg is None:
        return
    a = o.action
    if o.stack is not None:
        leg.stack = o.stack
        o.stack.legs.append(leg)
    if a is None:
        return
    if a.exit_after_sec is not None:
        leg.deadline = p.ts + a.exit_after_sec * 1000
    if a.stack is not None:
        run = StackRun(p.ts + a.stack.within_sec * 1000, [leg])
        leg.stack = run
        for layer in a.stack.layers:
            level = price + layer.offset
            # stack.md:74-77: `AT` is a stop beyond the seed in the trade direction, a resting limit behind it.
            beyond = (layer.offset > 0) == (o.side == LONG)
            kind = "LIMIT" if layer.limit or not beyond else "STOP"
            run.layers.append(Pending(o.side, layer.qty, kind, level, kind, None, a.exit, stack=run))
        st.stacks.append(run)
    leg.tiers = [TierState(t, p.ts + t.within_sec * 1000) for t in a.tiers]
    leg.tier_action = a
    for child in a.on_fill:
        place_child(st, p, o, child)


def place_child(st, p, parent: Pending, child) -> None:
    """oto.md:22,34: a child is a plain BUY/SELL; inside ON_FILL `entry` is the parent's fill price, "exact for a
    LIMIT/STOP parent (it fills at its price) and the signal-time estimate for a MARKET parent". Children rest GTC
    and carry no exits (oto.md:58)."""
    ot = child.order_type
    if ot.kind == "MARKET":
        price = st.fill_price(child.side, p, "MARKET", None)
        st.open_leg(p, child.side, child.qty, price, "MARKET", None)
        return
    entry = parent.signal_price if parent.kind == "MARKET" else parent.level
    level = entry + ot.entry_offset if ot.entry_offset is not None else ot.price
    st.pending.append(Pending(child.side, child.qty, ot.kind, level, ot.kind))


# --- resting entries -------------------------------------------------------------------------------------------


def triggered(o: Pending, tick) -> bool:
    """backtest-model.md:77-78: a pending order triggers on the first tick whose quote for its side reaches the
    level. A TRAILING entry fires "when price reverses by the trail distance" from the extreme it ratchets
    (add-stop-loss.md:79): a buy from the low-water mark, a sell from the high-water mark, measured on the mark."""
    if o.kind == "TRAIL":
        mid = tick.mid
        o.extreme = min(o.extreme, mid) if o.side == LONG else max(o.extreme, mid)
        dist = o.trail_by if o.trail_by is not None else o.extreme * o.trail_pct / 100
        return mid >= o.extreme + dist if o.side == LONG else mid <= o.extreme - dist
    quote = rules.quote_for(o.side, tick)
    if o.kind == "STOP":
        return rules.stop_touched(o.side, o.level, quote)
    return rules.limit_touched(o.side, o.level, quote)


def work_pending(st, p) -> None:
    """Resting entries in placement order, then each live STACK's next layer (stack.md:179: "Layers in the list
    trigger in order"). Two OCO_ENTRY legs reached on one print: "the leg with the closer trigger to the candle's
    open fills first" (actions.md:172) and the other is cancelled (actions.md:141)."""
    winners = _oco_winners(st, p)
    for o in st.pending:
        if not o.live:
            continue
        if o.group is not None and winners.get(o.group, o) is not o:
            continue
        _try_fill(st, p, o)
    for run in st.stacks:
        for o in run.layers:
            if o.live:
                if not _try_fill(st, p, o):
                    break
    st.pending = [o for o in st.pending if o.live]


def _oco_winners(st, p) -> dict:
    hits: dict = {}
    for o in st.pending:
        if o.live and o.group is not None and o.kind != "TRAIL" and triggered(o, p.tick):
            hits.setdefault(o.group, []).append(o)
    return {g: min(os, key=lambda o: abs(o.level - p.bar_open)) for g, os in hits.items()}


def _try_fill(st, p, o: Pending) -> bool:
    if o.kind == "STOP" and triggered(o, p.tick):
        if o.limit_after_stop is None:
            _fill(st, p, o, st.fill_price(o.side, p, "STOP", o.level))
            return True
        o.kind, o.level, o.limit_after_stop = "LIMIT", o.limit_after_stop, None
    if o.kind == "LIMIT" and triggered(o, p.tick):
        _fill(st, p, o, st.fill_price(o.side, p, "LIMIT", o.level))
        return True
    if o.kind == "TRAIL" and triggered(o, p.tick):
        _fill(st, p, o, st.fill_price(o.side, p, "MARKET", None))  # add-stop-loss.md:83: "fires a market order"
        return True
    return False


def _fill(st, p, o: Pending, price: Decimal) -> None:
    o.live = False
    if o.group is not None:
        for other in st.pending:
            if other.group == o.group:
                other.live = False
    filled(st, p, o, price)


def retire_stacks(st, p) -> None:
    """stack.md:3: "Unfilled layers cancel automatically when the trade exits or after a time fence"; :156 the
    fence cancels "if the second/third/etc. layer hasn't triggered before the deadline elapses"."""
    for run in st.stacks:
        if p.ts > run.deadline or not any(leg.open_ for leg in run.legs):
            for o in run.layers:
                o.live = False


# --- STACK_AT -----------------------------------------------------------------------------------------------


def work_tiers(st, p) -> None:
    """stack-at.md:73-85, on every tick after the primary fills (:191 "first evaluates on the tick after the
    primary fill"): the primary's MFE/MAE update on the mark, the tick's mid (:89-90), against its fill price;
    an MFE tier fires when `mfe >= threshold` and `elapsed <= within`; a recoil tier arms at `mae >= threshold`,
    floats its anchor with the worst adverse mark and fires once recovered by RECOVER (:28,67-69,80-81,189);
    a tier past its window is abandoned (:82); each fires at most once (:83), all qualifying on one tick (:85).
    The leg is a market order (stack-at.md:94-95) priced as any market fill on this broker, its BY bracket
    anchored on its own fill (:96-98), and it gets the action's EXIT AFTER from its own fill (exit-after.md:37)."""
    for leg in list(st.open_legs):
        if not leg.tiers or leg.fill_ts == p.ts:
            continue
        mid = p.tick.mid
        fav = mid - leg.entry if leg.side == LONG else leg.entry - mid
        leg.best_excursion = max(leg.best_excursion, fav)
        leg.worst_excursion = max(leg.worst_excursion, -fav)
        for ts in leg.tiers:
            if ts.done:
                continue
            if p.ts - leg.fill_ts > ts.tier.within_sec * 1000:
                ts.done = True
                continue
            if _tier_fires(ts, leg, mid):
                ts.done = True
                _fire_tier(st, p, leg, ts.tier)


def _tier_fires(ts: TierState, leg, mid: Decimal) -> bool:
    t = ts.tier
    if t.kind == "mfe":
        return leg.best_excursion >= t.threshold
    if not ts.armed and leg.worst_excursion >= t.threshold:
        ts.armed, ts.anchor = True, mid
    if not ts.armed:
        return False
    worse = mid < ts.anchor if leg.side == LONG else mid > ts.anchor
    if worse:
        ts.anchor = mid
    recovered = mid - ts.anchor if leg.side == LONG else ts.anchor - mid
    return recovered >= t.recover


def _fire_tier(st, p, primary, t: Tier) -> None:
    qty = t.qty if t.qty is not None else rules.quantize_volume(primary.qty * t.entry_qty_mul, st.inst)
    ex = Exit("bracket", Level(by=t.stop), Level(by=t.target))
    price = st.fill_price(primary.side, p, "MARKET", None)
    leg = st.open_leg(p, primary.side, qty, price, "MARKET", ex)
    a = primary.tier_action
    if leg is not None and a.exit_after_sec is not None:
        leg.deadline = p.ts + a.exit_after_sec * 1000


# --- armed trailing stop ------------------------------------------------------------------------------------


def trail_stop(leg, mid: Decimal) -> None:
    """bracket.md:161-167: pre-arm the stop sits at entry -+ distance; "when MFE crosses <threshold>, the stop
    arms and starts tracking the favorable extreme"; post-arm it sits at hwm -+ distance, "where hwm is the
    running favorable extreme since fill"; arming is one-way. The extreme is the mark (mid), the price MFE is
    measured on (stack-at.md:89-90)."""
    if leg.trail is None:
        return
    leg.best = max(leg.best, mid) if leg.side == LONG else min(leg.best, mid)
    mfe = leg.best - leg.entry if leg.side == LONG else leg.entry - leg.best
    if not leg.armed and mfe >= leg.arm_mfe:
        leg.armed = True
    if leg.armed:
        level = leg.best - leg.trail if leg.side == LONG else leg.best + leg.trail
        leg.stop = max(leg.stop, level) if leg.side == LONG else min(leg.stop, level)
