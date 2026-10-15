# `qkt data import`: any market-data file into qkt's store, exactly

Status: approved 2026-10-14, implementing on `feat/data-import`.

## Problem

Users arrive with data in dozens of shapes: MT5 exports, Dukascopy CSVs, HistData zips, TradingView
exports, Binance archives, exchange dumps, their own spreadsheets. qkt reads exactly one tick CSV
and one bar CSV, and `qkt data convert` only re-encodes qkt's own CSV as `.bin`. Getting outside
data in means hand-writing qkt's bytes, and the mistakes that produces are silent: a broker clock
taken for UTC moves every bar by two or three hours, a bid series taken for mid shifts every fill
by half a spread, a COT value stamped on its report date leaks three days of the future.

Data is the input to every backtest and every live decision. A wrong import does not fail, it
produces a plausible strategy that is wrong. The importer's job is therefore stricter than
"convert": it must convert exactly, prove what it can, refuse what it cannot prove, and record
what it did.

## Goals

1. One command turns a file from any common source into qkt's store with no flags when the
   source is known, and with the minimum of flags when it is not.
2. **One generic engine; sources are data.** A source is a small YAML descriptor (a preset), never
   a Kotlin class. Users write their own with `--plan`.
3. **Exact and deterministic.** Decimals never pass through floating point; the same input and
   descriptor produce byte-identical store files on every run and machine.
4. **Refuse rather than guess** whenever a decision changes a timestamp, a price or a volume's
   meaning and the evidence does not settle it.
5. Output is exactly what qkt already reads, so every existing reader, backtest and tool works on
   imported data unchanged.
6. Every import leaves provenance; every imported series can be measured against a venue's series
   (`qkt data compare`).

## Non-goals (this spec)

- Binary vendor formats (Dukascopy `.bi5`, Parquet, Databento DBN, Sierra `.scid`, MT4 `.hst`):
  they need an export to CSV first, and the refusal says so. Decoders can be added later behind
  the same pipeline, since a decoder only produces rows.
- Option chains, marks, trade tape, order-book depth, contract catalogs: same mechanism, later
  presets and writers.
- US equity options (the engine trades only Deribit linear options), corporate actions (no model).
- A spread column in bars (a separate, opt-in core change; see "Follow-ups").

## Commands

```
qkt data import <file|dir>... --as <SYMBOL> [--kind ticks|bars|funding|open-interest|series]
    [--preset <name|file.yaml>] [--map <field>=<ref>,...] [--time-format <pattern|epoch|iso>]
    [--tz <UTC|+02:00|Area/City|ny-close>] [--tf <tf>] [--price bid|ask|mid|last] [--label open|close]
    [--available <ref>|--available-lag <dur>] [--digits <n>] [--decimal ,|.] [--delimiter <c>]
    [--on-overlap refuse|replace|skip] [--sort] [--dry-run] [--plan] [--json] [--data-root <dir>]
qkt data compare <SYMBOL> --against <SYMBOL> [--tf <tf>] [--from <date>] [--to <date>] [--json]
qkt data presets [--json]
```

- `import` validates everything before it writes anything. `--dry-run` stops after the report.
- `--plan` prints the fully resolved descriptor as YAML (preset plus flags plus inference) and
  writes nothing; saved and passed back with `--preset my.yaml` it reproduces the import exactly.
- `--json` replaces the human report with one JSON document (for the bot CLI, the studio, agents).
- Exit codes: 0 written (or dry run clean), 1 refused or invalid data, 2 bad arguments.

## Data kinds and where they land

| Kind | Target (unchanged qkt formats) | Symbol rule |
|---|---|---|
| `ticks` | `symbols/<BARE>/<day>.csv.gz`, header `timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`, plus `symbols/<BARE>/manifest.json` (half-open day ranges) under `ManifestLock` | bare or prefixed; the tick store is keyed by the bare name |
| `bars` | `bars/<SRC>/<SYM>/<canonical tf>/<day>.csv`, header `timestamp,open,high,low,close,volume`, plus the bars manifest via `LocalBarStore.recordDay` | `SRC:SYM` required (the CSV bar store is read only for prefixed symbols); `BACKTEST` refused (it names the `build-bars` tier) |
| `funding` | `funding/<VENUE>/<NAME>.csv` (`time,rate,price`) via `FundingRateStore.merge` | `VENUE:NAME`, file-safe |
| `open-interest` | `open_interest/<VENUE>/<NAME>.csv` (`time,open_interest`) via `OpenInterestStore.merge` | `VENUE:NAME`, file-safe |
| `series` (COT, macro, any published-later number) | `bars/<SRC>/<NAME>/1d/<day>.csv`, open=high=low=close=value, volume 0, in the bar of the first UTC day that starts at or after the value became available | `SRC:NAME`; availability required |

