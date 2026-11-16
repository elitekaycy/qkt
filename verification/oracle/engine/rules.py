"""The modelling rules, each written from one documented sentence. The docstrings cite the doc and line.

Paths are relative to the qkt repo root; line numbers as of the doc answers to #1717 (README "Questions the docs
now answer").
"""

from __future__ import annotations

from decimal import ROUND_CEILING, ROUND_FLOOR, ROUND_HALF_EVEN, Decimal
from typing import Optional

from model import Instrument, Level, OrderType, Sizing, Tick

LONG, SHORT = "BUY", "SELL"


def opposite(side: str) -> str:
    return SHORT if side == LONG else LONG


# --- price grid -------------------------------------------------------------------------------------------


def on_grid(level: Decimal, entry: Decimal, grid: Decimal) -> Decimal:
    """bracket.md:40-47: each bracket level goes on the symbol's price grid "once the entry fills ... The rule is
    the nearest tick, and an exact half tick goes toward the entry: a stop never risks more than written and a
    target never promises more." Worked example there: EURUSD at 1.161055, BY 0.001 / 0.002 -> 1.16006 / 1.16305.
    """
    lo = (level / grid).to_integral_value(rounding=ROUND_FLOOR) * grid
    hi = (level / grid).to_integral_value(rounding=ROUND_CEILING) * grid
    if lo == hi:
        return level
    below, above = level - lo, hi - level
    if below == above:
        return lo if entry <= level else hi
    return lo if below < above else hi


# --- bracket geometry -------------------------------------------------------------------------------------


def stop_level(side: str, entry: Decimal, leg: Level, inst: Instrument) -> Decimal:
    """bracket.md:49-57 `BY <points>`: "stop at entry - points" for a long (mirror for a short);
    bracket.md:64-72 `BY <pct> PCT`: "percent of entry price", `1 PCT` means 1 %;
    bracket.md:84-92 `AT <price>`: the absolute price computed when the rule fires.
    All three then go on the grid (on_grid)."""
    raw = _level(side, entry, leg, is_stop=True)
    return on_grid(raw, entry, inst.grid)


def target_level(side: str, entry: Decimal, leg: Level, inst: Instrument) -> Decimal:
    raw = _level(side, entry, leg, is_stop=False)
    return on_grid(raw, entry, inst.grid)


def _level(side: str, entry: Decimal, leg: Level, is_stop: bool) -> Decimal:
    if leg.at is not None:
        return leg.at
    sign = -1 if (side == LONG) == is_stop else 1  # long stop below / long target above; mirrored for a short
    if leg.by is not None:
        return entry + sign * leg.by
    assert leg.pct is not None
    return entry * (1 + sign * leg.pct / 100)


def stop_distance(side: str, price: Decimal, leg: Level, inst: Instrument) -> Decimal:
    """sizing.md:73: `PCT RISK` and `RISK $` "measure their stop distance from that same entry estimate"
    (sizing_price); sizing.md:101 resolves `AT`, `BY` and `PCT` stop prices with that entry geometry."""
    if leg.by is not None:
        return leg.by
    if leg.pct is not None:
        return price * leg.pct / 100
    assert leg.at is not None
    return abs(price - leg.at)


# --- sizing -----------------------------------------------------------------------------------------------


def sizing_price(order_type: OrderType, signal_close: Decimal) -> Decimal:
    """sizing.md:61-71 "The price and the notional sizing uses": a market order sizes at the close of the bar
    that fired the rule, not at its fill; `LIMIT AT p` and `STOP AT p` at their own price; `STOP AT s LIMIT AT l`
    at the stop price `s`; equity and balance are read at that same moment."""
    if order_type.kind == "MARKET":
        return signal_close
    assert order_type.price is not None
    return order_type.price


def quantize_volume(qty: Decimal, inst: Instrument) -> Optional[Decimal]:
    """backtest-model.md:80-81 (paper) and :120-121 (mt5-sim): "Volume rounds down to `volumeStep` and an order
    below `volumeMin` is rejected"; sizing.md:241-243: rounded *down*, an order below the minimum "is rejected
    (counted in the report's rejections), never sent at the minimum". None means rejected."""
    stepped = (qty / inst.volume_step).to_integral_value(rounding=ROUND_FLOOR) * inst.volume_step
    if stepped < inst.volume_min:
        return None
    return stepped


