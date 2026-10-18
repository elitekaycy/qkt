# Real-data end-to-end suite — design

Date: 2026-10-18. Status: built (PR tier and full tier in CI, release proofs in the attestation, daily drift).

## Why

Most qkt users' first step is getting data, and wrong data breaks a strategy silently: a bar an hour off,
a price from the wrong ticker, a float that is not the vendor's number. Unit tests did not catch these,
because they feed the code what its author expected. The suite here runs **real vendor files** through
the **built `qkt` program**, checks every stored value against an **independent reader** or the
**vendor's own coarser data**, and runs again on every change.

The first pass over real downloads (2026-10-18) found eight defects that the unit suite passed:

| # | Defect | Source that showed it |
|---|---|---|
| 1 | A two-ticker yfinance file imported `--as YAHOO:AAPL` stored MSFT's prices (last same-named column won) | `yf.download(["AAPL","MSFT"])` |
| 2 | yfinance prices stored with float32 noise (`340.79998779` for 340.80) | every yfinance file; Alpha Vantage agrees with the recovered value on 345/400 fields, 35/400 before |
| 3 | MetaTrader presets assumed ny-close; an Exness (UTC) export without two weekly opens imported 2-3 h off with no warning | MT5 exports, Exness gateway `offset_seconds: 0` |
| 4 | `qkt data … --config <file>` refused as an unknown flag, though the data root reads it | the CLI itself |
| 5 | No preset for Binance trades / aggTrades, the main free crypto tick source | data.binance.vision |
| 6 | A Binance spot trades file and Kraken OHLCVT tie once trade ids reach 10 digits | data.binance.vision, Kraken |
| 7 | HistData monthly files refused when one Sunday opens late (its first hour missing) | HistData EURUSD 2026-03 |
| 8 | A 12-day data hole counted as a weekend when judging the clock | sparse files |

And it proved, to the field, what already worked: 1.73M Binance futures trades and 120k spot trades build
the exact 1m/5m/1h/4h/1d klines Binance publishes; Binance 1m rolled up equals Binance 5m/15m/1h/4h/1d
(spot and futures); 535k Bybit trades equal Bybit's 1m klines under Bybit's drawing convention; 2.64M
HistData ticks build HistData's own M1 bars; HistData ASCII and MetaTrader formats store byte-identically.

## Principles

1. **Real files only** in the corpus. Every file records where it came from (URL or repository and commit),
   its sha256, when it was fetched and the vendor's checksum where one is published. A hand-written
   fixture belongs in the unit tests, not here.
2. **qkt never grades itself.** Correctness checks compare qkt's store with `tests/data-e2e/lib/oracle.py`,
   readers written apart from qkt's Kotlin, with `Decimal` arithmetic, or with the vendor's own data at
   another resolution. Golden fingerprints (below) catch change; oracles catch error.
3. **Through the CLI.** Every case runs the installed `qkt` binary as a user would: `qkt data import`,
   `verify`, `bars`, `build-bars`, `convert`, `compare`, `snapshot`, `qkt backtest --dataset`. Flags,
   messages, exit codes and files on disk are all part of the contract.
4. **Deterministic.** Pinned corpus commit, fixed inputs, no network in the PR and testing tiers, each
   case in a fresh data root; every import runs twice and the second must change nothing.
5. **Vendor conventions are modelled, not tolerated.** Where a vendor draws data differently (Bybit opens
   each kline at the previous close and draws flat bars for empty minutes) the oracle states the
   convention and the comparison stays exact. A tolerance is allowed only for a named ambiguity in the
   vendor's data (Bybit spot trades sharing a millisecond in an order its kline does not use), and
   is counted and capped.

## Corpus

Raw files live in a **private** repository, `elitekaycy/qkt-data-corpus`, because qkt is public and the
files are third-party data (Binance, Bybit, OKX, HistData, Yahoo, Dukascopy, public MetaTrader exports).
qkt pins the corpus by commit in `tests/data-e2e/corpus.lock`; CI checks it out with a read-only deploy
key (`QKT_DATA_CORPUS_KEY`). Layout:

```
<vendor>/<…>/<file as downloaded>
PROVENANCE.json   one entry per file: path, sha256, bytes, source (url | repo@commit:path), fetched, vendor checksum
```

`tests/data-e2e/fetch-corpus.py` rebuilds the corpus from the recorded sources and verifies every sha256,
so a vendor that disappears or changes a file is detected, and the corpus can be audited at any time.

## Cases

`tests/data-e2e/cases.toml` lists every case: the files, the `qkt` commands, what the run must report
(preset, clock, records, days, notes, exit code, refusal text) and the oracle checks. One case per source
shape and per edge, tagged `pr` (small, every PR) or `full` (large, the testing branch).

Oracle checks:

| Check | Meaning |
|---|---|
| `ticks_equal` | every stored tick (time, price/bid/ask, volume) equals the independent read of the raw file |
| `bars_equal` | every stored bar equals the independent read |
| `vendor_bars` | `qkt data bars` at each listed timeframe equals the vendor's own bars at that timeframe (bars built from ticks, from bars, and from the `--bars` `.bin` tier) |
| `formats_identical` | two formats of one vendor's data store byte-identically |
| `compare` | `qkt data compare` between two sources reports the expected verdict and clock offset |
| `refuses` | the import exits 1 with the given text and writes nothing |
| `reimport_noop` | importing again reports 0 written and leaves every byte as it was |

