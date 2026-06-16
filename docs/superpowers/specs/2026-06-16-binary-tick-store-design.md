# Binary tick store — design

## Problem

Backtests over multi-year ranges are slow. A 3-year, 4-symbol backtest measured on the research
box took ~90 minutes at ~1 core (single-threaded), spending most of its time **decoding data, not
computing strategy logic**.

The cost is in the read path, `CsvTickFeed.parseLine`. Per tick it:

1. gzip-inflates the day file (`<date>.csv.gz`),
2. `line.split(",")` — allocates an 8-element `List<String>`,
3. parses up to six `BigDecimal(String)` and calls `.setScale(8, HALF_EVEN)` on each,
4. computes `finalPrice` (the bid/ask midpoint) via `BigDecimal` add/divide/setScale,
5. allocates a `Tick` holding seven nullable `BigDecimal` objects.

For ~80k ticks/symbol/day, a 3-year × 4-symbol run is on the order of **200M rows and ~1 billion
`BigDecimal` allocations**, redone from scratch on every backtest. The work is allocation- and
parse-bound (gzip + string-split + `BigDecimal(String)`), which is why the CPU sits near one core's
worth of churn rather than doing useful compute.

## Goal

Make a single backtest's data read **~10–20× faster** by changing only *how a `Tick` is
materialized from disk*, not *what a `Tick` is* or *what the engine does with it*.

### Non-goal (hard constraint): backtest=live parity is untouched

qkt's core invariant is bit-identical backtest=live replay. This design preserves it **by
construction**: the new feed produces the exact same `Tick` sequence — byte-for-byte equal
`BigDecimal` fields, same validation, same monotonic-timestamp checks — as `CsvTickFeed` produces
today. We change the decode, never the arithmetic the engine performs. A regression test asserts
the two feeds yield identical `Tick` streams, so parity is mechanically enforced, not assumed.

The more aggressive option — a fixed-point `long` pipeline through indicators/brokers/PnL for
~50–100× — is explicitly **out of scope**: it would change the engine's arithmetic and force a
re-proof of parity across the whole engine. We do the safe win first and let a post-conversion
profile decide whether anything more is even needed.

## Why a columnar binary file, not a time-series database

A backtest is a pure **sequential scan** of ticks in time order, not an ad-hoc query workload. A
server database (e.g. TimescaleDB) optimizes queries and adds an operational service plus a
network/IPC hop per scan — overhead with no upside for a full in-order replay. Columnar **files**
give the same scan speed with zero infrastructure and stay true to qkt's minimal-dependency,
deterministic, mock-first design.

If the research/dashboard side later wants ad-hoc SQL, that is a separate concern served by
exporting Parquet and running DuckDB over it — the engine's replay path stays a plain file read and
never depends on a database.

## The bit-identical mapping

Dukascopy day files write bid/ask/volumes at exactly 8 decimal places (e.g. `1712.00200000`).
`CsvTickFeed` parses these to `BigDecimal` at `Money.SCALE = 8`. A value at scale 8 is exactly its
**unscaled integer** (`value × 10^8`), which fits `int64` with room to spare:

- gold `2000.00000000` → `200000000000` (2×10¹¹); BTC `100000.00000000` → `10^13`; `int64` max ≈ 9.2×10¹⁸.

Reconstruction is `BigDecimal.valueOf(unscaledLong, 8)`, which yields a `BigDecimal` with unscaled
value `unscaledLong` and scale 8 — equal (`.equals`, scale included) to the `BigDecimal(String)`
the CSV path produces, because the source already has exactly 8 fractional digits so `setScale(8)`
is a no-op. `finalPrice` is then computed by the *same* midpoint logic the CSV path uses, so even
the rounding matches.

Absent columns (dukascopy leaves `price` and `volume` blank) are encoded with a `NULL` sentinel
(`Long.MIN_VALUE`, which no scaled price/volume can reach). This keeps the format general for
sources that populate different columns, and a column that is all-null compresses to almost nothing.

## Components

Each unit has one job, a clear interface, and is testable in isolation.

### 1. `TickAssembler` (extracted shared factory)

The validation + `finalPrice` logic currently inside `CsvTickFeed.parseLine` (the part after the
six values are in hand) moves into a single function:

```
assemble(symbol, timestamp, price?, volume?, bid?, ask?, bidVolume?, askVolume?, location): Tick
```

It performs the existing checks (price OR (bid AND ask); `bid <= ask`; non-negative fields) and
derives `finalPrice`. Both feeds call it, so they cannot drift — this is the DRY guarantee behind
parity. `CsvTickFeed` keeps owning string→`BigDecimal` parsing; it just delegates assembly.

### 2. Binary format `qkt-tick-bin-v1`

One file per symbol-day, `symbols/<SYM>/<date>.bin`, sibling to `<date>.csv.gz`. Layout:

- **Header:** magic (`QKT1`), format version, symbol (length-prefixed UTF-8), scale (`8`),
  `tickCount`, a column-presence flag word (which of the six value columns are stored), and the
  body codec id.
