# Futures data pipeline: final audit (2026-10-05)

This document supersedes the coverage and spec claims in the other `2026-10-05-*` futures docs where they disagree (see
"Corrections" below). The data and the process that produced it live in `~/Desktop/qkt/qkt-futures-data/`.

## The process (repeatable)

```
make setup     # venv + pyyaml/pyarrow
make fetch     # Yahoo (registry-driven windows), axb0306 GitHub mirror, Kaggle per-contract sets
make build     # rebuilds build/qkt-data from raw/ (~5 min): CME, CME_CONT, CME_INTRADAY, CME_ADJ, ticks, catalogs, rolls, instruments.yaml, config
make audit     # reports/coverage.{json,md}: every session 2018->now, per symbol, per data type
make promote   # backs up owned paths, rsyncs into ~/.qkt/data, writes ~/.qkt/qkt-futures.config.yaml
make verify    # 78 real qkt backtests across every tier x symbol x granularity -> reports/verify.{json,md}
```

Everything is generated from `config/symbols.yaml` (multiplier, tick, fee, margin, roll policy, sources, per-source
fixes). A new symbol, a new year or a refreshed margin snapshot is an edit there followed by `make all`.
Futures backtests run with `--config ~/.qkt/qkt-futures.config.yaml --allow-incomplete` (+ `--bars` for bar tiers).

## Tiers in `~/.qkt/data`

| Tier | What it is | Span | Use |
|---|---|---|---|
| `CME:<ROOT>@front`, `CME:<CONTRACT>` | Real per-expiry daily bars + contract catalog + roll history (7d before expiry, 00:00 UTC) | 2000-2022 (NG from 2018); ES NQ CL NG GC JY EC | Roll-aware backtests with roll costs |
| `CME_CONT:<SYM>` | Unadjusted front-month daily (Yahoo), closed sessions only, NY trade date | 2018-01-02 -> last closed session; all 10 | Long-horizon daily research |
| `CME_INTRADAY:<SYM>` | 1h/4h (Oct 2024->now), 1m-30m (Jan-Apr 2026 + last 30-60 days), 1-second trade prints Mar-Apr 2026 | recent windows | Intraday / tick-replay research |
| `CME_SAMPLED:<ES/NQ/RTY>` | 1m->4h built from 3-second ThinkorSwim last-price snapshots (Kaggle brtnsmth/intraday-market-data, CC0) | 2020-01-27 -> 2026-10-02, 1641-1643 of 1686 sessions | Multi-year intraday research; wicks < 3 s missed |
| `CME_ADJ:NQ` | Back-adjusted 1m, aggregated to 5m-4h | 2022-12-27 -> 2025-12-12 | Signal research only (levels shifted by roll gaps) |

## Coverage (share of real CME sessions 2018-01-02 -> 2026-10-02)

| Symbol | Per-contract + rolls | Daily | 1h/4h | 5m-30m | 1m | Ticks | Options |
|---|---|---|---|---|---|---|---|
| ES, NQ | 56.8% | 100% | 22.7% | 4.6% | 3.6% | 1.2% | 0% |
| RTY | none (no free source) | 100% | 22.7% | 4.6% | 3.6% | 1.2% | 0% |
| CL | 56.0% | 100% | 22.7% | 4.6% | 3.6% | 1.2% | 0% |
| NG | 56.1% (from 2018) | 100% | 22.4% | 3.8% | 2.9% | 0.6% | 0% |
| GC | 57.1% | 100% | 22.7% | 4.5% | 3.6% | 1.2% | 0% |
| SI | none | 99.95% (2018-01-29 unfillable) | 22.8% | 4.6% | 3.6% | 1.3% | 0% |
| ZN | none | 100% | 22.7% | 3.5% | 2.6% | 1.2% | 0% |
| JY, EC | 56.8% | 100% | 22.7% | 3.1% | 2.1% | 1.2% | 0% |

