# `qkt data import` plan

Spec: [`../specs/2026-10-14-data-import-design.md`](../specs/2026-10-14-data-import-design.md).

Each step is one commit on `feat/data-import` and leaves `./gradlew build` green.

1. **Engine and presets** (`marketdata/ingest`): open and decode inputs (gzip, zip, UTF-8/UTF-16),
   sniff CSV dialects, read CSV and JSON rows, parse numbers and times exactly, resolve clocks with DST
   gaps and overlaps, check weekly opens, compile mappings, build records, spill per UTC day, validate,
   write through qkt's stores, read back, record provenance. Built-in presets as YAML, each with a sample
   in its source's real shape (`PresetSamplesTest`).
2. **Tests** per component and end to end: safety (dry run and refusals write nothing, idempotent
   re-import, overlap policies, byte-identical output), edge cases (mixed epoch units, newest-first and
   shuffled files, overlapping tick files, close labels, broker H4 off the UTC grid, series availability),
   and the clock on a year of synthetic broker bars.
3. **Store defects found on the way**: canonical timeframe folders for `qkt fetch`, a strict bar day
   reader, locked and lenient bar manifests, `MISPLACED`/`BACKWARDS` in `qkt data verify`, and the
   provisioner never refetching over imported tick days.
4. **`qkt data compare`** and **`qkt data presets`**.
5. **Docs**: `how-to/import-data.md`, the CLI reference, the data guide, top-level help.
6. **Real-data checks** (run by hand, recorded in the PR): Binance klines, funding and open interest
   against the existing store; roll-ups from imported 1m bars against independent roll-ups; a backtest on
   imported data against the same backtest on the existing store; Bybit and OKX archives reconciled
   against their raw rows; scale (a year of 1m bars, 1.4M trades).
