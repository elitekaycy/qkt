# Faster backtests via decode-sharing — design

Date: 2026-06-22
Status: accepted (Phase 1; Phase 2 daemon gated on measurement)
Related: `reference_backtest_decode_perf`, `project_sweep_fanout_2026_06_17`, #214 (bar-replay, parked here)

## Context

A backtest replays historical **ticks** (individual price updates) through a strategy.
Forex tick data is large: 2-3 years is on the order of 50-150M ticks per symbol. Before the
engine can use a tick it must be **decoded** — unpacked from the on-disk binary form into a
`BigDecimal`-bearing `Tick` object. Prior JFR profiling (see `reference_backtest_decode_perf`,
`project_sweep_fanout_2026_06_17`) established that after the binary tick store and the
hot-path fixes, the remaining dominant cost is *genuine work*: `BinaryTickFeed` decode
(bytes→`long`→`BigDecimal`), `BigDecimal` compares, and `OrderManager` triggers.

The decode is paid **on every run and thrown away when the run ends**. An audit (2026-06-22)
found this redundancy is structural and nested:

- **Within one forge gate, one candidate.** G3 (validation) runs the same validation slice
  through N separate `qkt backtest` invocations (one per carried config, `carry_configs`
  default 3). G6 (robustness) runs the full dev range through ~16 separate invocations
  (3 cost-stress × commission + 13 risk-fit sizing/balance scenarios). Each invocation is a
  fresh `docker run --rm` = a cold JVM that re-decodes the same data from scratch.
- **Across candidates.** 176/213 strategies share the same 2021-2023 dev range; every G1
  smoke run (192 of them) re-decodes overlapping data.
- **Across containers / loop cycles.** bot2 runs ~3 concurrent gate containers plus a forever
  loop, each decoding into its own heap.

Reframe of the original ask ("load the data into memory once, every backtest reuses it"): on
bot2 the tick **file bytes are already shared in RAM** — all containers bind-mount the same
~61G `.bin` store, so the OS page cache serves reads from memory. What is *not* shared, and
what every run re-pays, is the **decode into `BigDecimal` ticks**, which cannot cross a
`docker run --rm` (separate-JVM) boundary without a shared process.

### Why not "use bars" (the obvious 100x lever), rejected