Days inside the imported span that have no rows get a header-only file (bars) or are left absent
and reported (ticks), matching how `qkt fetch` records closed days: a backtest only uses the CSV bar
store when every calendar day of its window has a file, so an import that skipped weekends would be
silently ignored.

Ticks: a quote row keeps `bid`/`ask` and leaves `price` blank (qkt derives the mid); a trade row
keeps `price` and leaves `bid`/`ask` blank. `symbol` holds the bare name. Rows are grouped by the
UTC day of their own timestamp, so no row ever sits in another day's file.

## The pipeline

```
open → decode → structure → descriptor → typed parse → clock → normalise → validate → write → verify → report
```

1. **Open** (`ImportSources`). Files, directories (walked, sorted by name) and archives detected by
   magic bytes, not extension: gzip `1f8b`, zip `PK\3\4` (every member that is not a known non-data
   member: `.txt` reports, `MANIFEST.json`, `__MACOSX/`, `.CHECKSUM`). Binary formats are refused
   naming the export to use (`.bi5`, `PAR1` Parquet, `.hst`, `.scid`, `DBN`).
2. **Decode** (`TextDecoding`). BOM first (UTF-8, UTF-16LE, UTF-16BE); BOM-less UTF-16 by NUL bytes
   in alternate positions; else strict UTF-8, else refuse unless `--encoding latin-1`. CR and CRLF
   normalised, a trailing `\x1a` dropped. A file whose first non-blank characters are `<` (an HTML
   page saved as `.csv`, e.g. a login or anti-bot page) is refused.
3. **Structure** (`CsvDialectSniffer`, `JsonRows`). JSON is an array, NDJSON or an object holding
   an array (`rows:` path). CSV dialect: every candidate delimiter (`,` `;` tab `|`) and quote
   (`"` or none) is scored on the first 2,000 lines as *row consistency × cell typeability* (the
   fraction of cells that parse as a number or a timestamp). The winner must be unique; a tie or a
   score under 0.9 refuses and names `--delimiter`. Whole-line quoting (`"a,b,c"`) is unwrapped;
   trailing empty columns that are empty in every row are dropped; header rows repeated mid-file
   (concatenated exports) are skipped and counted; a banner line before the header is skipped when
   it has a different field count from the body.
4. **Descriptor** (`ImportDescriptor`). Everything the engine needs to know about meaning: kind,
   time columns and format, clock, timeframe, label, price basis, field references, scales,
   digits, availability. Resolved in this order, later wins: matched built-in preset, `--preset`,
   flags. A preset matches by header tokens (exact, case- and whitespace-insensitive) or, for
   headerless files, by column count plus a per-column shape test plus an optional filename
   pattern. A file matching two presets equally refuses and lists them. With no preset, inference
   proposes the time column (a column every row parses as a time) and roles from a header
   dictionary (`open/o/Open/<OPEN>`, `bid/Bid/bidPrice/best_bid_price`, ...), then refuses for any
   meaning it cannot prove (clock for naive times, basis for a lone price column).
5. **Typed parse** (`DecimalText`, `TimeText`). Numbers: optional sign, digits, one decimal
   separator (`.` or `,` as declared or proven by column-wide evidence: 4+ digits after a comma, or
   a `;` file whose commas never form 3-digit groups; `1,234` alone is ambiguous and refuses),
   optional exponent (`3.146e-05`), converted with `BigDecimal(String)` only. Null tokens (empty,
   `null`, `NaN`, `nan`, `N/A`, `-`) become absent, never zero. Times: strict `java.time` patterns
   (`uuuu`, `ResolverStyle.STRICT`), fractions of 1 to 9 digits (sub-millisecond digits truncated
   and counted), epoch numbers whose unit is decided **per file** from magnitude (s 9-10 digits,
   ms 13, µs 16, ns 19, fractional seconds allowed) and must land between 1990 and tomorrow,
   ISO-8601 with `Z` or an offset, and per-row offsets (`31.12.2012 17:00:00.000 GMT-0500`). A file
   whose epoch magnitudes span two units refuses. Day-first vs month-first patterns are settled
   by the column: any first field over 12 means day-first, any second field over 12 month-first,
   both means invalid, neither (and no sequence evidence) refuses for `--time-format`.
6. **Clock** (`ImportClock`, `WeeklyOpenEvidence`). A timestamp that carries its own zone (epoch,
   `Z`, an offset) is used as is. A naive local time needs a clock: `UTC`, a fixed offset, an IANA
   zone, or `ny-close` (New York + 7 h, the GMT+2/+3 retail broker clock, which follows **US** DST
   dates; no IANA zone matches it). Conversion: a local time inside a DST gap refuses with the
   lines; inside an overlap it takes the earlier offset until the stream's local time steps
   backwards, then the later one (a stream that is not monotonic inside the overlap refuses). For
   FX and metals symbols the importer measures each week's first timestamp after the weekend and
   expresses it in New York time. The evidence passes when that New York time is the same hour in
   every week (a wrong DST rule moves it by an hour in some weeks) and lies between 16:00 and
   19:00 (FX opens 17:00, metals 18:00); otherwise the import refuses and prints the per-week
   evidence and the clocks that would pass. Data spanning a single DST season is reported "clock
   not proven across DST". Crypto and other 24/7 symbols carry no such evidence: naive times there
   need `--tz` (or a preset that states it).
