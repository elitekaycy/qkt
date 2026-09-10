#!/usr/bin/env python3
"""Audit qkt-forge OHLC bar cache against independently rebuilt tick bars.

The research harness depends on qkt-forge's cached bars and tick files. This
script rebuilds OHLC/spread bars from cached bid/ask ticks into a temporary
directory, compares them to the bar cache qkt-forge already uses, and reports
coverage and numeric drift.
"""

from __future__ import annotations

import argparse
import csv
import gzip
from pathlib import Path

import pandas as pd

from research_forge_intraday_edge import date_range, load_ohlc, tick_data_root


def rebuild_from_ticks(data_root: Path, symbol: str, timeframe: str, date_from: str, date_to: str) -> pd.DataFrame:
    if not timeframe.endswith("m"):
        raise ValueError(f"only minute bars are supported: {timeframe}")
    minutes = int(timeframe[:-1])
    bin_ms = minutes * 60_000
    root = tick_data_root(data_root)
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
    return pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close", "spread"])


def compare(data_root: Path, symbol: str, timeframe: str, date_from: str, date_to: str) -> dict:
    cached = load_ohlc(data_root, symbol, timeframe, date_from, date_to)
    rebuilt = rebuild_from_ticks(data_root, symbol, timeframe, date_from, date_to)
    merged = cached[["ts", "open", "high", "low", "close", "spread"]].merge(
        rebuilt[["ts", "open", "high", "low", "close", "spread"]],
        on="ts",
        suffixes=("_cached", "_rebuilt"),
        how="outer",
        indicator=True,
    )
    both = merged[merged["_merge"] == "both"].copy()
    diffs = {}
    for col in ["open", "high", "low", "close", "spread"]:
        delta = (both[f"{col}_cached"] - both[f"{col}_rebuilt"]).abs()
        diffs[col] = {
            "max_abs": float(delta.max()) if len(delta) else None,
            "mean_abs": float(delta.mean()) if len(delta) else None,
            "nonzero": int((delta > 1e-12).sum()) if len(delta) else 0,
        }
    return {
        "symbol": symbol,
        "timeframe": timeframe,
        "date_from": date_from,
        "date_to": date_to,
        "cached_rows": int(len(cached)),
        "rebuilt_rows": int(len(rebuilt)),
        "matched_rows": int(len(both)),
        "cached_only": int((merged["_merge"] == "left_only").sum()),
        "rebuilt_only": int((merged["_merge"] == "right_only").sum()),
        "diffs": diffs,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=Path("/root/projects/qkt-forge/run/data/_bars"))
    parser.add_argument("--timeframe", default="5m")
    parser.add_argument("--date-from", required=True)
    parser.add_argument("--date-to", required=True)
    parser.add_argument("--symbols", nargs="+", required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    for symbol in args.symbols:
        result = compare(args.data_root, symbol, args.timeframe, args.date_from, args.date_to)
        print(result)


if __name__ == "__main__":
    main()
