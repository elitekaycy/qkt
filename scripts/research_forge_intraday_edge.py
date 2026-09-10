#!/usr/bin/env python3
"""Measure intraday sweep/FVG retest hypotheses on qkt-forge OHLC bars.

This is intentionally a research harness, not a production strategy. It reads
qkt-forge bar CSVs, derives deterministic event labels, simulates passive
retest entries, and reports R-multiple evidence with cost and year splits.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import json
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd


DEFAULT_SYMBOLS = ("EURUSD", "XAUUSD", "XAGUSD", "COPPERCMDUSD", "LIGHTCMDUSD")
DEFAULT_DATA_ROOT = Path("/root/projects/qkt-forge/run/data/_bars")
DEFAULT_DATE_FROM = "2018-01-01"
DEFAULT_DATE_TO = "2021-07-02"


@dataclass(frozen=True)
class PatternResult:
    symbol: str
    pattern: str
    stop_model: str
    window: str
    side: str
    rr: float
    events: int
    entries: int
    wins: int
    losses: int
    unresolved: int
    expectancy_r: float | None
    win_rate: float | None
    profit_factor: float | None
    median_cost_r: float | None
    median_risk_price: float | None
    yearly: dict[str, dict[str, float | int | None]]


def date_range(date_from: str, date_to: str) -> Iterable[str]:
    current = date.fromisoformat(date_from)
    end = date.fromisoformat(date_to)
    while current <= end:
        yield current.isoformat()
        current += timedelta(days=1)


def tick_data_root(data_root: Path) -> Path:
    return data_root.parent if data_root.name == "_bars" else data_root


def build_ohlc_from_ticks(
    data_root: Path,
    symbol: str,
    timeframe: str,
    date_from: str,
    date_to: str,
) -> Path:
    if not timeframe.endswith("m"):
        raise FileNotFoundError(f"cannot derive {timeframe} OHLC from ticks without minute timeframe")
    minutes = int(timeframe[:-1])
    if minutes <= 0:
        raise ValueError(f"invalid timeframe: {timeframe}")

    bars_root = data_root if data_root.name == "_bars" else data_root / "_bars"
    cache = bars_root / f"{symbol}_{timeframe}_ohlc_{date_from}_{date_to}.csv"
    if cache.exists():
        return cache

    root = tick_data_root(data_root)
    bin_ms = minutes * 60_000
    rows: list[tuple[int, float, float, float, float, float]] = []
    current_bin: int | None = None
    open_mid = high_mid = low_mid = close_mid = spread_sum = 0.0
    ticks = 0

    def flush() -> None:
        nonlocal current_bin, open_mid, high_mid, low_mid, close_mid, spread_sum, ticks
        if current_bin is not None and ticks > 0:
            rows.append((current_bin, open_mid, high_mid, low_mid, close_mid, spread_sum / ticks))
        current_bin = None
        open_mid = high_mid = low_mid = close_mid = spread_sum = 0.0
        ticks = 0

    for ds in date_range(date_from, date_to):
        path = root / "symbols" / symbol / f"{ds}.csv.gz"
        if not path.exists():
            continue
        with gzip.open(path, "rt", newline="") as fh:
            reader = csv.reader(fh)
            next(reader, None)
            for row in reader:
                try:
                    ts = int(row[0])
                    bid = float(row[4])
                    ask = float(row[5])
                except (IndexError, ValueError):
                    continue
                if bid <= 0 or ask <= 0:
                    continue
                bin_start = ts - (ts % bin_ms)
                mid = (bid + ask) / 2.0
                spread = ask - bid
                if current_bin != bin_start:
                    flush()
                    current_bin = bin_start
                    open_mid = high_mid = low_mid = close_mid = mid
                    spread_sum = spread
                    ticks = 1
                else:
                    high_mid = max(high_mid, mid)
                    low_mid = min(low_mid, mid)
                    close_mid = mid
                    spread_sum += spread
                    ticks += 1
    flush()

    if not rows:
        raise FileNotFoundError(f"no cached ticks for {symbol} {date_from}..{date_to}")
    bars_root.mkdir(parents=True, exist_ok=True)
    with cache.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerows(rows)
    return cache


def load_ohlc(data_root: Path, symbol: str, timeframe: str, date_from: str, date_to: str) -> pd.DataFrame:
    exact = data_root / f"{symbol}_{timeframe}_ohlc_{date_from}_{date_to}.csv"
    if exact.exists():
        files = [exact]
    else:
        pattern = f"{symbol}_{timeframe}_ohlc_*.csv"
        candidates = sorted(data_root.glob(pattern))
        files = []
        for file in candidates:
            parts = file.stem.split("_")
            if len(parts) < 5:
                continue
            candidate_from = parts[-2]
            candidate_to = parts[-1]
            if date_from >= candidate_from and date_to <= candidate_to:
                files.append(file)
    if not files and timeframe == "1m":
        files = [build_ohlc_from_ticks(data_root, symbol, timeframe, date_from, date_to)]
    elif not files and timeframe.endswith("m"):
        files = [build_ohlc_from_ticks(data_root, symbol, timeframe, date_from, date_to)]
    if not files:
        raise FileNotFoundError(f"no OHLC files for {symbol} {timeframe}")
    dfs = [
        pd.read_csv(
            file,
            header=None,
            names=["ts", "open", "high", "low", "close", "spread"],
        )
        for file in files
    ]
    df = pd.concat(dfs, ignore_index=True)
    df = df.drop_duplicates("ts").sort_values("ts").reset_index(drop=True)
    start_ms = int(pd.Timestamp(date_from, tz="UTC").timestamp() * 1000)
    end_ms = int((pd.Timestamp(date_to, tz="UTC") + pd.Timedelta(days=1)).timestamp() * 1000)
    df = df[(df["ts"] >= start_ms) & (df["ts"] < end_ms)].reset_index(drop=True)
    df["dt"] = pd.to_datetime(df["ts"], unit="ms", utc=True)
    df["year"] = df["dt"].dt.year
    df["hour"] = df["dt"].dt.hour
    df["minute"] = df["dt"].dt.minute
    df["dow"] = df["dt"].dt.dayofweek
    df["range"] = df["high"] - df["low"]
    df["atr36"] = df["range"].rolling(36, min_periods=20).mean()
    return df


def window_masks(df: pd.DataFrame) -> dict[str, pd.Series]:
    hour_edge = df["minute"].isin([0, 5, 50, 55])
    session_edge = (
        df["hour"].isin([0, 6, 7, 8, 12, 13, 14, 16, 17, 20, 21, 22, 23])
        & df["minute"].isin([0, 5, 50, 55])
    )
    liquid_overlap = df["hour"].between(7, 16) & df["dow"].between(0, 4)
    off_window = (~hour_edge) & liquid_overlap
    return {
        "hour_edge": hour_edge,
        "session_edge": session_edge,
        "liquid_control": off_window,
        "all": pd.Series(True, index=df.index),
    }


def detect_sweeps(df: pd.DataFrame, lookback: int, atr_stop_mult: float, structure_buffer_atr: float) -> pd.DataFrame:
    prior_high = df["high"].shift(1).rolling(lookback, min_periods=lookback).max()
    prior_low = df["low"].shift(1).rolling(lookback, min_periods=lookback).min()
    atr = df["atr36"]

    bear = (df["high"] > prior_high) & (df["close"] < prior_high)
    bull = (df["low"] < prior_low) & (df["close"] > prior_low)

    rows = []
    for idx in df.index[bear.fillna(False)]:
        wick_mid = (float(df.at[idx, "high"]) + float(max(df.at[idx, "open"], df.at[idx, "close"]))) / 2.0
        micro_stop = float(df.at[idx, "high"] + max(atr.at[idx] * 0.10, df.at[idx, "spread"]))
        atr_stop = float(wick_mid + atr_stop_mult * atr.at[idx])
        structure_stop = float(df.at[idx, "high"] + structure_buffer_atr * atr.at[idx])
        rows.append(
            {
                "idx": int(idx),
                "pattern": "sweep_fade",
                "side": "short",
                "entry": wick_mid,
                "stop_micro": micro_stop,
                "stop_atr": atr_stop,
                "stop_structure": max(micro_stop, structure_stop),
            }
        )
    for idx in df.index[bull.fillna(False)]:
        wick_mid = (float(df.at[idx, "low"]) + float(min(df.at[idx, "open"], df.at[idx, "close"]))) / 2.0
        micro_stop = float(df.at[idx, "low"] - max(atr.at[idx] * 0.10, df.at[idx, "spread"]))
        atr_stop = float(wick_mid - atr_stop_mult * atr.at[idx])
        structure_stop = float(df.at[idx, "low"] - structure_buffer_atr * atr.at[idx])
        rows.append(
            {
                "idx": int(idx),
                "pattern": "sweep_fade",
                "side": "long",
                "entry": wick_mid,
                "stop_micro": micro_stop,
                "stop_atr": atr_stop,
                "stop_structure": min(micro_stop, structure_stop),
            }
        )
    return pd.DataFrame(rows)


def detect_fvgs(df: pd.DataFrame, displacement_mult: float, atr_stop_mult: float, structure_buffer_atr: float) -> pd.DataFrame:
    atr = df["atr36"]
    bull = (df["low"] > df["high"].shift(2)) & (df["range"] >= atr * displacement_mult)
    bear = (df["high"] < df["low"].shift(2)) & (df["range"] >= atr * displacement_mult)

    rows = []
    for idx in df.index[bull.fillna(False)]:
        low = float(df.at[idx, "low"])
        high_2 = float(df.at[idx - 2, "high"])
        midpoint = (low + high_2) / 2.0
        micro_stop = high_2 - max(float(atr.at[idx]) * 0.10, float(df.at[idx, "spread"]))
        atr_stop = midpoint - float(atr_stop_mult * atr.at[idx])
        structure_stop = min(float(df.at[idx - 2, "low"]), float(df.at[idx, "low"])) - float(structure_buffer_atr * atr.at[idx])
        rows.append(
            {
                "idx": int(idx),
                "pattern": "fvg_retest_cont",
                "side": "long",
                "entry": midpoint,
                "stop_micro": micro_stop,
                "stop_atr": atr_stop,
                "stop_structure": min(micro_stop, structure_stop),
            }
        )
    for idx in df.index[bear.fillna(False)]:
        high = float(df.at[idx, "high"])
        low_2 = float(df.at[idx - 2, "low"])
        midpoint = (high + low_2) / 2.0
        micro_stop = low_2 + max(float(atr.at[idx]) * 0.10, float(df.at[idx, "spread"]))
        atr_stop = midpoint + float(atr_stop_mult * atr.at[idx])
        structure_stop = max(float(df.at[idx - 2, "high"]), float(df.at[idx, "high"])) + float(structure_buffer_atr * atr.at[idx])
        rows.append(
            {
                "idx": int(idx),
                "pattern": "fvg_retest_cont",
                "side": "short",
                "entry": midpoint,
                "stop_micro": micro_stop,
                "stop_atr": atr_stop,
                "stop_structure": max(micro_stop, structure_stop),
            }
        )
    return pd.DataFrame(rows)


def simulate(
    df: pd.DataFrame,
    events: pd.DataFrame,
    window: pd.Series,
    rr: float,
    entry_wait_bars: int,
    hold_bars: int,
    stop_model: str,
    max_entry_cost_r: float | None,
) -> pd.DataFrame:
    if events.empty:
        return pd.DataFrame()
    rows = []
    window = window.reindex(df.index).fillna(False).to_numpy(dtype=bool)
    highs = df["high"].to_numpy(dtype=float)
    lows = df["low"].to_numpy(dtype=float)
    closes = df["close"].to_numpy(dtype=float)
    spreads = df["spread"].to_numpy(dtype=float)
    years = df["year"].to_numpy(dtype=int)
    n = len(df)
    for event in events.itertuples(index=False):
        idx = int(event.idx)
        if idx >= n - 2 or not bool(window[idx]):
            continue
        side = event.side
        entry = float(event.entry)
        stop = float(getattr(event, f"stop_{stop_model}"))
        risk = abs(entry - stop)
        if not np.isfinite(risk) or risk <= 0:
            continue

        entry_idx = None
        max_entry_idx = min(n - 1, idx + entry_wait_bars)
        for j in range(idx + 1, max_entry_idx + 1):
            if lows[j] <= entry <= highs[j]:
                entry_idx = j
                break
        if entry_idx is None:
            continue
        entry_cost_r = (spreads[entry_idx] * 2.0) / risk
        if max_entry_cost_r is not None and entry_cost_r > max_entry_cost_r:
            continue

        if side == "long":
            target = entry + rr * risk
        else:
            target = entry - rr * risk

        exit_idx = None
        gross_r = None
        max_exit_idx = min(n - 1, entry_idx + hold_bars)
        for j in range(entry_idx, max_exit_idx + 1):
            low = lows[j]
            high = highs[j]
            if side == "long":
                stop_hit = low <= stop
                target_hit = high >= target
            else:
                stop_hit = high >= stop
                target_hit = low <= target
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

        cost_price = spreads[entry_idx] + spreads[exit_idx]
        cost_r = cost_price / risk
        rows.append(
            {
                "symbol": event.symbol,
                "pattern": event.pattern,
                "stop_model": stop_model,
                "side": side,
                "event_idx": idx,
                "entry_idx": entry_idx,
                "exit_idx": exit_idx,
                "year": int(years[idx]),
                "gross_r": gross_r,
                "net_r": gross_r - cost_r,
                "risk_price": risk,
                "cost_r": cost_r,
            }
        )
    return pd.DataFrame(rows)


def summarize(
    symbol: str,
    pattern: str,
    stop_model: str,
    window_name: str,
    side: str,
    rr: float,
    event_count: int,
    trades: pd.DataFrame,
) -> PatternResult:
    if trades.empty:
        return PatternResult(symbol, pattern, stop_model, window_name, side, rr, event_count, 0, 0, 0, 0, None, None, None, None, None, {})
    net = trades["net_r"]
    wins = int((net > 0).sum())
    losses = int((net <= -0.999).sum())
    unresolved = int(((net <= 0) & (net > -0.999)).sum())
    gross_profit = float(net[net > 0].sum())
    gross_loss = float(-net[net < 0].sum())
    yearly = {}
    for year, sub in trades.groupby("year"):
        sub_net = sub["net_r"]
        y_gp = float(sub_net[sub_net > 0].sum())
        y_gl = float(-sub_net[sub_net < 0].sum())
        yearly[str(int(year))] = {
            "entries": int(len(sub)),
            "expectancy_r": float(sub_net.mean()),
            "win_rate": float((sub_net > 0).mean()),
            "profit_factor": (y_gp / y_gl) if y_gl > 0 else None,
        }
    return PatternResult(
        symbol=symbol,
        pattern=pattern,
        stop_model=stop_model,
        window=window_name,
        side=side,
        rr=rr,
        events=event_count,
        entries=int(len(trades)),
        wins=wins,
        losses=losses,
        unresolved=unresolved,
        expectancy_r=float(net.mean()),
        win_rate=float((net > 0).mean()),
        profit_factor=(gross_profit / gross_loss) if gross_loss > 0 else None,
        median_cost_r=float(trades["cost_r"].median()),
        median_risk_price=float(trades["risk_price"].median()),
        yearly=yearly,
    )


def analyze_symbol(args: argparse.Namespace, symbol: str) -> list[PatternResult]:
    df = load_ohlc(args.data_root, symbol, args.timeframe, args.date_from, args.date_to)
    windows = {name: mask for name, mask in window_masks(df).items() if name in set(args.windows)}
    sweep_events = detect_sweeps(df, args.sweep_lookback, args.atr_stop_mult, args.structure_buffer_atr)
    fvg_events = detect_fvgs(df, args.fvg_displacement, args.atr_stop_mult, args.structure_buffer_atr)
    for events in (sweep_events, fvg_events):
        if not events.empty:
            events["symbol"] = symbol
    all_events = pd.concat([sweep_events, fvg_events], ignore_index=True)
    if all_events.empty:
        return []

    results: list[PatternResult] = []
    for pattern in sorted(all_events["pattern"].unique()):
        if pattern not in set(args.patterns):
            continue
        pattern_events = all_events[all_events["pattern"] == pattern]
        for side in sorted(pattern_events["side"].unique()):
            side_events = pattern_events[pattern_events["side"] == side]
            for stop_model in args.stop_models:
                for window_name, mask in windows.items():
                    event_count = int(mask.reindex(df.index).fillna(False).loc[side_events["idx"]].sum())
                    for rr in args.rr:
                        trades = simulate(
                            df,
                            side_events,
                            mask,
                            rr,
                            args.entry_wait_bars,
                            args.hold_bars,
                            stop_model,
                            args.max_entry_cost_r,
                        )
                        results.append(summarize(symbol, pattern, stop_model, window_name, side, rr, event_count, trades))
    return results


def result_sort_key(item: PatternResult) -> tuple[float, int]:
    expectancy = item.expectancy_r if item.expectancy_r is not None else -999.0
    return (expectancy, item.entries)


def write_outputs(results: Iterable[PatternResult], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    rows = [asdict(result) for result in results]
    with (out_dir / "intraday_edge_summary.json").open("w", encoding="utf-8") as fh:
        json.dump(rows, fh, indent=2, sort_keys=True)

    flat_rows = []
    for row in rows:
        flat = dict(row)
        yearly = flat.pop("yearly")
        flat["profitable_years"] = sum(1 for y in yearly.values() if y["expectancy_r"] is not None and y["expectancy_r"] > 0)
        flat["years"] = len(yearly)
        flat_rows.append(flat)
    pd.DataFrame(flat_rows).sort_values(["expectancy_r", "entries"], ascending=[False, False]).to_csv(
        out_dir / "intraday_edge_summary.csv",
        index=False,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=DEFAULT_DATA_ROOT)
    parser.add_argument("--out-dir", type=Path, default=Path("run/research/intraday-edge"))
    parser.add_argument("--timeframe", default="5m")
    parser.add_argument("--date-from", default=DEFAULT_DATE_FROM)
    parser.add_argument("--date-to", default=DEFAULT_DATE_TO)
    parser.add_argument("--symbols", nargs="+", default=list(DEFAULT_SYMBOLS))
    parser.add_argument("--rr", nargs="+", type=float, default=[1.0, 2.0, 3.0, 5.0, 10.0, 20.0, 50.0])
    parser.add_argument("--windows", nargs="+", default=["hour_edge", "session_edge", "liquid_control", "all"])
    parser.add_argument("--patterns", nargs="+", default=["sweep_fade", "fvg_retest_cont"])
    parser.add_argument("--stop-models", nargs="+", default=["micro"])
    parser.add_argument("--atr-stop-mult", type=float, default=1.5)
    parser.add_argument("--structure-buffer-atr", type=float, default=0.5)
    parser.add_argument("--max-entry-cost-r", type=float)
    parser.add_argument("--sweep-lookback", type=int, default=12)
    parser.add_argument("--fvg-displacement", type=float, default=1.2)
    parser.add_argument("--entry-wait-bars", type=int, default=6)
    parser.add_argument("--hold-bars", type=int, default=36)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    results: list[PatternResult] = []
    for symbol in args.symbols:
        results.extend(analyze_symbol(args, symbol))
    write_outputs(results, args.out_dir)
    best = sorted(results, key=result_sort_key, reverse=True)[:30]
    for item in best:
        if item.entries < 30:
            continue
        print(
            f"{item.symbol:12s} {item.pattern:16s} {item.side:5s} {item.window:14s} "
            f"stop={item.stop_model:9s} rr={item.rr:4.1f} entries={item.entries:5d} expR={item.expectancy_r:7.3f} "
            f"wr={item.win_rate:5.3f} pf={item.profit_factor if item.profit_factor is not None else float('nan'):6.2f} "
            f"costR={item.median_cost_r:6.3f}"
        )


if __name__ == "__main__":
    main()
