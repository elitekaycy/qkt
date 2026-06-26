# Bars research tier (`--bars`) — design

Date: 2026-06-22
Status: accepted (Layer 0 detailed; Layer 1 sketched, gated). Feasibility spike passed (see Validation).
Related: `project_backtest_decode_sharing_2026_06_22`, #214 (BarTickFeed), `reference_backtest_decode_perf`

## Context

An audit of a single backtest (2-month EURUSD, on bot2) found where the time actually goes:

| | share |
|---|---|
| decode + candle-aggregation (data handling) | ~16% |
| **per-tick order/bracket trigger evaluation** (`OrderManager.evaluateTriggers`) | **~84%** |

The bottleneck is **not** data loading — it's checking every open bracket's stop/take-profit against
*every tick*, over tens of millions of ticks. So an in-memory decoded-tick cache (the obvious idea)
addresses only ~16% (~1.2x); it's the wrong lever. The only **drastic** lever is **fewer ticks =
bars**: replaying ~4 synthetic ticks/bar instead of hundreds of real ticks/bar slashes both the 84%
*and* the 16% by ~the bar/tick ratio.

Bars approximate intrabar fills (the O→L→H→C synthesis can't know the true intrabar path), so they are
a **research / exploration tier**, not a grading tier. The decision (confirmed with the user): a
**two-tier** workflow — iterate fast on bars, **grade survivors on ticks** (the existing tick path +
the scenario fan-out from PR #512). This spec is the bar tier (**Layer 0**). A long-lived warm runner
(**Layer 1**) is sketched and gated on later need.

Strategies are **multi-market** (several symbols) and **multi-timeframe** (a symbol declared at 15m
*and* 1h); the design handles both.

## Validation (feasibility spike — passed before committing)

A throwaway spike confirmed the load-bearing assumptions on synthetic data through the existing
`Backtest` path (a "bar backtest" = feeding the bars' synthetic ticks, exactly what `BarTickFeed`
produces):

- **Multi-timeframe rollup is exact** — 15m bars → synthetic ticks → aggregate to 1h yields
  *byte-identical* OHLC to direct ticks → 1h. So a symbol's coarser declared timeframes can be derived
  from one stored finest-tf series with zero error.
- **Deterministic** — identical bars → identical trade list across runs.
- **~16.5× faster** measured (432,000 tick events → 1,920 bar events, 23.8s → 1.4s), diluted by a
  per-run DSL-recompile artifact; pure event-processing ratio ~50×. On real data this puts a 56s
  2-month run into the low seconds and a 2yr run into single-digit seconds.

## Goals

- `qkt backtest|sweep|walkforward --bars` replays pre-built bars instead of ticks: ~tens-of-× faster,
  for research/iteration on multi-market + multi-timeframe strategies.
- A one-time `qkt data build-bars` that turns the existing `.bin` tick store into a fast bar store
  (decode-once), so forge's tick-only data is usable by `--bars` without re-fetching.
- **Fastest** load path: a binary, mmap-able bar store (no string parsing), shared across processes
  via the OS page cache.
- **Seamless** timeframe resolution: declare any tf; the engine uses exact stored bars, else aggregates
  up from a finer stored tf — automatically.
- Grading stays tick-faithful; `--bars` is clearly a lower-fidelity research tier.

## Non-goals

- Bar-faithful grading. `--bars` results are not byte-identical to ticks (intrabar fills approximated);
  survivors grade on ticks. This is by design, not a defect.
- Layer 1 (the long-lived warm runner) — sketched, built later only if forge's bar fleet is proven
  startup-bound.
- Fetching bars from broker APIs for `BACKTEST:` (that's `qkt fetch`, a separate live-venue path).

## Architecture (Layer 0)

### 1. Binary bar store (`BinaryBarFormat` + `BinaryBarFeed`)

Bars get the same binary treatment as ticks (reusing the `BinaryTickFormat` columnar pattern + the
mmap reader from `project_backtest_decode_sharing`), because CSV parsing (~1–3s for a 2yr-1min run) is
a large fraction of an otherwise sub-second bar run.

- **Layout:** `bars/<broker>/<symbol>/<tf>/<YYYY-MM-DD>.bin`, parallel to the tick `symbols/` tree,
  under the same data root. A `manifest.json` per `(broker,symbol)` tracks covered day-ranges per tf.
- **`BinaryBarFormat`:** little-endian header (magic `QKB1`, version, scale = `Money.SCALE` = 8,
  timeframe-ms, symbol, barCount) then a columnar `int64` body —
  `[startTs][open][high][low][close][volume]`, each value a scaled long (`BigDecimal.valueOf(v, 8)`).
- **`BinaryBarFeed(path)`:** mmap-reads (like `BinaryTickFeed`), reconstructs each `Candle(symbol,
  o, h, l, c, vol, startTs, startTs + tfMs)`. Zero per-process `byte[]` copy; shared across processes
  via the page cache (mmap, with the same deterministic `close()`/unmap).
- **Sizes (XAUUSD):** 1m ≈ 37 MB / 2yr; 5m ≈ 7.5 MB / 2yr. The whole bar universe (11 symbols × a few
  tfs × 4yr) is ~1–2 GB — small enough that the OS page cache holds **all of it** in RAM.

### 2. `qkt data build-bars <SYMBOL> --tf <interval> --from --to` (decode-once)

Streams the local `.bin` ticks **once** (the only time tick decode is paid), aggregates to OHLC bars
via the existing `CandleAggregator`, writes `BinaryBarFormat` day-files + updates the manifest.
Incremental (skips days already built, per manifest). Build the **finest** tf any strategy needs per
symbol; coarser tfs are derived at replay (§4). This is the missing piece — today nothing turns the
`.bin` tick store into stored bars (`qkt fetch` only pulls broker bars and refuses `BACKTEST:`).

### 3. `--bars` flag (force bar replay)

A `forceBars: Boolean`, read once from `args.flag("bars")` in `BacktestContext.build`, threaded as a
context field through `backtest()` → `Backtest.fromStore` → `fromSource` → `replayFeed`. In
`replayFeed` (`Backtest.kt:224`) the tick-preference becomes `if (!forceBars && ticksAvailable) {...}`;
the existing bar branch (`BarTickFeed(source.bars(symbol, window, range))`) becomes the forced path.
Because `sweep` and `walkforward` route through `ctx.backtest`/`scenarioEngines`, one context field
covers all three commands. `--bars` is currently unused (no collision).

### 4. Seamless per-symbol finest-tf resolution

Today `BacktestContext` derives **one** candle window from the *first* stream — wrong for multi-tf.
`--bars` instead computes `barWindows: Map<qktSymbol, TimeWindow>` = the **finest** declared tf per
symbol. `replayFeed` synthesizes each symbol at *its* finest tf; `CandleHub` aggregates up to the
symbol's coarser declared tfs (validated exact in §Validation). Multi-market: per-symbol feeds merge by
timestamp (existing `MergingTickFeed`).

`source.bars(symbol, tf, range)` resolves, in order:
1. **Exact stored binary bars at `tf`** → mmap `BinaryBarFeed` (fastest).
2. **A finer stored binary tf** → `BinaryBarFeed` + on-the-fly `CandleAggregator` rollup (cheap).
3. **Neither** → under `--bars`, **error** naming the `build-bars` to run (do *not* silently fall back
   to tick-aggregation, which re-decodes and defeats the tier).

### 5. In-memory sharing

- **Tier 0 (free, automatic):** binary bar files are mmap'd → the OS page cache holds the whole ~1–2 GB
  bar universe in RAM, zero-copy, shared across every process (docker or native). "Load once, all
  reuse" — for free, no daemon. Each run still parses bars into `Candle`s (binary → `BigDecimal.valueOf`,
  cheap).
- **Tier 1 (Layer 1, gated):** see below.

### 6. Fidelity guardrail

Under `--bars`, emit a one-line notice: "research tier — bar-approximated intrabar fills; not for
grading." Missing bars → hard error (§4.3). Document the fidelity caveat alongside the existing
`BarTickFeed` notes (divergence catalog A6/A7/A10).

## Data flow

```
qkt data build-bars XAUUSD --tf 1m --from 2021-01-01 --to 2023-01-01   (decode .bin ONCE -> binary bars)
  -> bars/BACKTEST/XAUUSD/1m/<day>.bin + manifest

qkt backtest strat.qkt --bars
  -> BacktestContext: forceBars=true; barWindows = {XAUUSD: 1m, XAGUSD: 5m, ...} (finest per symbol)
  -> replayFeed(forced): per symbol, source.bars(sym, finestTf, range)
       -> mmap BinaryBarFeed (page-cached) [or aggregate-up from a finer stored tf]
       -> BarTickFeed: 4 synthetic ticks/bar
  -> MergingTickFeed -> engine; CandleHub builds every declared tf (15m+1h) from the finest feed
  -> ~tens-of-x fewer events, no tick decode -> seconds
```

## Layer 1 (sketched, gated) — warm bar-backtest runner

Once bar runs are sub-second, the per-run JVM + container startup (~3–5s) dominates the forge fleet. A
long-lived JVM that holds parsed `Candle`s in an LRU (by `broker/symbol/tf/day`; tiny, so a huge
working set stays resident) and runs backtests as **jobs** (not `docker run --rm`) eliminates that
startup + the binary re-parse. This is the colleague's `research-api` model. Build it **only when**
profiling shows forge's bar fleet is startup-bound; it trades container isolation for throughput, so
it needs its own spec (job protocol, crash isolation, parity to a standalone run).

## Testing

- **Multi-timeframe rollup exact** (from the spike, promoted to a real test): ticks→15m→synthetic→1h
  == ticks→1h (OHLCV) — the multi-tf correctness guarantee.
- **build-bars round-trip:** `.bin` ticks → `build-bars` → `BinaryBarFeed` reads back the same OHLC the
  on-the-fly `CandleAggregator` produces; binary little-endian + null/scale correctness.
- **`--bars` resolution:** exact-tf hit uses stored bars; finer-tf hit aggregates up; missing → the
  named error (not a silent tick-aggregation).
- **Determinism:** same bars → identical trades across runs.
- **Multi-market `--bars`:** a 2-symbol strategy merges per-symbol bar feeds and runs.
- **Multi-timeframe `--bars` end-to-end:** a 15m+1h-same-symbol strategy builds both series from one
  stored 15m feed and trades.
- No mocks; real binary bars + the real engine.

## Out of scope / deferred

- Layer 1 warm runner (own spec, gated).
- Converting `qkt fetch` (broker) CSV bars to binary — orthogonal; forge uses `build-bars` on ticks.
- A "ticks-per-bar" fidelity knob (e.g. 1 tick/bar close-only) — default 4 (O→L→H→C); revisit if even
  faster exploration is wanted at lower fidelity.

## References

- Audit + measurements: `project_backtest_decode_sharing_2026_06_22`.
- Existing pieces: `marketdata/source/BarTickFeed.kt` (`candleToTicks`), `candles/CandleAggregator.kt`,
  `dsl/compile/CandleHub.kt`, `backtest/Backtest.kt:224-250` (`replayFeed`), `marketdata/store/LocalBarStore.kt`,
  `cli/FetchCommand.kt` (broker bars), `cli/DataCommand.kt` (`data convert`), `marketdata/BinaryTickFormat.kt`
  + `BinaryTickFeed.kt` (the binary + mmap pattern to mirror).
- #214 bar-replay spec: `docs/superpowers/specs/2026-06-03-issue214-bar-replay-backtest-design.md`.