CLI and store cases (no vendor oracle needed, all real files): data-root precedence (`--data-root`,
`QKT_DATA_HOME`, `data_root` relative to the config, missing absolute path refused, `--config`), `verify`
on a sound store and on a damaged copy (truncated day, deleted day, reordered rows), `build-bars`
incremental rebuild of a changed day, `convert` and `--prune` reading back identically, `snapshot` then
`backtest --dataset` refusing a changed byte, and a backtest on imported data that is byte-identical across
two runs and across the `.csv` and `.bin` tiers.

Edge catalogue (each a case): two tickers in one file, a ticker not in the file, float32 prices, Yahoo
hourly bars anchored at :30 (refused, naming the fix), HistData's late Sunday open (accepted, named),
a short MetaTrader export with no weekly open (refused, naming the broker clock), the same file with
`--tz UTC`, MetaTrader rows with empty prices, a header-only file, Binance µs and ms stamps, OKX days cut
at UTC+8, Dukascopy empty hours and a DST-change day, sub-millisecond trade stamps, values with more than
8 decimals, Bybit spot same-millisecond trades.

## Golden fingerprints

For each case `tests/data-e2e/golden/<case>.json` holds what the run produced: report fields and the
sha256 of every stored file (manifests with their `lastUpdated`/`importedAt` removed). A change to qkt that
changes a stored byte fails the suite until `run.py --update` rewrites the fingerprint, so the change is
visible in the pull request's diff and reviewed. Oracle checks still run on every case, so an updated
fingerprint can never hide a wrong value.

## Where each tier runs

The branch flow is feature → dev → testing → main (see `promote-dev-to-testing.sh`, `release-unattended.sh`).

| Tier | Runs on | What | Budget |
|---|---|---|---|
| PR | `check.yml`: every PR into dev, every push to dev/testing | `run.py --tier pr`: all `pr` cases | ≈ 3 min |
| Testing | `integration.yml`: push to testing (gates promotion to main) | `run.py --tier full`: every case, plus the external references (backtesting.py and backtrader reproduced to the cent, data and expected results in the corpus) | ≈ 15 min |
| Main | `run-attestation.sh`, new stage `release-proofs`, on the attested commit, before the promotion PR | the full data suite again, the 48-scenario audit (991 checks + 39 relations), the 12 docs examples, store compatibility (stores and state written by the previous release read by this one), and a live data check on the demo gateway (broker M1 bars equal bars built from the broker's ticks) | ≈ 40 min |
| Drift | `data-drift.yml`, daily schedule | fetch today's file from each free source and run the same oracles; a vendor format change opens an issue. Never blocks a merge | ≈ 5 min |

## Built, and what the later passes found

- **Release proofs** (`scripts/release-proof/run.sh`, stage `proofs` of `run-attestation.sh`, before the account is
  touched): corpus provenance; every data case; `tests/references` (29 cases: qkt equals backtesting.py and
  backtrader to the cent, conventions written into each case); the 48-scenario audit (991 independent bundle
  checks, 39 relations, fingerprints) on `stores/exness-2026-06` in the corpus, which reproduces the full-store
  run's trades 48/48; store compatibility (a store the previous release wrote, read by this one); and, with
  `docs_data_root`, the 12 docs examples written out from the pages and run on the local store pinned by snapshots
  (totals equal what the pages show). About 25 minutes.
- **Drift** (`.github/workflows/data-drift.yml`, `tests/data-e2e/drift/`): the newest complete day from Binance,
  Bybit, OKX, Dukascopy, Alpha Vantage and Yahoo through the same checks; a failure opens one `data-drift` issue.
- Later findings, fixed: the dataset gap policy defaulted to 30 minutes, so `backtest --dataset` refused every gold
  day for its hour-long daily break (now 90); `qkt data snapshot` had no way to pin holiday holes
  (`--allow-missing-days`); `qkt data bars --bars` did not roll up from a finer built timeframe as `backtest --bars`
  does. Vendor conventions modelled: Binance writes a flat zero-volume kline for a minute without trades; Binance
  aggTrades carry their first fill's time; Dukascopy draws flat candles for minutes without ticks.

## What else stays proven on every change

- **Release proofs on main:** today run by hand (`verify-rc2.sh`); they move into `scripts/release-proof/`
  and the attestation stage, with the same pass criteria (991/991, 29/29, 12/12 identical).
- **Store compatibility:** a store and a `--state-dir` written by the last release are read by the
  candidate without change (fleet upgrades do exactly this).
- **Determinism:** every backtest case runs twice; `result.json`, `trades.csv` and the equity files must be
  byte-identical.

## Adding a case

Download the file, add it to the corpus with `fetch-corpus.py add <url|repo@commit:path>`, add a case to
`cases.toml`, run `run.py --case <id> --update`, read the fingerprint, commit both.