7. **Normalise** (`RecordBuilder`). Field references resolve to cells (`<OPEN>`, `$3`, `[3]` in an
   array row, `mid.o` in an object row). Expressions allow `+ - * /`, parentheses, field names,
   constants and the symbol's `point`, `digits`, evaluated in `BigDecimal` with
   `MathContext.DECIMAL64`. Bid bars become mid by adding half of each bar's own spread
   (`spread * point / 2`), rounded to scale 8 HALF_EVEN exactly as `Mt5DataClient` does for
   `qkt fetch`, so imported and fetched MT5 bars agree. Close-labelled bars move back by one
   timeframe. MT5 tick rows with a blank bid or ask carry the previous value forward (blank means
   unchanged); a row before both sides are known is rejected. A zero `LAST` or volume on a quote
   feed is absent, not zero.
8. **Validate** (`RecordChecks`). Per kind:
   - order: a file entirely newest-first is reversed; otherwise a step backwards refuses with
     the line, unless `--sort` (stable, by time then input order);
   - duplicates: identical bars dropped and counted; same open time with different values
     refuses; identical ticks are kept (repeated quotes are real), ties keep input order;
   - values: prices must be positive; bars need `low <= min(open, close)`,
     `high >= max(open, close)`; a crossed quote (`bid > ask`) is dropped and counted (qkt's
     reader drops it anyway); negative volume refuses;
   - bar grid: every open time must sit on the epoch-aligned UTC grid of the timeframe; the
     timeframe, when not given, is the dominant spacing and must be a valid qkt timeframe. A
     broker H4 or D1 bar cut at broker midnight is off the UTC grid and refuses, saying to import
     H1 or finer (qkt builds H4 and D1 itself on the UTC grid);
   - precision: digits beyond the instrument's (built-in spec or `instruments.yaml`) that are
     not zeros refuse unless `--digits`;
   - flagged, never changed: price spikes (robust z on log returns over 10 MAD), stale runs, rows
     inside the symbol's closed hours, interior gaps longer than the timeframe during session.
   Any refusal lists up to 20 offending lines with file and line number and writes nothing.
9. **Write** (`StoreWriters`). Per kind, through qkt's own writers where they exist. Each day file
   is written to a temporary file and renamed atomically. Overlap with stored days: `refuse`
   (default, lists the days), `skip` (keep stored), `replace` (stored file moved to
   `.import-backup/<timestamp>/`). A day whose stored provenance already names the same input
   digest and descriptor is reported unchanged and not rewritten (idempotent re-import).
10. **Verify** (`PostWriteVerify`). Every written file is read back with qkt's own reader
    (`CsvTickFeed`, `BarDayCsv`, the funding and open-interest stores), and the row count, first
    and last timestamp must equal what was written.
11. **Report**: format and confidence, clock and evidence, mapping, rows read, written, dropped by
    reason, flagged, days written, unchanged, skipped, empty, and the next command to run.

## Provenance and the two core fixes

Each target directory gets `.import.json` (tick symbol directory, bar timeframe directory, and the
funding or open-interest venue directory), keyed by day (bars, ticks) or by symbol. An entry holds:
input file names and SHA-256, descriptor hash, preset, clock and its evidence, price basis, volume
meaning, counts, fields seen but not stored, qkt version and import time. The file is invisible to
the readers (it is not a day file and not `manifest.json`).

Two core changes make imported data safe in backtests:

1. **No silent replacement.** `BacktestDataProvisioner` deletes and refetches a tick day it judges
   incomplete. For a day with import provenance it no longer does: the hole is reported as part of
   the imported series ("imported day 2024-07-18 has 3 empty session hours"), and the run refuses
   or proceeds under `--allow-incomplete` exactly as for any other hole.
2. **Sources are visible.** When a backtest's ticks for a symbol come from imported days and from
   fetched days, it prints `qkt: XAUUSD ticks: 180 days imported (mt5-ticks), 22 days dukascopy`.
   `qkt data verify` prints the source of each imported day.

## Existing data defects fixed in the same PR

Found while mapping the store (and added to as testing finds more):

1. `qkt fetch --tf 60m` writes a `60m/` folder that every reader looks for as `1h/`: the folder
   name becomes `canonicalSpec()`.
