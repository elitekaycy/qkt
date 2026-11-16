"""Tranche A scenarios: market/limit/stop/stop-limit entries, bracket and OCO exits, both sides, both fill paths,
every SIZING form with lot quantization, spread/commission/slippage costs. Each case is named for what it proves.

Bars are lists of four mid prices (ticks at :00/:15/:30/:45). The signal bar is bar 0 (close 100.50) unless the
case says otherwise; the rule fires when that bar closes, i.e. on bar 1's first tick, which is usually quoted at
100.50. A market entry sizes at the signal bar's close and fills on that first tick (sizing.md:61-71,
backtest-model.md:89-96); the `gap-open` cases quote that tick elsewhere to tell the two apart.
"""

from __future__ import annotations

from dataclasses import dataclass, field

BASE = {"contractSize": "1", "volumeStep": "0.01", "volumeMin": "0.01", "pointSize": "0.01", "digits": 2}
CS10 = {**BASE, "contractSize": "10"}
CS100 = {**BASE, "contractSize": "100"}


@dataclass
class Case:
    name: str
    proves: str
    dimensions: dict
    bars: list
    actions: list
    instrument: dict = field(default_factory=lambda: dict(BASE))
    broker: str = "paper"
    fill_mode: str = "ticks"
    position_mode: str = "hedging"
    spread: str = "0.02"
    notes: list = field(default_factory=list)


def dims(**kw) -> dict:
    base = {
        "entry": "market",
        "exit": "bracket-by",
        "side": "long",
        "fills": "ticks",
        "path": "stop",
        "sizing": "fixed",
        "costs": "none",
        "broker": "paper",
        "mode": "hedging",
    }
    base.update(kw)
    return base


def entry(side, sizing=("fixed", "0.1"), order_type=None, exit=None, at="100.50"):
    do = {"kind": "entry", "side": side, "sizing": {"form": sizing[0], "value": sizing[1]}}
    if order_type is not None:
        do["orderType"] = order_type
    if exit is not None:
        do["exit"] = exit
    return {"when": {"barClose": at, "position": "flat"}, "do": do}


def by(stop, target):
    return {"kind": "bracket", "stop": {"by": stop}, "target": {"by": target}}


def pct(stop, target):
    return {"kind": "bracket", "stop": {"pct": stop}, "target": {"pct": target}}


def at(stop, target):
    return {"kind": "bracket", "stop": {"at": stop}, "target": {"at": target}}


def oco(stop, limit):
    return {"kind": "oco", "stop": stop, "limit": limit}


def close(side, at):
    return {"when": {"barClose": at, "position": side}, "do": {"kind": "close"}}


def reverse(side, order_side, qty, at):
    """An opposite-side market entry while `side` is held (actions.md:100-108)."""
    do = {"kind": "entry", "side": order_side, "sizing": {"form": "fixed", "value": qty}}
    return {"when": {"barClose": at, "position": side}, "do": do}


def reduce(side, order_side, fraction, at):
    return {"when": {"barClose": at, "position": side}, "do": {"kind": "reduce", "side": order_side, "fraction": fraction}}


SIGNAL = ["100.00", "100.10", "99.90", "100.50"]  # bar 0: closes at the trigger price
FLAT_UP = ["100.50", "100.80", "100.20", "100.00"]  # bar 1: nothing crossed after a long or short entry
TAIL = ["100.00", "100.00", "100.00", "100.00"]  # one more bar so the last event's bar closes

LONG_STOP_HIT = ["99.80", "99.51", "99.30", "99.40"]  # bid 99.50 touches a 99.50 stop; mid 99.51
LONG_TARGET_HIT = ["101.00", "102.00", "102.51", "102.30"]  # bid 102.50 touches a 102.50 target; mid 102.51
SHORT_STOP_HIT = ["100.80", "101.20", "101.49", "101.30"]  # ask 101.50 touches a 101.50 stop; mid 101.49
SHORT_TARGET_HIT = ["99.50", "98.80", "98.49", "98.70"]  # ask 98.50 touches a 98.50 target; mid 98.49

