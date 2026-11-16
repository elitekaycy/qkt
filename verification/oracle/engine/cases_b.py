"""Tranche B scenarios: OTO (`ON_FILL`), STACK, STACK_AT, EXIT AFTER, trailing entries and armed trailing stops,
OCO_ENTRY, composite collisions on one tick and two rules on one bar. Each case is named for what it proves.

Same tape conventions as cases.py: bar 0 closes at 100.50 (the trigger), bar 1 opens at 100.50 so the sizing price
and a paper market fill are one number. Where a doc leaves a reading open (README "Tranche B" questions) the tape is
built so every reading gives the same fills, and the case's notes say so.
"""

from __future__ import annotations

from cases import BASE, SIGNAL, Case

TAIL = ["100.00", "100.00", "100.00", "100.00"]
MT5 = {**BASE, "slippagePoints": 1}
MT5_SLIP2 = {**BASE, "slippagePoints": 2}


def dims(feature, shape, side="long", broker="paper", fills="ticks", mode="hedging", collision="none", costs=None) -> dict:
    return {
        "feature": feature,
        "shape": shape,
        "side": side,
        "broker": broker,
        "fills": fills,
        "mode": mode,
        "costs": costs or ("spread+slippage" if broker == "mt5-sim" else "none"),
        "collision": collision,
    }


def order(side, size="0.1", order_type=None, exit=None, **extra) -> dict:
    do = {"kind": "entry", "side": side, "sizing": {"form": "fixed", "value": size}}
    if order_type is not None:
        do["orderType"] = order_type
    if exit is not None:
        do["exit"] = exit
    do.update(extra)
    return do


def rule(do, at="100.50", position="flat") -> dict:
    return {"when": {"barClose": at, "position": position}, "do": do}


def close(at, position) -> dict:
    return {"when": {"barClose": at, "position": position}, "do": {"kind": "close"}}


def by(stop, target):
    return {"kind": "bracket", "stop": {"by": stop}, "target": {"by": target}}


def trailing_bracket(dist, after, target):
    return {"kind": "bracket", "stop": {"trail": dist, "afterMfe": after}, "target": {"by": target}}


def child(side, size, order_type=None) -> dict:
    c = {"side": side, "size": size}
    if order_type is not None:
        c["orderType"] = order_type
    return c


def limit_at_entry(offset):
    return {"type": "LIMIT", "entryOffset": offset}


def mfe(threshold, within, size, stop, target, **kw):
    return {"kind": "mfe", "threshold": threshold, "withinSec": within, "size": size, "stop": stop, "target": target, **kw}


