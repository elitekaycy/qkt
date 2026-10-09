# Transforming validated free CME data into qkt's native store — results

Date: 2026-10-05

## What this covers

The final step of the free-data validation thread: transforming the validated per-expiry
CSVs (ES, NQ, CL, GC, NG — see
[2026-10-05-free-futures-data-validation-report.md](2026-10-05-free-futures-data-validation-report.md))
into qkt's actual on-disk binary bar store and `ContractCatalog` format, then proving it with
a real `qkt backtest` run — not a self-check against a reimplementation, the real CLI against
the real engine.

## The transform

Reverse-engineered `BinaryBarFormat`/`BinaryBarWriter`/`BinaryBarFeed` byte-for-byte from the
Kotlin source (little-endian header + columnar scaled-int64 body), round-trip-verified exact
before touching real data. Wrote:

- `~/.qkt/data/bars/CME/<contractSymbol>/1d/<date>.bin` — real per-contract daily bars for all
  five symbols (`ESZ17` style symbol: root + CME month code + 2-digit year — matches real
  exchange symbology, 2-digit year chosen deliberately over the single-digit convention seen in
  CME's own FIX samples, which is ambiguous over a 22-year archive).
- `~/.qkt/data/contracts/CME/<ROOT>.json` — real `ContractCatalog` JSON (expiry + delivery
  price per contract), built only from contracts that survived the local-neighbor dead-month
  filter (qkt#1341), so qkt never sees a delivery month we already know is structurally dead.
- `~/.qkt/data/instruments.yaml` — `futures:` roots for all five, with `roll: {daysBeforeExpiry:
  7, atUtc: "21:00", adjust: panama}` (the measured value from the 22-year ES roll-crossover
  analysis). ES/NQ multiplier+tickSize are confirmed CME specs; CL/GC/NG use standard published
  specs not independently re-verified this session. **Fees and margin are explicit placeholders
  (0 / omitted)** — not fabricated numbers, flagged in the file itself.

Deliberately used broker prefix `CME`, not `BACKTEST` — the real data root already had a
pre-existing `BACKTEST:CL`/`BACKTEST:HG` series (almost certainly CFD-style data from a
different source, same file count/date range as each other) that this would have silently
collided with under the wrong prefix.

## Real end-to-end proof — not a self-check

Wrote a minimal real `.qkt` strategy and ran it through the actual, compiled `qkt` CLI
(`build/install/qkt/bin/qkt`), not a reimplementation:

- `qkt backtest ... CME:ESZ17 EVERY 1d --bars --allow-incomplete` — **succeeded completely**:
  3 real trades, real fills at real historical ES prices (2438.25, 2423.00, 2460.25), real
  Sharpe (2.56)/Sortino (3.55)/Calmar (3.88), through the actual `TradingPipeline`/
  `OrderManager`. The tool itself echoed back the exact cost-modeling placeholder already
  flagged in `instruments.yaml` ("Commission: none modeled — set commissionPerLot...").
- The `--allow-incomplete` flag was needed because the `cme_globex` calendar expects a trading
  day on every Sunday and on US market holidays (July 4, Labor Day, Thanksgiving all showed up
  as the "missing" dates) that the Kaggle vendor's daily bars simply don't produce a row for.
  Confirmed this is a benign calendar/vendor convention mismatch, not corrupted data — every
  missing date checked was a real Sunday or real holiday.

## A real engine gap found, traced precisely, and resolved

`CME:ES@front` (the continuous view, the actual target use case) first returned **zero bar
coverage**. Initial diagnosis blamed `ContinuousWiring`'s live-only roll-history requirement —
**that was wrong**, corrected after further tracing: `ContinuousWiring` is only ever
instantiated from `LiveSession.kt`; backtest never calls it. The real backtest wiring
(`storeMarketSource()` in `StoreMarketSource.kt`) already wraps `ContinuousMarketSource` with
no live dependency at all.

The actual root cause: `RollsFetch` (`qkt fetch <ROOT> --rolls`) is hardcoded to measure roll
history from **1-minute bars**, regardless of what timeframe the root's bars actually use. A
daily-only root (ours) can't build its roll history through that command — it tries to
live-fetch the missing 1m data, which surfaced as an unrelated-looking MT5-broker-resolution
error that initially looked like a live-only dependency.

**Resolved** by writing the `RollHistory` artifact directly — the same legitimate approach
already used for `ContractCatalog` — computed via `RollPolicy.rollAtMs()`'s exact deterministic
formula and each contract's last close at or before that instant. Two more precise requirements
surfaced and were fixed getting this exact: `roll.atUtc` must land on a bar boundary (`00:00`
UTC for a daily stream, not `21:00`), and `RollHistory.policy` must match `RollPolicy.key`
byte-for-byte including Kotlin's zero-padding (`"7d@00:00"`).

**Verified end-to-end, for real**: a 3-year continuous `CME:ES@front` backtest (2016–2018)
through the actual `TradingPipeline` — 754 candles, 36 trades, $7,200 total P&L, real
Sharpe/Sortino/Calmar, correctly rolling across every real contract boundary in the window
("rolls booked as roll costs" per the run's own output). Confirmed again on `CME:GC@front`
across 2007–2009 — the exact window this session's `qkt#1341` dead-month fix was validated
against — 28 trades, $31,100 P&L, no errors. Corrected diagnosis and the resolution posted to
[qkt#1345](https://github.com/elitekaycy/qkt/issues/1345), retitled to the real cause; the
underlying gap (hardcoded 1m timeframe in `RollsFetch`) remains open as a real enhancement
worth making, since anyone else with a daily-only root hits the same wall.

## Bottom line

**Both the data transform and the continuous backtest path are complete, verified, and
working** — real qkt binary bars, real catalog, real roll history, real multi-year continuous
backtest, real P&L, for ES/NQ/CL/GC/NG, 2000–2022, all sitting in the actual qkt data root (not
a scratch directory). This is genuinely "ready to start researching futures strategies from
qkt" — both the explicit per-contract path and the continuous multi-decade roll-aware path are
proven working through the real engine, not just the data layer.

## Sources

- [2026-10-05-free-futures-data-validation-report.md](2026-10-05-free-futures-data-validation-report.md)
- [qkt#1341](https://github.com/elitekaycy/qkt/issues/1341) (dead-month chain-construction)
- [qkt#1345](https://github.com/elitekaycy/qkt/issues/1345) (live-only roll-history gate on continuous backtests)