2. `BarDayCsv.read` never checks the header, silently skips rows with fewer than six fields, fails
   a bad number with a bare `NumberFormatException` and never checks order: it now checks the
   header, and any malformed, short or out-of-order row fails naming `file:line`.
3. The bars manifest reader rejects unknown keys (the tick manifest stopped doing so in #1376) and
   writes through a fixed temporary name without a lock: unknown keys are ignored and the write
   uses a unique temporary file under `ManifestLock`, like the tick manifest.
4. Nothing checks that a tick or bar sits in its own UTC day's file, or that time keeps rising
   from one day file to the next (each reader checks within one file only): `qkt data verify`
   flags `MISPLACED` rows and `BACKWARDS` day boundaries.
5. `BacktestDataProvisioner` silently replaces an imported tick day it judges incomplete (above).

## `qkt data compare`

Compares two stored series of one instrument, typically an import against a venue fetch of the
overlapping days (`qkt fetch MT5:XAUUSD --tf 1m --last 30d` first). Both sides are read as bars of
the given timeframe (ticks aggregate to bars with qkt's own aggregation). It reports matched,
missing and extra bars; the clock offset (the shift in whole minutes, −720 to +720, that best
aligns the closes, so a wrong clock shows as a non-zero offset); and close and high/low differences
in points (median, p99, max). Verdict: `identical` (offset 0, max difference 0), `matches`
(offset 0, p99 within 2 points), `differs`. The result is appended to the import's provenance and
shown by `qkt data verify`.

## Presets shipped

Each is a YAML file under `src/main/resources/importer/presets/`, each with a committed sample
taken from a real file (or the vendor's documented example) and a golden expected store output:

`mt5-bars`, `mt5-ticks`, `mt4-csv`, `dukascopy-csv-bars` (`Gmt time`/`Local time` with per-row
offset), `dukascopy-csv-ticks`, `dukascopy-node-bars`, `dukascopy-node-ticks`, `histdata-m1`,
`histdata-ticks`, `tradingview`, `binance-klines` (spot and futures, headerless and headed, ms and
µs), `binance-funding`, `binance-metrics-oi`, `bybit-trades`, `okx-candles`, `kraken-ohlcvt`,
`ninjatrader-bars`, `truefx-ticks`, `yfinance`, `alphavantage`, `polygon-aggs`, `oanda-candles`
(JSON), `ccxt-ohlcv` (JSON), `cftc-cot` (series), `generic` (inference only).

## Edge cases covered (each has a test)

Encoding: UTF-8 BOM, UTF-16LE with and without BOM, HTML page saved as CSV. Structure: CRLF, tab,
semicolon, pipe, whole-line quotes, trailing delimiter, banner line, header repeated mid-file,
multi-line yfinance header, zip with a report member, many files out of order. Numbers: decimal
comma, scientific notation, float noise (`981.6912000000001` kept exactly), `NaN` volume, integer
prices. Time: day-first vs month-first, glued milliseconds (`HHmmssfff`), 7- and 9-digit fractions,
epoch s/ms/µs/ns, Binance's ms-to-µs switch across files, per-row GMT offsets crossing DST, ISO with
offsets, `ny-close` across the US/EU mismatch weeks, a DST gap row, a DST overlap on a weekend-
trading symbol, newest-first files, OKX's UTC+8 file days. Bars: close-labelled bars, broker H4 off
the UTC grid, duplicate bars, OHLC violations, bars built up to coarser timeframes on read. Ticks:
blank bid/ask carried forward, crossed quotes, identical repeated ticks, a day split across files.
Safety: overlap refused, replaced with backup, idempotent re-import, provisioner leaves an imported
day alone, mixed sources reported.

## Acceptance

1. Every preset's sample imports with no flags to its golden output, byte for byte.
2. Round trip: a store produced by qkt's own Dukascopy fetch, exported to each tick and bar
   preset's shape, re-imports to byte-identical day files.
3. Independent truth: the same EURUSD days fetched by qkt from Dukascopy and downloaded separately
   (dukascopy-node CSV) compare `identical`; HistData M1 for those days compares with offset 0.
   Binance klines from `data.binance.vision` compare `identical` with `qkt fetch BINANCE_UM`.
4. Imported bars and ticks pass `qkt data verify`, and a `--no-fetch` backtest over them runs;
   1h and 4h bars qkt builds from imported 1m bars equal an independent roll-up of the raw file.
5. `./gradlew build` green (tests, ktlint, file-size, docs sync).

## Follow-ups (not in this spec)

- Optional 7th bar column `spread`, read by `candleToTicks` to give synthetic ticks a bid and ask
  (opt-in by presence; binary bars need a v2 format). Changes results only for data that has it.
- Binary decoders (`.bi5` first: qkt already depends on xz), chains, marks, tape, depth, catalogs.
- Studio drag-and-drop over `import --dry-run --json`.