NQ also has `CME_ADJ:NQ` at 33.9% (748 sessions, median in-session completeness 100%).
Exact uncovered windows per symbol and type: `qkt-futures-data/reports/coverage.md`.

Gap handling on the daily tier: holidays classified against the US market calendar; real abbreviated sessions
(2018-12-05, 2021-04-02, 2023-04-07) filled only where a second source matched Yahoo within 10 ticks on both neighbour
sessions with no rival contract. Illiquid-contract days (Yahoo following a thin near month) are flagged, not altered:
ZN ~18%, SI ~7%, GC ~2.2%.

## Update: CME_SAMPLED (added later on 2026-10-05)

A CC0 Kaggle capture of ThinkorSwim prices every 3 s (ES, NQ, RTY, Feb 2020 -> now, 343 weekly files, 48.9M
snapshots) closes most of the ES/NQ/RTY intraday gap. Cleaning and measured error vs exchange bars are in
`qkt-futures-data/README.md` and `reports/sampled.json`: 1m close exact 58% / 40% / 68% (ES/NQ/RTY vs TopstepX),
p95 2 / 12 / 4 ticks; daily high/low within 1 tick median for ES and RTY. Missing: 43-45 sessions when the capture
was down (listed), plus a 65-hour ES feed freeze in March 2020 that the stale rule removed. No equivalent free
source exists for CL, NG, GC, SI, ZN, JY, EC, or for any symbol in 2018-2019.

## Verification

`make verify`, final run 2026-10-05 18:36 UTC: **78/78 passed** (90/90 after adding CME_SAMPLED, same day) (EMA 9/21 cross through the compiled qkt CLI on the
live data root). Every run traded, charged commission, and roll-aware runs booked roll costs, e.g.
`CME:ES@front` 2019-2022: 26 trades, commission 57.98, roll costs 53.52. The tick tier replays 1-second prints into
1m candles without `--bars`. Full table: `qkt-futures-data/reports/verify.md`.

## Corrections to earlier 2026-10-05 docs

- EC per-contract data covers **2000-2022**, not "2018-2022" (that was a file-name sort artifact).
- ZN multiplier is **1000** ($100k face quoted in points), not 100000.
- JY has **92** contracts with exact-decimal catalog/roll prices (previously rounded to 4 dp = 100 ticks).
- Kaggle CME FX 2008 volume is ~50x under-reported; the chain filter now also keeps contracts with peak OI >= 50% of
  neighbours, which restored ECM08-ECM09 and JYU08 and the 2008 rolls.
- The intraday tier is not axb-only: 1h/4h and the recent 1m-30m windows come from Yahoo; vendors are never spliced.
- The global `~/.qkt/qkt.config.yaml` was removed (it changed CFD runs to a $500k balance); futures use the explicit
  `--config ~/.qkt/qkt-futures.config.yaml`, with max_order_notional sized from data (qkt's 250k default silently blocks NQ).
- qkt anchors panama at the oldest contract: CL goes negative across 2020 and qkt refuses ratio streams, so
  `CME:CL@front` is signal-only (qkt#1355); trade explicit CL contracts or `CME_CONT:CL`. NG's chain starts 2018 (min +0.236).

## What free data cannot provide

| Gap | Paid fix | Plugs in as |
|---|---|---|
| Multi-year ticks + top-of-book quotes | Databento (trades/MBP-1, pay per GB) | new fetcher -> `symbols/<SYM>` tick store |
| Per-contract daily 2023+ and RTY/SI/ZN chains | Databento definition + OHLCV-1d per contract, or Norgate | `build_contracts.py` source |
| 1m-30m outside the recent windows | Databento OHLCV-1m | `build_intraday.py` source |
| Options (chains, greeks inputs) | Databento OPRA/CME options or CBOE DataShop | not yet modelled |

Until then, the honest claim is: daily research from 2018 and roll-aware research 2018-2022 are real and complete;
intraday and tick research is limited to the recent windows above; options backtests are not possible on this data.