CASES: list = [
    # --- bracket BY, both sides, stop and target, paper ticks ---------------------------------------------
    Case(
        "long-market-bracket-by-stop-ticks-paper",
        "a long stop triggers on the bid touching the level and fills at that tick's mid, half a spread above it",
        dims(),
        [SIGNAL, FLAT_UP, LONG_STOP_HIT, TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
    ),
    Case(
        "short-market-bracket-by-stop-ticks-paper",
        "a short stop triggers on the ask touching the level and fills at that tick's mid, half a spread below it",
        dims(side="short"),
        [SIGNAL, FLAT_UP, SHORT_STOP_HIT, TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
    ),
    Case(
        "long-market-bracket-by-target-ticks-paper",
        "a long target triggers on the bid reaching the level and fills at that tick's mid",
        dims(path="target"),
        [SIGNAL, FLAT_UP, LONG_TARGET_HIT, TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
    ),
    Case(
        "short-market-bracket-by-target-ticks-paper",
        "a short target triggers on the ask reaching the level and fills at that tick's mid",
        dims(side="short", path="target"),
        [SIGNAL, FLAT_UP, SHORT_TARGET_HIT, TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
    ),
    Case(
        "long-market-bracket-by-stop-ticks-paper-netting",
        "the same long stop round trip on a netting account books the same tape",
        dims(mode="netting"),
        [SIGNAL, FLAT_UP, LONG_STOP_HIT, TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        position_mode="netting",
    ),
    Case(
        "long-market-bracket-by-stop-ticks-paper-cs100",
        "realised P&L scales by contractSize 100",
        dims(sizing="fixed-cs100"),
        [SIGNAL, FLAT_UP, LONG_STOP_HIT, TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        instrument=dict(CS100),
    ),
    # --- bracket PCT with the half-tick grid rule --------------------------------------------------------
    Case(
        "long-market-bracket-pct-halftick-target-ticks-paper",
        "PCT levels 99.495 and 103.515 land on the grid toward the entry (99.50 / 103.51); the target fills",
        dims(exit="bracket-pct", path="target"),
        [SIGNAL, FLAT_UP, ["101.00", "102.50", "103.52", "103.30"], TAIL],
        [entry("BUY", exit=pct("1", "3"))],
    ),
    Case(
        "short-market-bracket-pct-halftick-stop-ticks-paper",
        "PCT levels 101.505 and 97.485 land on the grid toward the entry (101.50 / 97.49); the stop fills",
        dims(exit="bracket-pct", side="short"),
        [SIGNAL, FLAT_UP, SHORT_STOP_HIT, TAIL],
        [entry("SELL", exit=pct("1", "3"))],
    ),
    # --- bracket AT on mt5-sim: bid/ask fills from the feed spread ----------------------------------------
    Case(
        "long-market-bracket-at-stop-ticks-mt5",
        "mt5-sim buys the entry at the ask and sells the stop at the bid, no slippage configured",
        dims(exit="bracket-at", broker="mt5-sim", costs="spread"),
        [SIGNAL, FLAT_UP, LONG_STOP_HIT, TAIL],
        [entry("BUY", exit=at("99.50", "102.50"))],
        broker="mt5-sim",
    ),
    Case(
        "short-market-bracket-at-target-ticks-mt5",
        "mt5-sim sells the entry at the bid and buys the target at the ask, a limit never filled worse than its price",
        dims(exit="bracket-at", side="short", path="target", broker="mt5-sim", costs="spread"),
        [SIGNAL, FLAT_UP, SHORT_TARGET_HIT, TAIL],
        [entry("SELL", exit=at("101.50", "98.50"))],
        broker="mt5-sim",
    ),
    # --- OCO exit pair by price ------------------------------------------------------------------------
    Case(
        "long-market-oco-stop-ticks-paper",
        "OCO { STOP AT, LIMIT AT } protects a long like a bracket; the stop fills and the limit is cancelled",
        dims(exit="oco"),
        [SIGNAL, FLAT_UP, LONG_STOP_HIT, TAIL],
        [entry("BUY", exit=oco("99.50", "102.50"))],
    ),
    Case(
        "short-market-oco-target-ticks-paper",
        "OCO { STOP AT, LIMIT AT } protects a short; the limit fills and the stop is cancelled",
        dims(exit="oco", side="short", path="target"),
        [SIGNAL, FLAT_UP, SHORT_TARGET_HIT, TAIL],
        [entry("SELL", exit=oco("101.50", "98.50"))],
    ),
    # --- limit entries ----------------------------------------------------------------------------------
    Case(
        "long-limit-bracket-target-ticks-paper",
        "a buy limit rests until the ask falls to it, fills at mid, and anchors its bracket on the fill",
        dims(entry="limit", path="target"),
        [SIGNAL, ["100.50", "100.30", "100.00", "99.90"], ["99.81", "99.79", "99.60", "99.70"], ["100.00", "100.50", "100.80", "100.60"], TAIL],
        [entry("BUY", order_type={"type": "LIMIT", "price": "99.80"}, exit=by("1.00", "1.00"))],
    ),
    Case(
        "short-limit-bracket-stop-ticks-paper",
        "a sell limit rests until the bid rises to it, fills at mid, and its stop then fills on the ask",
        dims(entry="limit", side="short"),
        [SIGNAL, ["100.50", "100.90", "101.21", "101.00"], ["101.50", "102.00", "102.20", "102.10"], TAIL],
        [entry("SELL", order_type={"type": "LIMIT", "price": "101.20"}, exit=by("1.00", "1.00"))],
    ),
    Case(
        "long-limit-never-fills-ticks-paper",
        "a buy limit the ask never reaches books nothing",
        dims(entry="limit", path="none"),
        [SIGNAL, FLAT_UP, ["100.20", "100.40", "100.10", "100.30"], TAIL],
        [entry("BUY", order_type={"type": "LIMIT", "price": "99.00"}, exit=by("1.00", "1.00"))],
    ),
    # --- stop entries -----------------------------------------------------------------------------------
    Case(
        "long-stop-entry-bracket-target-ticks-paper",
        "a buy stop triggers on the ask reaching it and fills at mid; its target then fills",
        dims(entry="stop", path="target"),
        [SIGNAL, ["100.50", "100.80", "100.99", "101.20"], ["101.50", "101.80", "102.00", "101.90"], TAIL],
        [entry("BUY", order_type={"type": "STOP", "price": "101.00"}, exit=by("1.00", "1.00"))],
    ),
    Case(
        "short-stop-entry-bracket-stop-ticks-mt5-slippage",
        "mt5-sim slips a sell-stop entry and its protective buy-stop by slippagePoints, both against the trade",
        dims(entry="stop", side="short", broker="mt5-sim", costs="spread+slippage"),
        [SIGNAL, ["100.50", "100.30", "100.01", "99.80"], ["100.20", "100.60", "100.96", "100.80"], TAIL],
        [entry("SELL", order_type={"type": "STOP", "price": "100.00"}, exit=by("1.00", "1.00"))],
        instrument={**BASE, "slippagePoints": 3},
        broker="mt5-sim",
    ),
    # --- stop-limit entries -----------------------------------------------------------------------------
    Case(
        "long-stoplimit-rests-then-fills-ticks-paper",
        "once the stop trades, a limit rests at the limit price and fills when the ask comes back to it",
        dims(entry="stop-limit", path="target"),
        [SIGNAL, ["100.50", "100.80", "101.19", "101.30"], ["101.20", "101.09", "101.30", "101.40"], ["101.80", "102.10", "102.00", "102.00"], TAIL],
        [entry("BUY", order_type={"type": "STOP_LIMIT", "stop": "101.00", "limit": "101.10"}, exit=by("1.00", "1.00"))],
    ),
    Case(
        "short-stoplimit-marketable-ticks-paper",
        "a sell stop-limit whose trigger tick already satisfies the limit fills on that tick",
        dims(entry="stop-limit", side="short", path="target"),
        [SIGNAL, ["100.50", "100.20", "99.95", "99.80"], ["99.50", "99.00", "98.94", "99.10"], TAIL],
        [entry("SELL", order_type={"type": "STOP_LIMIT", "stop": "100.00", "limit": "99.90"}, exit=by("1.00", "1.00"))],
        notes=["actions.md:133-135: a stop-limit whose trigger price already satisfies its limit fills on that tick (README Q6)."],
    ),
    Case(
        "long-stoplimit-limit-never-fills-ticks-paper",
        "a stop-limit whose stop trades but whose limit the ask never returns to books nothing",
        dims(entry="stop-limit", path="none"),
        [SIGNAL, ["100.50", "100.80", "101.19", "101.30"], ["101.40", "101.60", "101.50", "101.70"], ["101.60", "101.70", "101.60", "101.65"]],
        [entry("BUY", order_type={"type": "STOP_LIMIT", "stop": "101.00", "limit": "101.10"}, exit=by("1.00", "1.00"))],
    ),
    # --- stop and target inside one bar, tick order decides -----------------------------------------------
    Case(
        "long-market-samebar-stop-first-ticks-paper",
        "when one bar's ticks cross the stop and then the target, the stop fills and the target is cancelled",
        dims(path="same-bar-both"),
        [SIGNAL, FLAT_UP, ["100.40", "99.51", "102.51", "101.00"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
    ),
    Case(
        "long-market-samebar-target-first-ticks-paper",
        "when one bar's ticks cross the target and then the stop, the target fills and the stop is cancelled",
        dims(path="same-bar-both"),
        [SIGNAL, FLAT_UP, ["100.60", "102.51", "99.51", "101.00"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
    ),
    Case(
        "short-market-samebar-stop-first-ticks-paper",
        "a short whose bar crosses the stop before the target exits at the stop",
        dims(side="short", path="same-bar-both"),
        [SIGNAL, FLAT_UP, ["100.60", "101.49", "98.49", "100.00"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
    ),
    Case(
        "short-market-samebar-target-first-ticks-paper",
        "a short whose bar crosses the target before the stop exits at the target",
        dims(side="short", path="same-bar-both"),
        [SIGNAL, FLAT_UP, ["100.40", "98.49", "101.49", "100.00"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
    ),
    # --- synthetic bar path (--bars --no-tick-fills) ----------------------------------------------------
    Case(
        "long-market-samebar-both-bars-synthetic-paper",
        "a bar spanning both levels replays its adverse extreme first: the long exits at the stop level",
        dims(fills="bars-synthetic", path="same-bar-both"),
        [SIGNAL, FLAT_UP, ["100.40", "102.80", "99.20", "101.00"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
    ),
    Case(
        "short-market-samebar-both-bars-synthetic-paper",
        "a bar spanning both levels replays its adverse extreme first: the short exits at the stop level",
        dims(fills="bars-synthetic", side="short", path="same-bar-both"),
        [SIGNAL, FLAT_UP, ["100.60", "98.00", "103.00", "101.00"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
    ),
    Case(
        "long-market-stop-through-bars-synthetic-paper",
        "a stop the bar trades through fills at its level, not at the bar low",
        dims(fills="bars-synthetic"),
        [SIGNAL, FLAT_UP, ["100.20", "100.40", "99.30", "99.80"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
    ),
    Case(
        "short-market-target-through-bars-synthetic-paper",
        "a target the bar trades through fills at its level, not at the bar low",
        dims(fills="bars-synthetic", side="short", path="target"),
        [SIGNAL, FLAT_UP, ["100.00", "99.50", "98.30", "98.90"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
    ),
    Case(
        "long-market-gap-stop-bars-synthetic-paper",
        "a stop the next bar opens beyond fills at that open",
        dims(fills="bars-synthetic", path="gap-stop"),
        [SIGNAL, FLAT_UP, ["100.20", "100.40", "100.00", "99.90"], ["99.00", "99.20", "98.80", "99.10"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
    ),
    Case(
        "short-market-gap-target-bars-synthetic-paper",
        "a target the next bar opens beyond fills at that open (the default take-profit pricing)",
        dims(fills="bars-synthetic", side="short", path="gap-target"),
        [SIGNAL, FLAT_UP, ["100.00", "99.60", "99.40", "99.50"], ["98.00", "98.20", "97.80", "98.10"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
        notes=["backtest-model.md:87-88: a take-profit the next bar opens beyond always fills at that open on paper (README Q7)."],
    ),
    Case(
        "long-market-entry-and-stop-same-bar-bars-synthetic-paper",
        "the entry fills at the bar open and the same bar's low takes the stop at its level",
        dims(fills="bars-synthetic", path="entry-and-stop-same-bar"),
        [SIGNAL, ["100.50", "100.70", "99.40", "100.00"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        fill_mode="bars-synthetic",
    ),
    Case(
        "long-stop-entry-target-bars-synthetic-paper",
        "a pending buy stop the bar trades through fills at its level on the synthetic path",
        dims(fills="bars-synthetic", entry="stop", path="target"),
        [SIGNAL, ["100.50", "100.80", "101.30", "101.20"], ["101.50", "101.80", "102.30", "101.90"], TAIL],
        [entry("BUY", order_type={"type": "STOP", "price": "101.00"}, exit=by("1.00", "1.00"))],
        fill_mode="bars-synthetic",
        notes=["backtest-model.md:85-88: the at-level rule holds for every resting order, entries included (README Q8)."],
    ),
    # --- --bars with tick-resolved fills: the same tapes as the tick replays ----------------------------
    Case(
        "long-market-bracket-target-bars-ticks-paper",
        "--bars with tick-resolved fills books the long target exactly as the tick replay does",
        dims(fills="bars-ticks", path="target"),
        [SIGNAL, FLAT_UP, LONG_TARGET_HIT, TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        fill_mode="bars-ticks",
    ),
    Case(
        "short-market-bracket-stop-bars-ticks-paper",
        "--bars with tick-resolved fills books the short stop exactly as the tick replay does",
        dims(fills="bars-ticks", side="short"),
        [SIGNAL, FLAT_UP, SHORT_STOP_HIT, TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        fill_mode="bars-ticks",
    ),
    # --- rule-driven exits: CLOSE and partial close -----------------------------------------------------
    Case(
        "long-market-close-rule-ticks-paper",
        "CLOSE from a bar-close rule flattens at the first tick after that close",
        dims(exit="close-rule", path="close"),
        [SIGNAL, FLAT_UP, ["101.00", "102.00", "102.50", "103.00"], ["103.00", "103.20", "102.90", "103.10"], TAIL],
        [entry("BUY"), close("long", "103.00")],
    ),
    Case(
        "long-market-partial-close-then-close-netting-ticks-paper",
        "SELL 0.5 * POSITION realises half the long at market; CLOSE realises the rest at the entry price basis",
        dims(exit="partial-close", path="close", mode="netting"),
        [SIGNAL, FLAT_UP, ["101.00", "101.50", "101.80", "102.00"], ["102.00", "102.20", "102.60", "102.80"], ["102.90", "103.20", "102.80", "103.00"], ["103.00", "103.10", "102.90", "103.05"], TAIL],
        [entry("BUY"), reduce("long", "SELL", "0.5", "102.00"), close("long", "103.00")],
        position_mode="netting",
    ),
    Case(
        "short-market-partial-close-then-close-netting-ticks-paper",
        "BUY 0.5 * ABS(POSITION) realises half the short at market; CLOSE realises the rest",
        dims(exit="partial-close", side="short", path="close", mode="netting"),
        [SIGNAL, FLAT_UP, ["100.00", "99.50", "99.20", "99.00"], ["99.00", "98.80", "98.60", "98.40"], ["98.30", "98.10", "98.20", "98.00"], ["98.00", "97.90", "98.10", "97.95"], TAIL],
        [entry("SELL"), reduce("short", "BUY", "0.5", "99.00"), close("short", "98.00")],
        position_mode="netting",
    ),
    # --- sizing forms through .qkt, with lot quantization ----------------------------------------------
    Case(
        "long-market-sizing-fixed-step-rounding-ticks-paper",
        "SIZING 0.125 rounds down to the 0.01 step: 0.12 lots",
        dims(sizing="fixed-step", path="target"),
        [SIGNAL, FLAT_UP, LONG_TARGET_HIT, TAIL],
        [entry("BUY", sizing=("fixed", "0.125"), exit=by("1.00", "2.00"))],
    ),
    Case(
        "long-market-sizing-usd-ticks-paper",
        "SIZING 1000 USD at 100.50 with contractSize 10 is 0.995 lots, quantized to 0.99",
        dims(sizing="usd", path="target"),
        [SIGNAL, FLAT_UP, LONG_TARGET_HIT, TAIL],
        [entry("BUY", sizing=("usd", "1000"), exit=by("1.00", "2.00"))],
        instrument=dict(CS10),
        notes=["sizing.md:73-78: one lot's notional is price x contractSize x the quote-to-account rate (README Q3)."],
    ),
    Case(
        "short-market-sizing-pct-equity-ticks-paper",
        "SIZING 5 PCT OF EQUITY on 10000 at 100.50 is 4.975 lots, quantized to 4.97",
        dims(sizing="pct-equity", side="short"),
        [SIGNAL, FLAT_UP, SHORT_STOP_HIT, TAIL],
        [entry("SELL", sizing=("pctEquity", "5"), exit=by("1.00", "2.00"))],
    ),
    Case(
        "long-market-sizing-pct-risk-ticks-paper",
        "SIZING 1 PCT RISK with a 2.00 stop and contractSize 10 is exactly 5 lots; the stop loses just under 1%",
        dims(sizing="pct-risk"),
        [SIGNAL, FLAT_UP, ["99.50", "99.00", "98.51", "98.80"], TAIL],
        [entry("BUY", sizing=("pctRisk", "1"), exit=by("2.00", "4.00"))],
        instrument=dict(CS10),
    ),
    Case(
        "long-market-sizing-pct-risk-step-rounding-ticks-paper",
        "SIZING 0.5 PCT RISK with a 1.50 stop and contractSize 10 is 3.333 lots, quantized down to 3.33",
        dims(sizing="pct-risk-step", path="target"),
        [SIGNAL, FLAT_UP, ["101.00", "102.50", "103.51", "103.30"], TAIL],
        [entry("BUY", sizing=("pctRisk", "0.5"), exit=by("1.50", "3.00"))],
        instrument=dict(CS10),
    ),
    Case(
        "short-market-sizing-risk-usd-below-min-rejected-ticks-paper",
        "SIZING RISK $ 0.1 over a 2.00 stop and contractSize 10 is 0.005 lots, below volumeMin: rejected, no trade",
        dims(sizing="risk-usd-below-min", side="short", path="none"),
        [SIGNAL, FLAT_UP, SHORT_STOP_HIT, TAIL],
        [entry("SELL", sizing=("riskUsd", "0.1"), exit=by("2.00", "4.00"))],
        instrument=dict(CS10),
    ),
    Case(
        "long-market-sizing-risk-usd-ticks-paper",
        "SIZING RISK $ 50 over a 1.50 stop and contractSize 10 is 3.333 lots, quantized to 3.33; the 2.00 target fills",
        dims(sizing="risk-usd", path="target"),
        [SIGNAL, FLAT_UP, LONG_TARGET_HIT, TAIL],
        [entry("BUY", sizing=("riskUsd", "50"), exit=by("1.50", "2.00"))],
        instrument=dict(CS10),
    ),
    # --- costs ------------------------------------------------------------------------------------------
    Case(
        "long-market-commission-bracket-target-ticks-paper",
        "commissionPerLot 7 charges 0.70 on the 0.1-lot entry and again on the exit; realised is net per fill",
        dims(costs="commission", path="target"),
        [SIGNAL, FLAT_UP, LONG_TARGET_HIT, TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        instrument={**BASE, "commissionPerLot": "7"},
    ),
    Case(
        "short-market-commission-spread-slippage-stop-ticks-mt5",
        "mt5-sim with commission, feed spread and slippage: entry at bid minus slip, stop at ask plus slip, 0.35 per fill",
        dims(side="short", broker="mt5-sim", costs="commission+spread+slippage"),
        [SIGNAL, FLAT_UP, ["100.80", "101.20", "101.46", "101.30"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        instrument={**BASE, "commissionPerLot": "3.5", "slippagePoints": 2},
        broker="mt5-sim",
    ),
    Case(
        "long-market-spreadpoints-override-target-ticks-mt5",
        "spreadPoints 10 prices the entry at mid + 0.05; the target triggers on the feed's bid and, a limit, fills no worse than its price",
        dims(broker="mt5-sim", costs="spreadPoints", path="target"),
        [SIGNAL, FLAT_UP, ["101.00", "102.00", "102.56", "102.30"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
        instrument={**BASE, "spreadPoints": 10},
        broker="mt5-sim",
        notes=["backtest-model.md:166-168: a limit's cap holds against a stated spread, so the target fills at its level (README Q11)."],
    ),
    Case(
        "long-market-gap-stop-ticks-paper",
        "a tick that gaps through the stop fills at that tick's mid, beyond the level",
        dims(path="gap-stop"),
        [SIGNAL, FLAT_UP, ["100.20", "100.00", "99.00", "99.20"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"))],
    ),
    Case(
        "short-market-gap-stop-ticks-mt5-slippage",
        "a tick that gaps through a short stop fills at that tick's ask plus slippage",
        dims(side="short", path="gap-stop", broker="mt5-sim", costs="spread+slippage"),
        [SIGNAL, FLAT_UP, ["100.60", "100.80", "102.00", "101.90"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        instrument={**BASE, "slippagePoints": 3},
        broker="mt5-sim",
    ),
    # --- answered doc questions (README "Questions the docs now answer") ---------------------------------
    Case(
        "long-market-sizing-usd-gap-open-ticks-paper",
        "SIZING 1000 USD sizes at the signal close 100.50 (9.95 lots) and fills on the next tick at 101.00",
        dims(sizing="usd", path="target"),
        [SIGNAL, ["101.00", "101.20", "100.80", "101.10"], ["101.50", "102.50", "103.01", "102.80"], TAIL],
        [entry("BUY", sizing=("usd", "1000"), exit=by("1.00", "2.00"))],
        notes=["sizing.md:66-68 sizes a market order at the signal bar's close; backtest-model.md:89-96 fills it on the tick that closed the bar (README Q1, Q2)."],
    ),
    Case(
        "long-limit-sizing-pct-equity-ticks-paper",
        "SIZING 5 PCT OF EQUITY on a LIMIT AT 99.80 sizes at the limit price: 500 / 99.80 = 5.01 lots",
        dims(entry="limit", sizing="pct-equity", path="target"),
        [SIGNAL, ["100.50", "100.30", "100.00", "99.90"], ["99.81", "99.79", "99.60", "99.70"], ["100.00", "100.50", "100.80", "100.60"], TAIL],
        [entry("BUY", sizing=("pctEquity", "5"), order_type={"type": "LIMIT", "price": "99.80"}, exit=by("1.00", "1.00"))],
        notes=["sizing.md:69-70: a LIMIT entry sizes at its own price, not the signal close (README Q1)."],
    ),
    Case(
        "long-market-digits-half-even-stop-ticks-mt5",
        "spreadPoints 3 puts fills on half a point; digits 2 rounds half to even: buy 100.525 -> 100.52, sell 99.485 -> 99.48",
        dims(broker="mt5-sim", costs="spreadPoints", path="gap-stop"),
        [["100.00", "100.10", "99.90", "100.51"], ["100.51", "100.80", "100.30", "100.60"], ["100.00", "99.80", "99.50", "99.70"], TAIL],
        [entry("BUY", exit=by("1.00", "2.00"), at="100.51")],
        instrument={**BASE, "spreadPoints": 3},
        broker="mt5-sim",
        notes=["backtest-model.md:121-122: mt5-sim rounds fill prices to digits half to even (README Q5)."],
    ),
    Case(
        "short-stoplimit-marketable-ticks-mt5",
        "a sell stop-limit whose trigger bid 99.94 already satisfies its 99.90 limit fills on that tick at the bid",
        dims(entry="stop-limit", side="short", path="target", broker="mt5-sim", costs="spread"),
        [SIGNAL, ["100.50", "100.20", "99.95", "99.80"], ["99.50", "99.00", "98.93", "99.10"], TAIL],
        [entry("SELL", order_type={"type": "STOP_LIMIT", "stop": "100.00", "limit": "99.90"}, exit=by("1.00", "1.00"))],
        broker="mt5-sim",
        notes=["actions.md:133-135: the limit fills on the tick that trips the stop when that tick already satisfies it (README Q6)."],
    ),
    Case(
        "short-market-gap-target-ticks-mt5",
        "a tick whose ask gaps through a short's target fills at that ask, better than the level (--tp-fill print, the default)",
        dims(side="short", path="gap-target", broker="mt5-sim", costs="spread"),
        [SIGNAL, FLAT_UP, ["100.00", "99.80", "97.50", "98.00"], TAIL],
        [entry("SELL", exit=by("1.00", "2.00"))],
        broker="mt5-sim",
        notes=["backtest-model.md:135-137: --tp-fill defaults to print, the crossing price when it beats the level (README Q7)."],
    ),
    Case(
        "short-limit-entry-gap-bars-synthetic-paper",
        "a pending sell limit the next bar opens beyond fills at that open on the synthetic path",
        dims(fills="bars-synthetic", entry="limit", side="short", path="target"),
        [SIGNAL, ["100.50", "100.90", "100.70", "101.00"], ["101.50", "101.80", "101.40", "101.60"], ["101.00", "100.80", "100.40", "100.60"], TAIL],
        [entry("SELL", order_type={"type": "LIMIT", "price": "101.20"}, exit=by("1.00", "1.00"))],
        fill_mode="bars-synthetic",
        notes=["backtest-model.md:85-88: a resting entry the bar opens beyond fills at that open (README Q8)."],
    ),
    Case(
        "long-market-partial-close-then-close-hedging-ticks-paper",
        "on a hedging account SELL 0.5 * POSITION closes half the leg by ticket; CLOSE realises the rest",
        dims(exit="partial-close", path="close", mode="hedging"),
        [SIGNAL, FLAT_UP, ["101.00", "101.50", "101.80", "102.00"], ["102.00", "102.20", "102.60", "102.80"], ["102.90", "103.20", "102.80", "103.00"], ["103.00", "103.10", "102.90", "103.05"], TAIL],
        [entry("BUY"), reduce("long", "SELL", "0.5", "102.00"), close("long", "103.00")],
        notes=["actions.md:102-104: a smaller opposite order closes that part of the oldest leg (README Q9)."],
    ),
    Case(
        "long-market-reverse-netting-ticks-paper",
        "SELL 0.2 over a 0.1 long on a netting account reverses in one fill: realises the long, opens a 0.1 short",
        dims(exit="close-rule", path="close", mode="netting"),
        [SIGNAL, FLAT_UP, ["100.50", "101.00", "101.50", "102.00"], ["102.00", "101.50", "101.20", "101.00"], TAIL],
        [entry("BUY"), reverse("long", "SELL", "0.2", "102.00"), close("short", "101.00")],
        position_mode="netting",
        notes=["actions.md:105-108: netting reverses an oversized opposite order in one fill (README Q10)."],
    ),
    Case(
        "long-market-reverse-hedging-ticks-paper",
        "SELL 0.2 over a 0.1 long on a hedging account closes the leg by ticket and opens the 0.1 excess as a short",
        dims(exit="close-rule", path="close", mode="hedging"),
        [SIGNAL, FLAT_UP, ["100.50", "101.00", "101.50", "102.00"], ["102.00", "101.50", "101.20", "101.00"], TAIL],
        [entry("BUY"), reverse("long", "SELL", "0.2", "102.00"), close("short", "101.00")],
        notes=["actions.md:105-108: hedging closes the opposite legs, then opens the excess, two deals at one price (README Q10)."],
    ),
]