CASES: list = [
    # --- OTO (ON_FILL) ----------------------------------------------------------------------------------------
    Case(
        "long-market-oto-market-child-netting-ticks-paper",
        "a market ON_FILL child fills with its parent on the parent's fill tick; CLOSE flattens both as one netted position",
        dims("oto", "oto-market-child", mode="netting"),
        [SIGNAL, ["100.50", "100.70", "100.90", "101.00"], ["101.20", "101.10", "101.00", "101.05"]],
        [rule(order("BUY", onFill=[child("BUY", "0.05")])), close("101.00", "long")],
        position_mode="netting",
    ),
    Case(
        "long-market-oto-limit-child-at-entry-netting-ticks-paper",
        "a LIMIT AT entry - 0.50 child rests until the parent fills, then fills when the ask reaches 100.00",
        dims("oto", "oto-limit-child", mode="netting"),
        [SIGNAL, ["100.50", "100.30", "99.99", "100.20"], ["100.40", "100.60", "100.80", "100.90"], ["101.00"] * 4],
        [rule(order("BUY", onFill=[child("BUY", "0.05", limit_at_entry("-0.50"))])), close("100.90", "long")],
        position_mode="netting",
    ),
    Case(
        "long-market-oto-entry-is-signal-estimate-netting-ticks-mt5",
        "for a MARKET parent `entry` is the signal-time estimate 100.50, not the 100.52 fill: the child limit is 100.00",
        dims("oto", "oto-entry-signal-estimate", broker="mt5-sim", mode="netting"),
        [
            SIGNAL,
            ["100.50", "100.00", "100.30", "100.40"],
            ["100.20", "99.99", "100.40", "100.80"],
            ["101.00", "101.00", "101.00", "101.10"],
        ],
        [rule(order("BUY", onFill=[child("BUY", "0.1", limit_at_entry("-0.50"))])), close("100.80", "long")],
        instrument=dict(MT5),
        broker="mt5-sim",
        position_mode="netting",
        notes=["oto.md:34: `entry` is the signal-time estimate for a MARKET parent; the ask touches 100.01 first (the fill-based level) and 100.00 later."],
    ),
    Case(
        "long-stop-oto-limit-child-at-stop-price-netting-ticks-paper",
        "for a STOP parent `entry` is its price 101.00: the child limit rests at 100.50 and fills on the dip",
        dims("oto", "oto-entry-stop-price", mode="netting"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.00", "100.90"],
            ["100.70", "100.49", "100.80", "101.30"],
            ["101.40", "101.40", "101.40", "101.45"],
        ],
        [
            rule(order("BUY", order_type={"type": "STOP", "price": "101.00"}, onFill=[child("BUY", "0.05", limit_at_entry("-0.50"))])),
            close("101.30", "long"),
        ],
        position_mode="netting",
        notes=["The stop trips on a tick whose mid is the stop price, so `entry` as the order price (oto.md:34) and as the paper fill are one number (README QB2)."],
    ),
    Case(
        "long-stop-oto-parent-never-fills-child-dropped-ticks-paper",
        "a STOP parent that never triggers places none of its ON_FILL children",
        dims("oto", "oto-parent-unfilled"),
        [SIGNAL, ["100.50", "100.80", "100.20", "100.00"], TAIL],
        [rule(order("BUY", order_type={"type": "STOP", "price": "102.00"}, onFill=[child("BUY", "0.05")]))],
    ),
    Case(
        "short-market-oto-limit-child-never-fills-netting-ticks-paper",
        "a SELL LIMIT AT entry + 0.50 child the bid never reaches stays out of the position; CLOSE takes the parent only",
        dims("oto", "oto-child-unfilled", side="short", mode="netting"),
        [
            SIGNAL,
            ["100.50", "100.80", "100.98", "100.70"],
            ["100.40", "100.20", "100.10", "100.05"],
            ["100.00", "100.00", "100.00", "100.02"],
        ],
        [rule(order("SELL", onFill=[child("SELL", "0.05", limit_at_entry("0.50"))])), close("100.05", "short")],
        position_mode="netting",
    ),
    # --- STACK -------------------------------------------------------------------------------------------------
    Case(
        "long-market-stack-above-layers-own-stops-ticks-paper",
        "STACK 3 SPACING 0.50 ABOVE: rungs at 101.00 and 101.50 off the seed fill, each layer stopped by its own bracket",
        dims("stack", "stack-above"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.00", "101.20"],
            ["101.30", "101.50", "101.40", "101.00"],
            ["100.80", "100.51", "100.30", "100.01"],
            ["99.80", "99.51", "99.40", "99.45"],
        ],
        [rule(order("BUY", exit=by("1.00", "3.00"), stack={"n": 3, "spacing": "0.50", "dir": "ABOVE"}))],
    ),
    Case(
        "short-market-stack-below-pyramid-down-targets-ticks-paper",
        "a SELL STACK BELOW adds as price falls (sell stops at 100.00, 99.50); each layer takes its own target",
        dims("stack", "stack-below", side="short"),
        [
            SIGNAL,
            ["100.50", "100.20", "100.00", "99.80"],
            ["99.70", "99.50", "99.20", "98.99"],
            ["98.80", "98.49", "98.20", "97.99"],
            ["98.10", "98.20", "98.30", "98.40"],
        ],
        [rule(order("SELL", exit=by("1.00", "1.50"), stack={"n": 3, "spacing": "0.50", "dir": "BELOW"}))],
    ),
    Case(
        "short-market-stack-above-average-up-stops-ticks-paper",
        "a SELL STACK ABOVE averages up with a sell limit at 101.00; seed and layer stop out on their own levels",
        dims("stack", "stack-above-short", side="short"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.01", "101.20"],
            ["101.30", "101.49", "101.80", "102.00"],
            ["101.90", "101.90", "101.90", "101.95"],
        ],
        [rule(order("SELL", exit=by("1.00", "2.00"), stack={"n": 2, "spacing": "0.50", "dir": "ABOVE"}))],
    ),
    Case(
        "long-market-stack-below-average-down-targets-ticks-mt5",
        "mt5-sim average-down: the seed buys at ask plus slip, the layer limit at seed fill - 0.50 fills unslipped",
        dims("stack", "stack-below", broker="mt5-sim"),
        [
            SIGNAL,
            ["100.50", "100.30", "100.00", "100.20"],
            ["100.60", "101.02", "101.20", "101.53"],
            ["101.40", "101.40", "101.40", "101.45"],
        ],
        [rule(order("BUY", exit=by("2.00", "1.00"), stack={"n": 2, "spacing": "0.50", "dir": "BELOW"}))],
        instrument=dict(MT5),
        broker="mt5-sim",
    ),
    Case(
        "long-market-stack-above-layer-slipped-ticks-mt5",
        "mt5-sim pyramid: the rung hangs off the slipped seed fill (101.03) and the layer stop fills at ask plus slip",
        dims("stack", "stack-above", broker="mt5-sim"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.03", "101.20"],
            ["100.90", "100.07", "99.80", "99.54"],
            ["99.40", "99.40", "99.40", "99.45"],
        ],
        [rule(order("BUY", exit=by("1.00", "2.00"), stack={"n": 2, "spacing": "0.50", "dir": "ABOVE"}))],
        instrument=dict(MT5_SLIP2),
        broker="mt5-sim",
    ),
    Case(
        "long-market-stack-list-custom-sizes-ticks-paper",
        "STACK [0.10, 0.20 AT entry + 0.50, 0.30 AT entry + 1.00]: each layer's own size and target",
        dims("stack", "stack-list"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.00", "101.20"],
            ["101.30", "101.50", "101.80", "102.01"],
            ["102.20", "102.51", "102.80", "103.01"],
            ["103.10", "103.10", "103.10", "103.15"],
        ],
        [
            rule(
                {
                    "kind": "entry",
                    "side": "BUY",
                    "exit": by("1.00", "1.50"),
                    "stack": {"layers": [{"size": "0.1"}, {"size": "0.2", "offset": "0.50"}, {"size": "0.3", "offset": "1.00"}]},
                }
            )
        ],
    ),
    Case(
        "long-market-stack-within-cancels-unfilled-layer-ticks-paper",
        "WITHIN 1m from the seed fill: layer 2 fills inside it, layer 3's rung is reached after it and never fills",
        dims("stack", "stack-within"),
        [
            SIGNAL,
            ["100.50", "101.00", "101.10", "101.20"],
            ["101.30", "101.40", "101.50", "101.30"],
            ["100.80", "100.01", "99.80", "99.51"],
            ["99.40", "99.40", "99.40", "99.45"],
        ],
        [rule(order("BUY", exit=by("1.00", "3.00"), stack={"n": 3, "spacing": "0.50", "dir": "ABOVE", "withinSec": 60}))],
    ),
    Case(
        "long-market-stack-seed-stop-cancels-pending-layers-ticks-paper",
        "the seed stops out before any layer triggers, so the unfilled layers cancel and a later rally adds nothing",
        dims("stack", "stack-exit-cancels"),
        [SIGNAL, ["100.50", "100.00", "99.51", "99.80"], ["100.40", "101.00", "101.51", "101.60"], ["101.40"] * 4],
        [rule(order("BUY", exit=by("1.00", "3.00"), stack={"n": 3, "spacing": "0.50", "dir": "ABOVE"}))],
    ),
    Case(
        "long-market-stack-layer-fills-on-seed-stop-tick-ticks-paper",
        "one gap tick reaches the average-down layer and the seed's stop: the layer fills, the seed stops, the layer runs on",
        dims("stack", "stack-below", collision="stack-layer-on-stop-tick"),
        [SIGNAL, ["100.50", "100.30", "99.60", "99.90"], ["100.20", "100.61", "100.70", "100.80"], ["100.70"] * 4],
        [rule(order("BUY", exit=by("0.80", "1.00"), stack={"n": 2, "spacing": "0.50", "dir": "BELOW"}))],
        notes=["Both orders rest when the tick opens and its quotes reach both levels, so both trigger on it (backtest-model.md:77-79); layers cancel only once the trade has exited (stack.md:3). README QB4."],
    ),
    # --- STACK_AT ----------------------------------------------------------------------------------------------
    Case(
        "long-market-stackat-mfe-tier-own-bracket-ticks-paper",
        "an MFE 0.50 tier fires on the mark reaching 101.00, its leg takes its own target while the primary stops out",
        dims("stack-at", "stack-at-mfe"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.00", "101.20"],
            ["101.40", "101.61", "101.30", "100.80"],
            ["100.20", "99.80", "99.51", "99.60"],
        ],
        [rule(order("BUY", exit=by("1.00", "2.00"), stackAt=[mfe("0.50", 300, "0.05", "0.40", "0.60")]))],
    ),
    Case(
        "long-market-stackat-two-tiers-one-tick-ticks-paper",
        "one tick lifts MFE past both 0.30 and 0.60: both tiers fire on it, each with its own size and bracket",
        dims("stack-at", "stack-at-two-tiers"),
        [
            SIGNAL,
            ["100.50", "100.60", "101.20", "101.40"],
            ["101.71", "101.90", "102.01", "101.50"],
            ["100.50", "99.90", "99.51", "99.70"],
        ],
        [
            rule(
                order(
                    "BUY",
                    exit=by("1.00", "3.00"),
                    stackAt=[mfe("0.30", 300, "0.05", "1.00", "0.50"), mfe("0.60", 300, "0.03", "1.00", "0.80")],
                )
            )
        ],
    ),
    Case(
        "long-market-stackat-tier-abandoned-after-within-ticks-paper",
        "MFE reaches the threshold 75 s after the primary fill, past WITHIN 1m: the tier is abandoned",
        dims("stack-at", "stack-at-expired"),
        [
            SIGNAL,
            ["100.50", "100.60", "100.70", "100.80"],
            ["100.90", "101.10", "101.50", "102.00"],
            ["102.30", "102.51", "102.40", "102.45"],
        ],
        [rule(order("BUY", exit=by("1.00", "2.00"), stackAt=[mfe("0.50", 60, "0.05", "0.40", "0.60")]))],
    ),
    Case(
        "long-market-stackat-recoil-tier-entry-qty-ticks-paper",
        "MAE 0.50 arms the recoil tier, the anchor floats to 99.70, a 0.30 recovery fires ENTRY_QTY * 0.5",
        dims("stack-at", "stack-at-recoil"),
        [
            SIGNAL,
            ["100.50", "100.20", "99.90", "99.70"],
            ["99.90", "100.00", "100.20", "100.41"],
            ["101.00", "101.80", "102.51", "102.30"],
        ],
        [
            rule(
                order(
                    "BUY",
                    size="0.2",
                    exit=by("1.50", "2.00"),
                    stackAt=[{"kind": "mae", "threshold": "0.50", "recover": "0.30", "withinSec": 300, "entryQtyMul": "0.5", "stop": "0.50", "target": "0.40"}],
                )
            )
        ],
    ),
    Case(
        "short-market-stackat-mfe-tier-stop-ticks-paper",
        "a SELL primary's MFE tier fires on the mark falling to 100.10; its leg stops out alone, the primary takes its target",
        dims("stack-at", "stack-at-mfe", side="short"),
        [
            SIGNAL,
            ["100.50", "100.30", "100.10", "100.20"],
            ["100.39", "100.00", "99.50", "99.00"],
            ["98.80", "98.49", "98.60", "98.70"],
        ],
        [rule(order("SELL", exit=by("1.00", "2.00"), stackAt=[mfe("0.40", 300, "0.05", "0.30", "0.80")]))],
    ),
    Case(
        "long-market-stackat-tier-on-target-tick-ticks-paper",
        "one gap tick fills the primary's target and lifts MFE past the tier: the tier fires on it and its leg runs on",
        dims("stack-at", "stack-at-mfe", collision="stack-at-tier-on-target-tick"),
        [SIGNAL, ["100.50", "100.80", "101.60", "101.70"], ["101.90", "102.11", "102.00", "101.95"]],
        [rule(order("BUY", exit=by("1.00", "1.00"), stackAt=[mfe("0.90", 300, "0.05", "0.50", "0.50")]))],
        notes=["The tick updates the primary's MFE and fills its target; stack-at.md:75-85 evaluate tiers on every tick the primary was open for, and :53 closes the primary leg only. README QB5."],
    ),
    Case(
        "long-market-stackat-tier-ticks-mt5",
        "mt5-sim: MFE is the mark against the 100.52 fill; the tier leg buys at ask plus slip, its bracket on that fill",
        dims("stack-at", "stack-at-mfe", broker="mt5-sim"),
        [
            SIGNAL,
            ["100.50", "100.80", "101.02", "101.20"],
            ["101.40", "101.65", "101.50", "101.80"],
            ["102.00", "102.53", "102.40", "102.30"],
        ],
        [rule(order("BUY", exit=by("1.00", "2.00"), stackAt=[mfe("0.50", 300, "0.05", "0.40", "0.60")]))],
        instrument=dict(MT5),
        broker="mt5-sim",
    ),
    # --- EXIT AFTER --------------------------------------------------------------------------------------------
    Case(
        "long-market-exit-after-ticks-paper",
        "EXIT AFTER 2m closes the leg at market on the tick at fill + 120 s",
        dims("exit-after", "exit-after-plain"),
        [SIGNAL, ["100.50", "100.70", "100.90", "101.00"], ["101.10", "101.20", "101.30", "101.40"], ["101.50", "101.60", "101.70", "101.80"]],
        [rule(order("BUY", exitAfterSec=120))],
    ),
    Case(
        "short-market-exit-after-off-tick-deadline-ticks-mt5",
        "EXIT AFTER 100s lands between ticks: the close is the first tick at or after fill + 100 s, at ask plus slip",
        dims("exit-after", "exit-after-off-tick", side="short", broker="mt5-sim"),
        [SIGNAL, ["100.50", "100.30", "100.20", "100.10"], ["100.00", "99.90", "99.80", "99.70"], ["99.60"] * 4],
        [rule(order("SELL", exitAfterSec=100))],
        instrument=dict(MT5_SLIP2),
        broker="mt5-sim",
    ),
    Case(
        "long-market-exit-after-beats-bracket-ticks-paper",
        "the timer fires before the bracket: the bracket is cancelled, so the later dip through the old stop sends nothing",
        dims("exit-after", "exit-after-bracket-timer"),
        [SIGNAL, ["100.50", "100.60", "100.70", "100.80"], ["100.90", "100.40", "99.40", "99.30"], ["99.20"] * 4],
        [rule(order("BUY", exit=by("1.00", "2.00"), exitAfterSec=60))],
    ),
    Case(
        "long-market-exit-after-target-first-drops-timer-ticks-paper",
        "the target fills before the 3m deadline, so the timer is dropped and nothing else is sent",
        dims("exit-after", "exit-after-bracket-first"),
        [SIGNAL, ["100.50", "101.00", "101.51", "101.40"], ["101.30"] * 4, ["101.20"] * 4, ["101.10"] * 4],
        [rule(order("BUY", exit=by("1.00", "1.00"), exitAfterSec=180))],
    ),
    Case(
        "long-limit-exit-after-from-fill-ticks-paper",
        "a LIMIT entry filling 45 s after placement closes 1m after its fill, not 1m after the signal",
        dims("exit-after", "exit-after-pending"),
        [SIGNAL, ["100.50", "100.30", "100.20", "99.99"], ["100.10", "100.20", "100.30", "100.40"], ["100.45"] * 4],
        [rule(order("BUY", order_type={"type": "LIMIT", "price": "100.00"}, exitAfterSec=60))],
    ),
    Case(
        "short-market-stackat-legs-exit-after-own-fill-ticks-paper",
        "EXIT AFTER 1m with STACK_AT: the primary closes 60 s after its fill, the tier leg 60 s after its own (30 s later)",
        dims("exit-after", "exit-after-stack-at", side="short"),
        [SIGNAL, ["100.50", "100.40", "100.20", "100.30"], ["100.10", "100.00", "99.90", "99.95"], ["99.80"] * 4],
        [rule(order("SELL", stackAt=[mfe("0.30", 300, "0.05", "1.00", "2.00")], exitAfterSec=60))],
    ),
    # --- trailing entries and armed trailing stops -------------------------------------------------------------
    Case(
        "short-trailing-by-entry-fires-on-reversal-ticks-paper",
        "SELL TRAILING BY 0.50 ratchets its high-water mark to 101.10 and sells at market when the mark falls 0.51",
        dims("trailing-entry", "trailing-by", side="short"),
        [SIGNAL, ["100.50", "100.70", "100.90", "101.10"], ["100.80", "100.59", "100.40", "100.30"], ["100.20"] * 4],
        [rule(order("SELL", order_type={"type": "TRAILING", "by": "0.50"})), close("100.30", "short")],
        notes=["The spread is constant, so a mark measured on mid, bid or ask gives the same trigger tick (README QB6)."],
    ),
    Case(
        "long-trailing-pct-entry-fires-on-reversal-ticks-paper",
        "BUY TRAILING PCT 0.5 ratchets its low-water mark to 99.40 and buys at market when the mark rises 0.5 %",
        dims("trailing-entry", "trailing-pct"),
        [SIGNAL, ["100.50", "100.20", "99.80", "99.40"], ["99.70", "99.95", "100.10", "100.20"], ["100.30"] * 4],
        [rule(order("BUY", order_type={"type": "TRAILING", "pct": "0.5"})), close("100.20", "long")],
        notes=["0.5 % of the low-water mark (0.497) and of the placement price (0.5025) both first trip on the 99.95 tick (README QB6)."],
    ),
    Case(
        "long-market-trailing-by-exit-netting-ticks-paper",
        "on a netted long, SELL TRAILING BY 0.40 rides the high to 101.60 and closes the long on a 0.41 reversal",
        dims("trailing-exit", "trailing-by", mode="netting"),
        [
            SIGNAL,
            ["100.50", "100.70", "100.90", "101.00"],
            ["101.00", "101.30", "101.60", "101.40"],
            ["101.30", "101.19", "101.00", "100.90"],
        ],
        [rule(order("BUY")), rule(order("SELL", order_type={"type": "TRAILING", "by": "0.40"}), at="101.00", position="long")],
        position_mode="netting",
    ),
    Case(
        "long-market-armed-trailing-stop-trails-hwm-ticks-paper",
        "STOP LOSS TRAILING 0.50 AFTER MFE >= 0.80 arms at 101.30, trails the 101.70 high to 101.20, stops on the reversal",
        dims("armed-trailing", "armed-trailing-hwm"),
        [SIGNAL, ["100.50", "100.90", "101.30", "101.70"], ["101.50", "101.10", "100.90", "100.80"], ["100.70"] * 4],
        [rule(order("BUY", exit=trailing_bracket("0.50", "0.80", "3.00")))],
        notes=["The reversal tick is through the trailed level on every reading of the extreme (mid or bid) (README QB7)."],
    ),
    Case(
        "short-market-armed-trailing-stop-pre-arm-hit-ticks-paper",
        "a SELL armed trailing stop that never arms stops out at its pre-arm level entry + 0.60",
        dims("armed-trailing", "armed-trailing-pre-arm", side="short"),
        [SIGNAL, ["100.50", "100.30", "100.10", "100.40"], ["100.70", "101.09", "101.20", "101.00"], ["100.90"] * 4],
        [rule(order("SELL", exit=trailing_bracket("0.60", "1.00", "3.00")))],
    ),
    Case(
        "long-market-armed-trailing-from-inception-ticks-mt5",
        "AFTER MFE >= 0 trails from the fill: the stop follows the 100.80 high to 100.40 and fills at bid minus slip",
        dims("armed-trailing", "armed-trailing-inception", broker="mt5-sim"),
        [SIGNAL, ["100.50", "100.80", "100.60", "100.39"], ["100.30"] * 4],
        [rule(order("BUY", exit=trailing_bracket("0.40", "0", "3.00")))],
        instrument=dict(MT5),
        broker="mt5-sim",
    ),
    # --- OCO_ENTRY ---------------------------------------------------------------------------------------------
    Case(
        "oco-entry-gap-through-both-legs-ticks-paper",
        "a gap tick reaches both legs: the leg whose trigger is closer to the bar's open fills, the other is cancelled",
        dims("oco-entry", "oco-entry-gap", collision="oco-entry-both-legs-gapped"),
        [SIGNAL, ["100.50", "100.40", "100.30", "100.20"], ["98.80", "99.20", "99.81", "99.70"], ["99.60"] * 4],
        [
            rule(
                {
                    "kind": "ocoEntry",
                    "legs": [
                        order("BUY", order_type={"type": "LIMIT", "price": "99.00"}, exit=by("1.00", "1.00")),
                        order("SELL", order_type={"type": "STOP", "price": "99.50"}, exit=by("1.00", "1.00")),
                    ],
                },
                position=None,
            )
        ],
        notes=["actions.md:256 states the tie-break for a candle; on ticks the gap tick opens its bar. The closer leg is also listed first, so submission order agrees (README QB8)."],
    ),
    Case(
        "oco-entry-gap-through-both-legs-bars-synthetic-paper",
        "a bar opening beyond both legs fills the leg closer to the open at the open; the other is cancelled",
        dims("oco-entry", "oco-entry-gap", side="short", fills="bars-synthetic", collision="oco-entry-both-legs-gapped"),
        [SIGNAL, ["100.50", "100.40", "100.30", "100.20"], ["98.80", "98.70", "99.85", "99.70"], ["99.60"] * 4],
        [
            rule(
                {
                    "kind": "ocoEntry",
                    "legs": [
                        order("SELL", order_type={"type": "STOP", "price": "99.50"}, exit=by("1.00", "1.00")),
                        order("BUY", order_type={"type": "LIMIT", "price": "99.00"}, exit=by("1.00", "1.00")),
                    ],
                },
                position=None,
            )
        ],
        fill_mode="bars-synthetic",
        notes=["actions.md:256: \"the leg with the closer trigger to the candle's open fills first\"; the BUY limit (0.20 from the 98.80 open) is listed second."],
    ),
    Case(
        "oco-entry-straddle-dual-breach-bars-synthetic-paper",
        "a bar through both straddle stops fills the closer SELL leg at its level, cancels the BUY, then stops the short",
        dims("oco-entry", "oco-entry-dual-breach", side="short", fills="bars-synthetic"),
        [SIGNAL, ["100.50", "100.60", "100.40", "100.30"], ["100.30", "99.60", "101.20", "100.90"], ["100.80"] * 4],
        [
            rule(
                {
                    "kind": "ocoEntry",
                    "legs": [
                        order("BUY", order_type={"type": "STOP", "price": "101.00"}, exit=by("1.00", "1.00")),
                        order("SELL", order_type={"type": "STOP", "price": "99.80"}, exit=by("1.00", "1.00")),
                    ],
                },
                position=None,
            )
        ],
        fill_mode="bars-synthetic",
        notes=["The SELL trigger is closer to the 100.30 open (actions.md:256) and the flat bar replays its low first (backtest-data.md:25-27), so both rules pick the SELL (README QB9)."],
    ),
    # --- two rules on one bar ----------------------------------------------------------------------------------
    Case(
        "two-rules-same-bar-two-bracketed-legs-ticks-paper",
        "two rules firing on one bar close open two legs on the same tick; each exits on its own bracket",
        dims("multi-rule", "two-market", collision="two-rules-same-bar"),
        [SIGNAL, ["100.50", "100.20", "99.99", "100.40"], ["101.00", "101.80", "102.51", "102.30"], ["102.20"] * 4],
        [rule(order("BUY", exit=by("1.00", "2.00")), position=None), rule(order("BUY", size="0.2", exit=by("0.50", "3.00")), position=None)],
    ),
    Case(
        "two-rules-same-bar-market-and-limit-short-ticks-mt5",
        "mt5-sim: one rule sells at market, the other rests a sell limit at 100.80; both legs take their own targets",
        dims("multi-rule", "market-and-limit", side="short", broker="mt5-sim", collision="two-rules-same-bar", costs="spread"),
        [SIGNAL, ["100.50", "100.81", "100.60", "100.30"], ["100.00", "99.79", "99.60", "99.48"], ["99.40"] * 4],
        [
            rule(order("SELL", exit=by("1.00", "1.00")), position=None),
            rule(order("SELL", order_type={"type": "LIMIT", "price": "100.80"}, exit=by("1.00", "1.00")), position=None),
        ],
        instrument={**BASE},
        broker="mt5-sim",
    ),
    Case(
        "two-rules-same-bar-bars-synthetic-paper",
        "on the synthetic path both rules fill at the next open; the tighter stop goes first on the low, the other a bar later",
        dims("multi-rule", "two-market", fills="bars-synthetic", collision="two-rules-same-bar"),
        [SIGNAL, ["100.50", "99.90", "101.00", "100.80"], ["100.90", "99.40", "100.90", "100.00"], ["99.90"] * 4],
        [rule(order("BUY", exit=by("1.00", "1.00")), position=None), rule(order("BUY", exit=by("0.50", "2.00")), position=None)],
        fill_mode="bars-synthetic",
    ),
]