- **Body (columnar):** for the timestamp and each present value column, a contiguous block of
  `tickCount` `int64`s (little-endian), `NULL` sentinel for absent cells. Columnar (not row-major)
  so each column compresses well and only present columns occupy space.
- **Codec:** the body is compressed with a fast codec (LZ4 or Zstd) — decode throughput is far above
  gzip, and columnar `int64` (optionally delta-encoded monotonic timestamps) compresses near the
  current `.csv.gz` size. Raw-mmap vs LZ4 vs delta+Zstd is chosen by the benchmark in component 6;
  the header records which, so the reader stays format-driven.

### 3. `BinaryTickFeed` (implements `TickFeed`)

Opens a `.bin`, reads the header, then iterates columns in lockstep, reconstructing each `Tick` via
`BigDecimal.valueOf(unscaled, scale)` (NULL sentinel → `null`) and `TickAssembler.assemble`. It
keeps `CsvTickFeed`'s monotonic-timestamp check and fail-loud behavior on malformed data.

### 4. `BinaryTickWriter`

Writes a `Tick` (or raw column) sequence to a `.bin` atomically (temp file + atomic move, matching
the existing store's write discipline). Used by the converter now and by `qkt fetch` later so newly
fetched data lands as `.bin` directly.

### 5. Converter — `qkt convert-cache`

A one-time, idempotent CLI command: for the given symbols/range, read each `.csv.gz` via
`CsvTickFeed` and write the `.bin` via `BinaryTickWriter`. Skips days already converted. Leaves the
`.csv.gz` in place by default (`--prune` to delete after a verified write) so conversion is
reversible. Run once on the research box's cache.

### 6. `DataStore.openFeed` prefers `.bin`

`DefaultDataStore.dayFile` gains `.bin` precedence: `.bin` → `.csv.gz` → `.csv`. No other store
logic changes (manifests still key on day presence; a `.bin` counts as present). Migration is
gradual and non-breaking: unconverted days transparently fall back to CSV.

## Data flow

```
backtest -> DataStore.openFeed(request)
          -> per symbol: ConcatenatedTickFeed of per-day feeds
               day has .bin?  -> BinaryTickFeed  (fast: mmap/typed columns -> valueOf -> assemble)
               else .csv.gz   -> CsvTickFeed     (unchanged fallback)
          -> MergingTickFeed (k-way by timestamp) -> RangeClippedTickFeed -> engine
```

The engine downstream of `openFeed` is untouched; it receives identical `Tick`s.

## Error handling

- Corrupt/short `.bin` (bad magic, truncated body, count mismatch): fail loud with the file path —
  same philosophy as the CSV path. A backtest must never silently run on partial data.
- Unknown format version or codec id in the header: hard error naming the file and the unsupported
  value.
- Non-monotonic timestamps inside a `.bin`: same `check` as `CsvTickFeed`.

## Testing

- **Parity (the core guarantee):** over a real converted day and a synthetic edge-case day
  (all-null price/volume, single tick, empty/header-only, max-magnitude prices), assert
  `CsvTickFeed` and `BinaryTickFeed` yield identical `Tick` sequences (`Tick.equals`, which compares
  every `BigDecimal` field including scale).
- **Round-trip:** `BinaryTickWriter` then `BinaryTickFeed` reproduces the input ticks exactly.
- **End-to-end parity:** a backtest over a fixed range produces identical metrics with `.bin` present
  vs `.csv.gz` only — the ultimate proof the engine sees no difference.
- **Converter:** idempotent (second run is a no-op); `--prune` removes CSV only after a verified
  `.bin`; fallback works when only some days are converted.
- **Benchmark harness:** measures decode throughput (ticks/sec) of `CsvTickFeed` vs `BinaryTickFeed`
  on a representative day, reporting the speedup. This is the success metric and selects the codec.

## Success criteria

1. Parity test green — identical `Tick` streams and identical backtest metrics (.bin vs .csv.gz).
2. ≥10× decode throughput on a representative symbol-day (target; measured by the harness).
3. No new server/database dependency.

## Out of scope (tracked, not built here)

- **Fixed-point `long` pipeline** (~50–100×): changes engine arithmetic; revisit only if a
  post-conversion profile shows compute, not decode, is the wall — and only with its own parity
  re-proof.
- **Cross-backtest parallelism:** the multiplier on throughput, but it needs the qkt cache-write
  race fixed (read-only cache during backtest) — that is the separate `fix-concurrent-manifest-race`
  work, not this branch.
- **Bar store binary format / pre-materialized bars:** the same treatment applies to `LocalBarStore`
  and would let bar-only strategies skip tick re-aggregation, but the dominant, universal cost is the
  tick path; bars are a fast follow once this lands.
- **Parquet/DuckDB research-query layer:** separate concern; export for analysis, never a replay
  dependency.

## Risk

Low. The change is contained to the marketdata read layer plus a converter and a writer; the engine
core is untouched. The single real risk — that the new feed diverges from the CSV feed — is closed
by the parity and end-to-end tests, which fail the build on any divergence.