Coarse **bars** (OHLC summaries) are ~100-1000x less data than ticks and are the most likely
reason a comparable backtest elsewhere finishes in seconds. qkt can already synthesize bars
into ticks (`BarTickFeed`, O→L→H→C; see #214 spec) for tick-less venues. But it is **not** a
viable speed lever here:

- 100% of forge's 209 strategies carry intrabar stop-loss / take-profit brackets, and
  186/205 grids *tune* the bracket levels (`slpct`/`rrmult`). Within one bar, price can touch
  both the stop and the target; which fills **first** (win or loss) is unknowable from OHLC.
- Entry signals are 100% candle-close (already bar-faithful); tick-dependence is confined
  entirely to **intrabar order execution** (`OrderManager.evaluateTriggers` per tick,
  `PaperBroker` fills).
- Therefore only G1 (smoke: compiles + trades ≥ ~5 + PnL sign) tolerates bars, and G1 is
  already cheapened 6× by a 180-day window. Every grading gate G2-G7 ranks/selects on
  fill-sensitive metrics (Sharpe, deflated Sharpe, PBO, WFE, realized-DD sizing) and would
  change verdicts under bar replay. A bar tier is necessarily a *separate, lower-fidelity*
  tier — it can never be byte-identical to a true-tick run (intrabar high/low order assumed,
  no quotes, fewer trigger points).

Bars are parked. The lever is **decode-sharing while staying byte-identical to tick replay.**

## The sacred constraint: Backtest=Live byte-identical parity

The proven invariant (`docs/parity/backtest-vs-live.md`, `BacktestLiveParityTest`) is that
`Backtest + PaperBroker` produces a trade list identical to `LiveSession + PaperBroker` given
the same ticks — "the only difference between backtest and live is the tick feed and the
clock." Every change here must produce results **byte-identical to the current decode path**.
That rules bars out and shapes the two builds below: both share the *decode work* while
feeding the engines exactly the same `Tick` objects they get today.

## Goals

- Eliminate the within-gate decode redundancy: G3 (N param configs) and G6 (cost-stress +
  risk-fit scenarios) each decode their data **once** and run all variations off it.
- Stop each container copying the `.bin` file into private heap; read decoded `long`s
  straight from the shared OS page cache (`mmap`).
- A measurement that quantifies the residual decode fraction and **gates** the larger daemon
  build, so we do not build it on a guess.
- Every change byte-identical to today's tick replay (parity tests prove it).

## Non-goals

- Bars / a lower-fidelity tier (parked; see Context).
- The cross-process decoded-tick daemon — **deferred to Phase 2, gated on the measurement**
  (sketched under "Phase 2" so the arc is documented; not built in Phase 1).
- Reducing the *number* of G6 risk-fit simulations (proven not safely reducible below).
- Changing how live trading consumes data.

## Architecture

Three independent workstreams (WS1, WS2 in qkt; WS3 in qkt-forge) plus a measurement. The
unifying idea, already embodied by `SweepReplay`, is **"decode once, fan each tick to many
engines."** WS1 generalizes *what* can differ between those engines; WS3 points forge's
multi-run gates at it; WS2 makes the one decode cheaper and the file bytes shared across
containers.

### WS1 — Generalize the sweep fan-out to a scenario axis (qkt)

`SweepReplay` already decodes the tick stream once per worker and fans every tick to one
`ReplayEngine` per combo, "bit-identical to running each combo as its own backtest"
(`backtest/sweep/SweepReplay.kt:9-18,65-77`). Each engine owns isolated execution state
(broker, P&L, risk, candle aggregation); only the decoded stream is shared. Today the only
thing that varies per engine is **strategy params** — `BacktestContext.sweepEngines()` exposes
`engineFor: (Map<String,String>) -> ReplayEngine` (`cli/BacktestContext.kt:96-102`), and
broker / instruments / starting-balance / halt-rules are baked **once** into the context
(`BacktestContext.kt:47-54,81-84,166-234`).

The audit confirmed the layers beneath already accept all of these *per call*:
`Backtest.fromStore`/`fromSource` and the `ReplayEngine` constructor take `startingBalance`,
`instruments`, `brokerKind`, `rules`, `haltRules` as parameters
(`backtest/Backtest.kt:89-106,114-132,165-178`; `research/ReplayEngine.kt:55-71`). Critically,
**the shared feed depends only on symbols + time window + store + candleWindow**
(`Backtest.kt:188-191`) — never on broker/instruments/balance/risk/sizing. So varying those
per engine while sharing one decode is sound and parity-safe.

**Change.** Widen the per-engine config that `SweepReplay<C>` is already generic over from
`Map<String,String>` to a `ScenarioSpec`:

```
ScenarioSpec(
    label: String,
    params: Map<String, String>,          // existing param substitution (the common case)
    strategySource: String? = null,        // optional: a transformed strategy (e.g. a swapped SIZING clause)
    brokerKind: BrokerKind? = null,         // default → context value
    instruments: Path? = null,              // default → context value
    startingBalance: BigDecimal? = null,    // default → context value
    haltRules: HaltRules? = null,           // default → context value
)
```

- `BacktestContext.backtest(...)` gains per-scenario parameters, each defaulting to the
  current immutable field, and threads them into `Backtest.fromStore` (the params already
  exist there). `sweepEngines()`'s `engineFor` takes a `ScenarioSpec` instead of a `Map`.
  `SweepReplay<C>` itself does not change (already generic).
- Param substitution and `strategySource` both reduce to "compile *this* strategy variant for
  this engine" — params are already per-engine strategy variation; a swapped `SIZING` clause
  is the same mechanism with a different transform. Forge owns the text transform and supplies
  the per-scenario source (keeps qkt generic). Broker/instruments/balance/halt-rules are
  execution knobs threaded straight into the engine.
- **Hard invariant, validated:** a scenario may not change the **symbol set or timeframes** —
  those determine the single shared feed (`sharedFeed = { backtest(emptyMap()).detachFeed() }`,
  `BacktestContext.kt:97`). The scenario loader asserts every scenario's strategy declares the
  same streams; mismatch is a hard error, not a silent re-decode.

**JSON enrichment.** Each engine's `snapshot()` already returns a full
`BacktestResult`/`PerformanceReport` carrying `dailyPnL` (by UTC day,
`backtest/PerformanceReport.kt:49`) and `maxDailyDrawdown`. But `SweepCommand.printJson`
(`cli/SweepCommand.kt:100-117`) serializes only `label, params, rank, trades, totalPnL,
sharpe, calmar, maxDrawdown, winRate`. Add per-combo **`dailyPnL`** (G3 needs it for
profitable-months) and **`maxDailyDrawdown`** (G6 risk-fit needs it for prop-pass), using the
same field names as `qkt backtest --json` (`cli/ReportFormat.kt`) so forge's existing parser
extends trivially.

**CLI surface.** `qkt sweep --scenarios <file.json>`, where the file is a list of
`ScenarioSpec` entries. The existing `--param` grid stays the common path (it lowers to a
list of param-only scenarios). Reuses the proven sweep path and the per-combo JSON contract
forge already parses for G2 (`SweepRun.from_json` in `qkt/runner.py`). Reversible internal
tooling; chosen over a new subcommand to avoid a parallel code path.

**Parallelism note.** `SweepReplay` decodes once *per worker* (round-robin over
`--parallelism`), so at parallelism P the stream decodes P times. For these small-N gates,
`--parallelism 1` decodes exactly once and runs all engines on one thread. The
decode-once-vs-parallel-sim trade-off is decided by the measurement; a producer/consumer
refinement (one decoder thread, parallel consumer engines) is recorded as a measured option,
not built up front.

### WS2 — Memory-map the binary tick store (qkt)

`BinaryTickFeed` currently does `Files.readAllBytes(path)` then fills a `LongArray` per column
(`marketdata/BinaryTickFeed.kt:28-41`); `next()`/`decode()` index those arrays and allocate
one `BigDecimal` per tick via `BigDecimal.valueOf(v, scale)` (`:43-71`). The on-disk format —
a 28+symLen-byte header then contiguous little-endian `int64` blocks (timestamps, then each
present column), no padding (`marketdata/BinaryTickFormat.kt:50-63`,
`BinaryTickWriter.kt:38-40`) — is directly mappable.

**Change, staged:**

- **Option A (land first):** swap the byte source — `FileChannel.open(path, READ).use {
  it.map(READ_ONLY, 0, size).order(LITTLE_ENDIAN) }` — keeping the existing `LongArray` fill
  and decode path verbatim. ~3 lines, near-zero parity risk. Removes the private heap `byte[]`
  copy; multiple containers mapping the same file share the same physical page-cache pages.
- **Option B (only if the measurement says the `LongArray` fill matters):** drop the
  `LongArray`s and read longs straight from the mapped buffer via precomputed per-column int
  offsets (`tsBase = 28 + symLenBytes`; `colBase[col] = tsBase + (1 + slot) * tickCount * 8`),
  `decode(col,i) = buf.getLong(colBase[col] + i*8)`. ~25-35 lines confined to
  `BinaryTickFeed.kt`; format untouched.

**Three traps to encode (parity-critical):**

1. `MappedByteBuffer` defaults **BIG_ENDIAN** — must `.order(LITTLE_ENDIAN)` or every long is
   silently byte-swapped. (#1 parity trap.)
2. Mapping size and every offset are int-indexed (2GB cap). Day-files are a few MB — safe —
   but add an explicit guard if concatenated files ever map as one.
3. No deterministic unmap on the JVM (frees on GC). `BinaryTickFeed` currently inherits
   `TickFeed`'s no-op `close()`; add an explicit `close()` that drops the buffer reference (the
   `FileChannel` may close immediately after `map()`). Under fan-out, N mappings of the same
   file coexist — fine on Linux.

### WS3 — Point forge's multi-run gates at the scenario sweep (qkt-forge)

Mirror the existing `runner.sweep(...)` (used by G2) with a `runner.scenario_sweep(...)` that
emits the `--scenarios` file and parses the enriched per-combo JSON via the existing
`SweepRun.from_json` contract (`qkt/runner.py`).

- **G3 (validation).** Replace the per-config `runner.backtest(...)` loop
  (`gates/validation.py`) with one scenario sweep of the carried configs (they differ only in
  `params`, same validation window/broker/instruments). Per combo it consumes `totalPnL>0`,
  `sharpe` (vs the train Sharpe already carried from G2; degradation > `max_train_val_degradation`
  = 0.7), and `profitable_months_pct` ≥ `min_profitable_months_pct` = 0.3, derived from the
  newly-emitted `dailyPnL`. N backtests → 1 shared-decode sweep.
- **G6 cost-stress** (`gates/robustness.py`). The 3 commission-scaled runs differ in
  `instruments` (+ `broker=mt5-sim`), not params → a 3-scenario cost-axis sweep. Consumes only
  `totalPnL`. 3 → 1.
- **G6 risk-fit** (`gates/risk_fit.py`). The ~13 runs differ in `SIZING` expression (forge's
  `_sub_sizing` text rewrite → per-scenario `strategySource`) and `startingBalance` → one
  scenario sweep. Consumes `totalPnL`, `maxDailyDrawdown` (newly emitted), `maxDrawdown`,
  `calmar`, `sharpe`, `trades`. **Share the decode, not cut simulations** — the audit proved
  the sims are not safely reducible: signal/trade generation is balance-dependent
  (equity-gated `WHEN`, equity-compounding risk-% sizing via `SizingCompiler`'s `pnl.equity()`,
  equity-referenced DD halts), and forge receives no per-trade list in stdout JSON. 13 → 1.

### Measurement (the Phase 2 gate)

After WS1-WS3 ship to `:edge`, profile the heaviest gate (G6) on bot2 to quantify what is
left.

- Build qkt **from source** with JFR enabled — the `:edge` jlink runtime lacks `jdk.jfr`; pull
  `dev` first so the build reads `.bin` not stale CSV (procedure from
  `project_sweep_fanout_2026_06_17`). Measure **CPU-time fractions / hot-method shares**, not
  wall-clock (the box is contention-bound).
- Split the cost three ways: (i) byte→`long` read (the mmap target, now shared via page
  cache), (ii) `BigDecimal.valueOf` allocation + downstream `BigDecimal` decode work (the
  **only** slice a persistent cross-process cache could remove beyond mmap), (iii) per-engine
  sim (`BigDecimal` compares, `OrderManager` triggers, candle aggregation — irreducible).
- **Decision rule:** if after WS1-WS3 slice (ii) is still **> ~25%** of fleet CPU, spec
  Phase 2 (daemon). Otherwise stop — page cache + mmap + fan-out captured the win and the
  residual lever is cores/concurrency, not per-tick decode.

### Phase 2 (deferred, gated): the decoded-tick daemon

Documented for completeness; **built only if the measurement clears the bar above.** One
long-lived JVM holds an LRU cache of decoded ticks (keyed by symbol+day, evicting cold days —
the working set is small because most candidates share the 2021-2023 range); forge submits
backtest *jobs* instead of `docker run --rm`. It is the cross-process generalization of WS1's
in-process fan-out — the only way to share the `BigDecimal`-decoded form across separate gate
invocations, candidates, and loop cycles. Parity requirement: a daemon-run backtest must be
byte-identical to a standalone run (same engine, cache returns identical `Tick` objects); this
is the gating risk and why it is staged behind a measurement rather than assumed.

## Data flow (WS1 + WS3, G6 risk-fit example)

```
forge G6 risk-fit
  → builds scenarios.json: 13 entries (per-scenario SIZING source + startingBalance), same symbols+window
  → docker run --rm qkt sweep <strategy> --scenarios scenarios.json --from D --to D --json
      → BacktestContext: ONE shared feed from symbols+window (decoded once at --parallelism 1)
      → SweepReplay: each tick fanned to 13 ReplayEngines, each with its own
        compiled strategy variant + startingBalance (isolated broker/PnL/risk)
      → printJson: 13 per-combo objects incl. totalPnL, maxDailyDrawdown, maxDrawdown, calmar, sharpe, trades
  → SweepRun.from_json × 13 → prop_pass per scenario → best by calmar
```

One decode instead of 13; results byte-identical to the 13 standalone backtests.

## Testing

- **WS1 parity (the core guarantee).** For each axis (params, broker, instruments,
  startingBalance, strategySource/sizing), assert a scenario-sweep combo is byte-identical
  (trade list + every metric) to the same config run as a standalone `Backtest`. Model on the
  existing sweep-vs-standalone parity coverage.
- **WS1 JSON.** `SweepCommand --json` emits `dailyPnL` and `maxDailyDrawdown` per combo,
  matching the standalone `qkt backtest --json` values for the same config.
- **WS1 guard.** A scenarios file whose entries declare different symbols/timeframes is a hard
  error (protects the shared feed).
- **WS2 parity.** An mmap-backed `BinaryTickFeed` yields a byte-identical tick stream to the
  `readAllBytes` path across the `.bin` corpus (timestamps + every column + null sentinels).
  Explicit little-endian regression. `close()`/unmap lifecycle test.
- **WS3 (forge).** On a known real `.qkt`, the G3 and G6 scenario-sweep path yields the same
  pass/fail verdict and the same selected winner as the current N-run path (golden test).
- No mocks; real `.bin` data and the real engine throughout.

## Sequencing / risk

- WS1 and WS2 are independent qkt PRs to `dev` (can land in parallel). WS3 depends on WS1's
  `--scenarios` surface and JSON enrichment.
- Promotion: `dev` → (green) auto-fast-forward `testing` → `docker.yml` builds `:edge`. Forge
  reads `:edge`; WS3 is a forge-repo change deployed on bot2.
- Measurement runs after `:edge` carries WS1+WS2. Phase 2 (daemon) only if it clears the gate.
- Risk is concentrated in parity. Mitigation: the byte-identical tests above run before any
  forge wiring; WS2 starts at Option A (3 lines) before the riskier Option B.

## Out of scope / deferred

- Bars / a lower-fidelity tier (parked; intrabar fills need ticks).
- The Phase 2 daemon (gated on measurement).
- Reducing G6 risk-fit simulation count (not safely reducible — balance-dependent signals).
- A producer/consumer "one decoder, parallel consumers" SweepReplay (recorded as a measured
  option for parallel fan-out).

## References

- Decode path: `marketdata/BinaryTickFeed.kt:28-71`, `marketdata/BinaryTickFormat.kt:50-63`,
  `marketdata/BinaryTickWriter.kt:38-40`, `marketdata/source/LocalMarketSource.kt:38-65`.
- Fan-out: `backtest/sweep/SweepReplay.kt:9-18,65-77`, `cli/BacktestContext.kt:96-102`,
  `backtest/Backtest.kt:89-106,114-132,165-178,188-191`, `research/ReplayEngine.kt:55-71`.
- JSON: `cli/SweepCommand.kt:100-117`, `cli/ReportFormat.kt`, `backtest/PerformanceReport.kt`.
- Bars (parked): `marketdata/source/BarTickFeed.kt`, `docs/parity/backtest-vs-live.md`
  (rows A4/A6/A7/A10), `docs/superpowers/specs/2026-06-03-issue214-bar-replay-backtest-design.md`.
- Forge gates (bot2): `qkt-forge/src/qkt_forge/gates/{grid,validation,robustness,risk_fit}.py`,
  `qkt-forge/src/qkt_forge/qkt/runner.py`.
- Prior perf work: `reference_backtest_decode_perf`, `project_sweep_fanout_2026_06_17`.
