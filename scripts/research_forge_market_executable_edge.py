#!/usr/bin/env python3
"""Measure qkt-executable intraday timing/level variants.

This harness deliberately avoids passive pending-limit fills and dynamic stop
prices because those were not faithfully executable in qkt-forge gate screens.
It tests market entries after a sweep/retest/reclaim has already printed, with
fixed price-distance stops and RR exits. Results are research evidence, not
production strategy output.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import pandas as pd

from research_forge_intraday_edge import DEFAULT_DATA_ROOT, load_ohlc, window_masks


DEFAULT_DATE_FROM = "2018-01-01"
DEFAULT_DATE_TO = "2021-07-02"


@dataclass(frozen=True)
class MarketResult:
    symbol: str
    timeframe: str
    pattern: str
    window: str
    side: str
    stop_price: float
    rr: float
    events: int
    trades: int
    wins: int
    losses: int
    unresolved: int
    expectancy_r: float | None
    win_rate: float | None
    profit_factor: float | None
    median_cost_r: float | None
    profitable_years: int
    years: int
    yearly: dict[str, dict[str, float | int | None]]


def previous_hour_levels(df: pd.DataFrame) -> tuple[pd.Series, pd.Series]:
    hour_start = df["dt"].dt.floor("h")
    hourly = (
        df.assign(hour_start=hour_start)
        .groupby("hour_start")
        .agg(hour_high=("high", "max"), hour_low=("low", "min"))
        .sort_index()
    )
    prev = hourly.shift(1)
    mapped_high = hour_start.map(prev["hour_high"])
    mapped_low = hour_start.map(prev["hour_low"])
    return mapped_high.astype(float), mapped_low.astype(float)


def asian_session_levels(df: pd.DataFrame) -> tuple[pd.Series, pd.Series]:
    day = df["dt"].dt.floor("D")
    asia = (
        df[df["hour"].between(0, 5)]
        .assign(day=day[df["hour"].between(0, 5)].to_numpy())
        .groupby("day")
        .agg(asian_high=("high", "max"), asian_low=("low", "min"))
        .sort_index()
    )
    mapped_high = day.map(asia["asian_high"])
    mapped_low = day.map(asia["asian_low"])
    usable = df["hour"] >= 6
    return mapped_high.where(usable).astype(float), mapped_low.where(usable).astype(float)


def previous_period_levels(df: pd.DataFrame, period: str) -> tuple[pd.Series, pd.Series]:
    naive_dt = df["dt"].dt.tz_convert(None)
    period_start = naive_dt.dt.to_period(period).dt.start_time.dt.tz_localize("UTC")
    levels = (
        df.assign(period_start=period_start)
        .groupby("period_start")
        .agg(period_high=("high", "max"), period_low=("low", "min"))
        .sort_index()
    )
    prev = levels.shift(1)
    mapped_high = period_start.map(prev["period_high"])
    mapped_low = period_start.map(prev["period_low"])
    return mapped_high.astype(float), mapped_low.astype(float)


def rolling_prior_levels(df: pd.DataFrame, lookback_bars: int) -> tuple[pd.Series, pd.Series]:
    high = df["high"].shift(1).rolling(lookback_bars, min_periods=max(20, lookback_bars // 4)).max()
    low = df["low"].shift(1).rolling(lookback_bars, min_periods=max(20, lookback_bars // 4)).min()
    return high.astype(float), low.astype(float)


def timeframe_minutes(df: pd.DataFrame) -> float:
    diffs = df["ts"].diff().dropna()
    if diffs.empty:
        return 1.0
    return float(diffs.median() / 60_000.0)


def detect_market_events(df: pd.DataFrame, pattern: str, lookback: int, displacement_mult: float) -> pd.DataFrame:
    atr = df["atr36"]
    rows: list[dict] = []

    if pattern == "rolling_sweep_reclaim":
        prior_high = df["high"].shift(1).rolling(lookback, min_periods=lookback).max()
        prior_low = df["low"].shift(1).rolling(lookback, min_periods=lookback).min()
        high_sweep = (df["high"] > prior_high) & (df["close"] < prior_high)
        low_sweep = (df["low"] < prior_low) & (df["close"] > prior_low)
        for idx in df.index[high_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "short", "entry": float(df.at[idx, "close"])})
        for idx in df.index[low_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "long", "entry": float(df.at[idx, "close"])})

    elif pattern == "previous_hour_sweep_reclaim":
        prev_high, prev_low = previous_hour_levels(df)
        high_sweep = (df["high"] > prev_high) & (df["close"] < prev_high)
        low_sweep = (df["low"] < prev_low) & (df["close"] > prev_low)
        for idx in df.index[high_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "short", "entry": float(df.at[idx, "close"])})
        for idx in df.index[low_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "long", "entry": float(df.at[idx, "close"])})

    elif pattern == "asian_range_sweep_reclaim":
        asian_high, asian_low = asian_session_levels(df)
        high_sweep = (df["high"] > asian_high) & (df["close"] < asian_high)
        low_sweep = (df["low"] < asian_low) & (df["close"] > asian_low)
        for idx in df.index[high_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "short", "entry": float(df.at[idx, "close"])})
        for idx in df.index[low_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "long", "entry": float(df.at[idx, "close"])})

    elif pattern in {"previous_day_sweep_reclaim", "previous_week_sweep_reclaim"}:
        period = "D" if pattern == "previous_day_sweep_reclaim" else "W"
        period_high, period_low = previous_period_levels(df, period)
        high_sweep = (df["high"] > period_high) & (df["close"] < period_high)
        low_sweep = (df["low"] < period_low) & (df["close"] > period_low)
        for idx in df.index[high_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "short", "entry": float(df.at[idx, "close"])})
        for idx in df.index[low_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "long", "entry": float(df.at[idx, "close"])})

    elif pattern in {"rolling_day_sweep_reclaim", "rolling_week_sweep_reclaim"}:
        minutes = timeframe_minutes(df)
        lookback_bars = int(round((24 * 60 if pattern == "rolling_day_sweep_reclaim" else 5 * 24 * 60) / minutes))
        period_high, period_low = rolling_prior_levels(df, lookback_bars)
        high_sweep = (df["high"] > period_high) & (df["close"] < period_high)
        low_sweep = (df["low"] < period_low) & (df["close"] > period_low)
        for idx in df.index[high_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "short", "entry": float(df.at[idx, "close"])})
        for idx in df.index[low_sweep.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "long", "entry": float(df.at[idx, "close"])})

    elif pattern == "fvg_market_retest_reject":
        bull_fvg = (df["low"].shift(1) > df["high"].shift(3)) & (df["range"].shift(1) >= atr.shift(1) * displacement_mult)
        bear_fvg = (df["high"].shift(1) < df["low"].shift(3)) & (df["range"].shift(1) >= atr.shift(1) * displacement_mult)
        bull_mid = (df["low"].shift(1) + df["high"].shift(3)) / 2.0
        bear_mid = (df["high"].shift(1) + df["low"].shift(3)) / 2.0
        bull_reject = bull_fvg & (df["low"] <= bull_mid) & (df["close"] > bull_mid)
        bear_reject = bear_fvg & (df["high"] >= bear_mid) & (df["close"] < bear_mid)
        for idx in df.index[bull_reject.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "long", "entry": float(df.at[idx, "close"])})
        for idx in df.index[bear_reject.fillna(False)]:
            rows.append({"idx": int(idx), "pattern": pattern, "side": "short", "entry": float(df.at[idx, "close"])})

    else:
        raise ValueError(f"unknown pattern: {pattern}")

    return pd.DataFrame(rows)


def simulate_market(
    df: pd.DataFrame,
    events: pd.DataFrame,
    window: pd.Series,
    side: str,
    stop_price: float,
    rr: float,
    hold_bars: int,
    max_entry_cost_r: float | None,
) -> pd.DataFrame:
    if events.empty:
        return pd.DataFrame()
    window_arr = window.reindex(df.index).fillna(False).to_numpy(dtype=bool)
    highs = df["high"].to_numpy(dtype=float)
    lows = df["low"].to_numpy(dtype=float)
    closes = df["close"].to_numpy(dtype=float)
    spreads = df["spread"].to_numpy(dtype=float)
    years = df["year"].to_numpy(dtype=int)
    rows: list[dict] = []
    n = len(df)

    for event in events[events["side"] == side].itertuples(index=False):
        idx = int(event.idx)
        if idx >= n - 2 or not bool(window_arr[idx]):
            continue
        entry = float(event.entry)
        risk = float(stop_price)
        entry_cost_r = (spreads[idx] * 2.0) / risk
        if max_entry_cost_r is not None and entry_cost_r > max_entry_cost_r:
            continue
        stop = entry - risk if side == "long" else entry + risk
        target = entry + rr * risk if side == "long" else entry - rr * risk
        gross_r = None
        exit_idx = None
        max_exit_idx = min(n - 1, idx + hold_bars)
        for j in range(idx + 1, max_exit_idx + 1):
            if side == "long":
                stop_hit = lows[j] <= stop
                target_hit = highs[j] >= target
            else:
                stop_hit = highs[j] >= stop
                target_hit = lows[j] <= target
            if stop_hit:
                exit_idx = j
                gross_r = -1.0
                break
            if target_hit:
                exit_idx = j
                gross_r = rr
                break
        if gross_r is None:
            exit_idx = max_exit_idx
            close = closes[exit_idx]
            gross_r = (close - entry) / risk if side == "long" else (entry - close) / risk
        cost_r = (spreads[idx] + spreads[exit_idx]) / risk
        rows.append(
            {
                "year": int(years[idx]),
                "event_idx": idx,
                "exit_idx": exit_idx,
                "gross_r": gross_r,
                "net_r": gross_r - cost_r,
                "cost_r": cost_r,
            }
        )
    return pd.DataFrame(rows)


def summarize(
    symbol: str,
    timeframe: str,
    pattern: str,
    window: str,
    side: str,
    stop_price: float,
    rr: float,
    events: int,
    trades: pd.DataFrame,
) -> MarketResult:
    if trades.empty:
        return MarketResult(symbol, timeframe, pattern, window, side, stop_price, rr, events, 0, 0, 0, 0, None, None, None, None, 0, 0, {})
    net = trades["net_r"]
    wins = int((net > 0).sum())
    losses = int((net <= -0.999).sum())
    unresolved = int(((net <= 0) & (net > -0.999)).sum())
    gp = float(net[net > 0].sum())
    gl = float(-net[net < 0].sum())
    yearly: dict[str, dict[str, float | int | None]] = {}
    for year, sub in trades.groupby("year"):
        sub_net = sub["net_r"]
        ygp = float(sub_net[sub_net > 0].sum())
        ygl = float(-sub_net[sub_net < 0].sum())
        yearly[str(int(year))] = {
            "trades": int(len(sub)),
            "expectancy_r": float(sub_net.mean()),
            "win_rate": float((sub_net > 0).mean()),
            "profit_factor": (ygp / ygl) if ygl > 0 else None,
        }
    return MarketResult(
        symbol=symbol,
        timeframe=timeframe,
        pattern=pattern,
        window=window,
        side=side,
        stop_price=stop_price,
        rr=rr,
        events=events,
        trades=int(len(trades)),
        wins=wins,
        losses=losses,
        unresolved=unresolved,
        expectancy_r=float(net.mean()),
        win_rate=float((net > 0).mean()),
        profit_factor=(gp / gl) if gl > 0 else None,
        median_cost_r=float(trades["cost_r"].median()),
        profitable_years=sum(1 for y in yearly.values() if y["expectancy_r"] is not None and y["expectancy_r"] > 0),
        years=len(yearly),
        yearly=yearly,
    )


def write_outputs(results: list[MarketResult], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = [asdict(result) for result in results]
    (out_dir / "market_executable_summary.json").write_text(json.dumps(rows, indent=2, sort_keys=True))
    flat = []
    for row in rows:
        copy = dict(row)
        copy.pop("yearly")
        flat.append(copy)
    pd.DataFrame(flat).sort_values(["expectancy_r", "trades"], ascending=[False, False]).to_csv(
        out_dir / "market_executable_summary.csv",
        index=False,
    )


def stop_grid_for(symbol: str, timeframe: str) -> list[float]:
    if symbol == "EURUSD":
        return [0.0005, 0.0007, 0.0010, 0.0015, 0.0020]
    if symbol in {"XAUUSD", "XAGUSD"}:
        return [1.0, 1.5, 2.5, 4.0, 6.0] if symbol == "XAUUSD" else [0.015, 0.025, 0.04, 0.06, 0.10]
    return [0.5, 1.0, 1.5, 2.5, 4.0]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=DEFAULT_DATA_ROOT)
    parser.add_argument("--out-dir", type=Path, default=Path("run/research/market-executable-edge"))
    parser.add_argument("--timeframe", default="1m")
    parser.add_argument("--date-from", default=DEFAULT_DATE_FROM)
    parser.add_argument("--date-to", default=DEFAULT_DATE_TO)
    parser.add_argument("--symbols", nargs="+", default=["EURUSD", "XAUUSD"])
    parser.add_argument("--patterns", nargs="+", default=[
        "rolling_sweep_reclaim",
        "previous_hour_sweep_reclaim",
        "asian_range_sweep_reclaim",
        "previous_day_sweep_reclaim",
        "previous_week_sweep_reclaim",
        "rolling_day_sweep_reclaim",
        "rolling_week_sweep_reclaim",
        "fvg_market_retest_reject",
    ])
    parser.add_argument("--windows", nargs="+", default=["hour_edge", "session_edge", "liquid_control", "all"])
    parser.add_argument("--rr", nargs="+", type=float, default=[1.0, 2.0, 3.0, 5.0, 10.0])
    parser.add_argument("--stops", nargs="+", type=float)
    parser.add_argument("--sides", nargs="+", default=["long", "short"])
    parser.add_argument("--lookback", type=int, default=12)
    parser.add_argument("--fvg-displacement", type=float, default=1.2)
    parser.add_argument("--hold-bars", type=int, default=36)
    parser.add_argument("--max-entry-cost-r", type=float, default=0.10)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    results: list[MarketResult] = []
    for symbol in args.symbols:
        df = load_ohlc(args.data_root, symbol, args.timeframe, args.date_from, args.date_to)
        windows = {name: mask for name, mask in window_masks(df).items() if name in set(args.windows)}
        stops = args.stops if args.stops else stop_grid_for(symbol, args.timeframe)
        for pattern in args.patterns:
            events = detect_market_events(df, pattern, args.lookback, args.fvg_displacement)
            if events.empty:
                continue
            for window_name, window in windows.items():
                for side in args.sides:
                    event_count = int(window.reindex(df.index).fillna(False).loc[events[events["side"] == side]["idx"]].sum())
                    for stop_price in stops:
                        for rr in args.rr:
                            trades = simulate_market(
                                df,
                                events,
                                window,
                                side,
                                stop_price,
                                rr,
                                args.hold_bars,
                                args.max_entry_cost_r,
                            )
                            results.append(
                                summarize(symbol, args.timeframe, pattern, window_name, side, stop_price, rr, event_count, trades)
                            )
    write_outputs(results, args.out_dir)
    for row in sorted(results, key=lambda r: ((r.expectancy_r or -999), r.trades), reverse=True)[:30]:
        if row.trades < 30:
            continue
        print(
            f"{row.symbol:8s} {row.timeframe:3s} {row.pattern:28s} {row.window:14s} {row.side:5s} "
            f"stop={row.stop_price:g} rr={row.rr:g} trades={row.trades:5d} "
            f"expR={row.expectancy_r:7.3f} pf={row.profit_factor if row.profit_factor is not None else float('nan'):6.2f} "
            f"costR={row.median_cost_r:6.3f} posYears={row.profitable_years}/{row.years}"
        )


if __name__ == "__main__":
    main()