def size(
    sizing: Sizing, price: Decimal, equity: Decimal, balance: Decimal, stop_dist: Optional[Decimal], inst: Instrument
) -> Optional[Decimal]:
    """sizing.md "The forms available today" (lines 7-16) and each form's section:
    - fixed (:19-24): the literal, in the venue's unit (lots);
    - `USD` (:51-57): notional / (price x contractSize x quote-to-account rate), sizing.md:73-78 (the rate is 1
      here: a USD account trading a USD-quoted symbol);
    - `PCT OF EQUITY` (:30-45): equity x pct / 100 of position value at the current price;
    - `PCT OF BALANCE` (:40-45): the same on cash balance;
    - `PCT RISK` (:75-82): "if the stop hits, the loss is exactly N% of equity", sugar for `RISK N/100`;
    - `RISK $ N` (:15, :164): account-currency budget / (stop distance x contract size).
    Every result is quantized by quantize_volume (sizing.md:240-243)."""
    cs = inst.contract_size
    if sizing.form == "fixed":
        raw = sizing.value
    elif sizing.form == "usd":
        raw = sizing.value / (price * cs)
    elif sizing.form == "pctEquity":
        raw = equity * sizing.value / 100 / (price * cs)
    elif sizing.form == "pctBalance":
        raw = balance * sizing.value / 100 / (price * cs)
    elif sizing.form == "pctRisk":
        assert stop_dist is not None, "PCT RISK requires a bracket stop (sizing.md:103)"
        raw = equity * sizing.value / 100 / (stop_dist * cs)
    elif sizing.form == "riskUsd":
        assert stop_dist is not None, "RISK $ requires a bracket stop (sizing.md:103)"
        raw = sizing.value / (stop_dist * cs)
    else:
        raise ValueError(f"unknown sizing form {sizing.form}")
    return quantize_volume(raw, inst)


# --- triggers ---------------------------------------------------------------------------------------------


def quote_for(side: str, tick: Tick) -> Decimal:
    """backtest-model.md:77-78: a pending order "triggers on the first tick whose quote for its side reaches the
    level (the ask for a buy, the bid for a sell, as at the venue)"."""
    return tick.ask if side == LONG else tick.bid


def limit_touched(side: str, level: Decimal, quote: Decimal) -> bool:
    """A buy limit is reached when the ask falls to it; a sell limit when the bid rises to it."""
    return quote <= level if side == LONG else quote >= level


def stop_touched(side: str, level: Decimal, quote: Decimal) -> bool:
    """A buy stop is reached when the ask rises to it; a sell stop when the bid falls to it."""
    return quote >= level if side == LONG else quote <= level


# --- fill prices ------------------------------------------------------------------------------------------


def fill_quotes(tick: Tick, inst: Instrument) -> tuple[Decimal, Decimal]:
    """backtest-model.md:179: `spreadPoints: N` "fills at the tick's mid +- N/2 points, whatever spread the tick
    carries"; :183-184: "The fill price is the only thing they change: stops, limits ... still see the feed's
    quotes." Returns (bid, ask) used for pricing a fill."""
    if inst.spread_points is None:
        return tick.bid, tick.ask
    half = Decimal(inst.spread_points) * inst.point_size / 2
    return tick.mid - half, tick.mid + half


def paper_fill(tick: Tick) -> Decimal:
    """backtest-model.md:76-79: `paper` "fills at mid ... fills at that tick's mid, so it can fill up to half a
    spread better than its level, or beyond it on a gap. No spread, slippage or latency."""
    return tick.mid


def mt5_fill(side: str, tick: Tick, inst: Instrument, slipped: bool, limit: Optional[Decimal] = None) -> Decimal:
    """backtest-model.md:120-124: "A BUY fills at the ask and a SELL at the bid ... plus slippage"; :164-167:
    `mt5-sim` "slips market and stop fills against you by the instrument's `slippagePoints` (each point one
    `pointSize` wide)"; "A limit, take-profit included, is never filled worse than its price, so it is not slipped."
    backtest-model.md:167-168: the cap holds against a stated spread, so a target `spreadPoints` would price past
    its level fills at the level. :121-123: "fill prices round to `digits`, half to even ... after slippage and the
    limit cap" (round_digits)."""
    bid, ask = fill_quotes(tick, inst)
    slip = Decimal(inst.slippage_points) * inst.point_size if slipped else Decimal(0)
    price = ask + slip if side == LONG else bid - slip
    if limit is not None:
        price = min(price, limit) if side == LONG else max(price, limit)
    return round_digits(price, inst.digits)


def round_digits(price: Decimal, digits: int) -> Decimal:
    """backtest-model.md:121-122: "fill prices round to `digits`, half to even (with `digits: 2`, 100.525 fills at
    100.52 and 99.485 at 99.48)"."""
    return price.quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_EVEN)


# --- costs and P&L ----------------------------------------------------------------------------------------


def commission(qty: Decimal, inst: Instrument) -> Decimal:
    """backtest-data.md:577: `commissionPerLot` is "$ per lot per fill"; cli-commands.md:65: "per 1.0 lot per
    side". Charged on every fill, entry and exit."""
    return inst.commission_per_lot * qty


def gross_pnl(side: str, entry: Decimal, exit_price: Decimal, qty: Decimal, inst: Instrument) -> Decimal:
    """Price difference x lots x contractSize in the quote currency (USD account, USD-quoted symbol, so the
    conversion rate is 1: backtest-model.md "Equity"; the release audit's `verify.py` relation
    realised = (exit - entry) x qty x contractSize that qkt's own trades.csv reconciles to)."""
    diff = exit_price - entry if side == LONG else entry - exit_price
    return diff * qty * inst.contract_size
