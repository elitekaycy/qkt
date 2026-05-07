# Phase 10b — Parameter Sweep

**Status:** Design draft.
**Predecessor:** Phase 10 (backtest reporting).
**Successor:** Phase 10c (walk-forward analysis), Phase 11 (DSL).

---

## 1. Mission

Phase 10b adds the iteration loop on top of Phase 10. After this phase, a quant can hand the engine a list of strategy configurations, kick off a sweep, and get back a ranked map of results — `Map<config, BacktestResult>` in spirit, with a writer that produces a summary CSV plus the full per-run reports from Phase 10. The phase ships sequential execution by default (deterministic, simple) and opt-in fixed-pool parallelism for callers who want speed on multi-core hardware.

The phase is deliberately scoped to **parameter variation, not time variation**. Walk-forward analysis (rolling train/test windows) is a separate phase that builds on this one.

---

## 2. Goals

- New package `com.qkt.backtest.sweep`. `BacktestSweep<C>` is the harness; `SweepRun<C>` and `SweepResult<C>` are the data types.
- Caller-provided per-config Backtest factory — the sweep harness owns iteration and parallelism, the caller owns the strategy + per-config Backtest construction.
- Sequential by default (`parallelism = 1`). Opt-in parallel via fixed thread pool (`parallelism > 1`).
- Result order matches input order regardless of completion order, in both sequential and parallel modes.
- Fail-fast: first exception in any run cancels pending runs and propagates. No silent skipping.
- `SweepResult.rankedBy(scoreOf)` — sorts descending by an arbitrary metric extractor.
- `SweepReportWriter(dir)` — emits `sweep_summary.csv` + `sweep_summary.json` at top level, plus a `runs/<label>/` subdirectory per config with the full Phase 10 `BacktestReportWriter` output.

## Non-goals

- **No walk-forward analysis.** Rolling train/test windows are Phase 10c. The spec for that phase will be a layer on top of `BacktestSweep` — sweep across configs on a train window, pick best, evaluate on the test window.
- **No cartesian-product builder.** A pure `cartesianProduct(map: Map<String, List<Any>>): List<Map<String, Any>>` helper is a 4-line function the caller can write or that we ship as a utility later. Keeping the sweep API typed (`<C>`) is more important than ergonomics for the grid-search special case.
- **No multi-run comparison report** (option C from earlier brainstorming). `SweepResult.runs` already gives the caller everything to write a side-by-side comparison if they want. We ship the sweep summary; ad-hoc comparisons are a writer-layer concern.
- **No persistence of `SweepResult` across processes.** The writer dumps to disk; reading reports back into a typed `SweepResult` is a future feature if it's ever needed.
- **No live-mode parallelism.** The qkt single-thread invariant (skill §7) still applies to the engine hot path. Phase 10b parallelism applies only to running multiple isolated `Backtest` instances offline.
- **No progress callbacks, cancellation tokens, or async APIs.** `Sweep.run()` is blocking. Caller can `Thread.interrupt()` if they really need to stop a runaway sweep.
- **No automatic best-config selection** (e.g., "return the run with highest Sharpe"). `SweepResult.rankedBy` is a sort utility — picking is the caller's call.
- **No grid-search optimization heuristics** (Bayesian optimization, hyperband, random search variants). Pure brute-force iteration over a caller-supplied list. Smarter search is a separate research phase.

---

## 3. Architecture

```
                    ┌────────────────────────────────────────────┐
                    │ BacktestSweep<C>                           │
                    │                                            │
                    │   configs: List<Pair<String, C>>           │
                    │   backtestFactory: (label, config) -> ...  │
                    │   parallelism: Int = 1                     │
                    │                                            │
                    │   run() ─┬─ parallelism == 1 → sequential  │
                    │          └─ parallelism >  1 → thread pool │
                    └─────────────────┬──────────────────────────┘
                                      │
                                      ▼
                            for each (label, config):
                            ┌────────────────────────┐
                            │ backtestFactory(...)   │
                            │   → fresh Backtest     │
                            │     (own feed, bus,    │
                            │      clock, etc.)      │
                            └───────────┬────────────┘
                                        │
                                        ▼
                                   .run() → BacktestResult
                                        │
                                        ▼
                                   SweepRun<C>(label, config, result)
                                        │
                                        ▼
                                   SweepResult<C>(runs)
```

**Read/write split.** `BacktestSweep` is the only orchestrator. Each `Backtest` instance is independent — no shared mutable state across runs, even in parallel mode. The qkt single-producer-per-type invariant is preserved within each run; the sweep harness sits one layer above.

**Determinism.** Sequential is deterministic by construction. Parallel preserves per-run determinism (each engine is its own world) and per-result-order determinism (we collect futures in input order via `futures.map { it.get() }`). The only non-determinism is wall-clock — which thread finishes first.

---

## 4. Package layout

```
com.qkt.backtest.sweep/                          # new
├── BacktestSweep.kt                             # the harness
├── SweepRun.kt                                  # data class
└── SweepResult.kt                               # data class with ranking helper

com.qkt.backtest.report/
└── SweepReportWriter.kt                         # new — sits next to BacktestReportWriter
```

Pure additive. No moves, no breaking changes. Callers who don't import `com.qkt.backtest.sweep.*` see zero change.

---

## 5. Data model

### 5.1 SweepRun

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult

data class SweepRun<C>(
    val label: String,
    val config: C,
    val result: BacktestResult,
)
```

### 5.2 SweepResult

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult
import java.math.BigDecimal

data class SweepResult<C>(
    val runs: List<SweepRun<C>>,
) {
    fun byLabel(label: String): SweepRun<C>? = runs.firstOrNull { it.label == label }

    fun rankedBy(scoreOf: (BacktestResult) -> BigDecimal): List<SweepRun<C>> =
        runs.sortedByDescending { scoreOf(it.result) }
}
```

`rankedBy` returns sorted descending. Caller picks "best by Sharpe" via:

```kotlin
val best = result.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }.first()
```

A null Sharpe is coerced to zero in the score function — caller decides how to treat undefined metrics. The harness doesn't second-guess.

---

## 6. BacktestSweep API

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BacktestSweep<C>(
    private val configs: List<Pair<String, C>>,
    private val backtestFactory: (label: String, config: C) -> Backtest,
    private val parallelism: Int = 1,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) {
            "config labels must be unique: ${configs.map { it.first }}"
        }
        require(configs.all { it.first.isNotBlank() }) { "config labels must be non-blank" }
    }

    fun run(): SweepResult<C> =
        if (parallelism == 1) runSequential() else runParallel()

    private fun runSequential(): SweepResult<C> =
        SweepResult(
            configs.map { (label, config) ->
                SweepRun(label, config, backtestFactory(label, config).run())
            },
        )

    private fun runParallel(): SweepResult<C> {
        val poolSize = parallelism.coerceAtMost(configs.size)
        val executor = Executors.newFixedThreadPool(poolSize)
        try {
            val futures =
                configs.map { (label, config) ->
                    executor.submit<SweepRun<C>> {
                        SweepRun(label, config, backtestFactory(label, config).run())
                    }
                }
            return try {
                SweepResult(futures.map { it.get() })
            } catch (e: ExecutionException) {
                executor.shutdownNow()
                throw e.cause ?: e
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(1, TimeUnit.MINUTES)
        }
    }
}
```

### 6.1 Parameter contract

- **`configs: List<Pair<String, C>>`** — same shape as the existing `TradingPipeline.strategies`. Caller picks labels; labels must be unique, non-blank, and (for the writer) match `[A-Za-z0-9_-]+`.
- **`backtestFactory: (label, config) -> Backtest`** — invoked once per config. Must return a fresh `Backtest` instance every time. The closure is the natural place to vary strategy params, warmup specs, risk rules, ticks/source per config.
- **`parallelism: Int = 1`** — sequential when 1, fixed-pool thread pool when >1. Pool size is `min(parallelism, configs.size)`.

### 6.2 Why one factory instead of two

An earlier sketch had a separate `(C) -> Strategy` factory plus a `(label, Strategy) -> Backtest` factory. That ended up redundant — callers always close over `config` inside the Backtest factory anyway, so we collapsed to a single factory taking both `label` and `config`. One closure, no double indirection.

### 6.3 Parallel determinism

The thread pool is `Executors.newFixedThreadPool(...)` — bounded threads, FIFO submission. Each task is fully self-contained: it gets a fresh `Backtest`, which gets a fresh `EventBus`, `MarketPriceTracker`, `PaperBroker`, `RiskState`, `EquityCurveCollector`, etc. There is **no** shared mutable state between tasks.

Order preservation: we map `futures.map { it.get() }` over the **input order**. This blocks until each future in input-order finishes, regardless of which thread completed first. The resulting `SweepResult.runs` always matches the input `configs` order.

### 6.4 Fail-fast semantics

In sequential mode, exceptions propagate naturally — the iteration stops, no further runs execute, the exception surfaces.

In parallel mode:
1. We submit all tasks up front. The pool may already be running them when an early one throws.
2. `futures.map { it.get() }` blocks in input order. The first throwing task raises `ExecutionException` from `.get()`.
3. We catch it, call `executor.shutdownNow()` to interrupt pending tasks, and re-throw the original cause.
4. The `finally` block calls `executor.shutdown()` + `awaitTermination(1, TimeUnit.MINUTES)` to ensure the pool fully drains before the method returns.

Edge case: a task earlier in input order succeeds but a later one is still running and throws first. Because we walk `futures` in input order, the early success is read first; the later exception only surfaces when we reach its index. This is fine — we still propagate the exception, just slightly later than "first failure wall-clock." For sweeps, this is acceptable: we always propagate *some* exception, and the input-order walk gives deterministic error reporting.

### 6.5 Why fixed pool, not coroutines or Stream.parallel

- **Coroutines** would add a `kotlinx-coroutines-core` dependency we don't currently have. Backtests are CPU-bound, not I/O-bound — coroutines bring async machinery we don't need.
- **`parallelStream()`** uses `ForkJoinPool.commonPool()`, which is shared across the JVM. Sweeps could starve other commonPool users, and we'd have no control over pool size. Bounded `Executors.newFixedThreadPool` is the right tool.

---

## 7. SweepReportWriter format

```kotlin
package com.qkt.backtest.report

import com.qkt.backtest.sweep.SweepResult
import java.nio.file.Path

class SweepReportWriter(private val dir: Path) {
    fun <C> write(result: SweepResult<C>)
}
```

### 7.1 Output layout

```
<dir>/
├── sweep_summary.csv        # one row per run with key metrics
├── sweep_summary.json       # structured equivalent + config rendering
└── runs/
    ├── <label_1>/           # full BacktestReportWriter output
    │   ├── result.json
    │   ├── equity_global.csv
    │   ├── equity_<sid>.csv
    │   ├── trades.csv
    │   └── rejections.csv
    ├── <label_2>/
    └── ...
```

The per-run subdirs are produced by invoking the existing `BacktestReportWriter(runs/<label>).write(run.result)`. Pure reuse — zero duplication of Phase 10's serializer.

### 7.2 sweep_summary.csv

```
label,config,totalPnL,sharpeRatio,maxDrawdown,winRate,tradeCount,profitFactor,calmarRatio
ema_9_21,EmaConfig(fast=9, slow=21),123.45,1.2345,0.1234,0.55,42,1.8,10.0
ema_12_26,EmaConfig(fast=12, slow=26),98.76,1.0123,0.1500,0.50,38,1.5,6.5
...
```

- **Header row required.**
- **`config` column** uses `config.toString()`. For Kotlin `data class`, that's the auto-generated form `EmaConfig(fast=9, slow=21)`. Documented in the cookbook: callers should use `data class` for readable output.
- Numeric columns use `BigDecimal.toPlainString()` (no scientific notation), `null` rendered as empty string.
- No quoting/escaping. Validates each label matches `[A-Za-z0-9_-]+` and config string contains no commas or newlines before writing — fail-fast `IllegalArgumentException`.

### 7.3 sweep_summary.json

```json
[
  {
    "label": "ema_9_21",
    "config": "EmaConfig(fast=9, slow=21)",
    "totalPnL": "123.45",
    "sharpeRatio": "1.2345",
    "maxDrawdown": "0.1234",
    "winRate": "0.55",
    "tradeCount": 42,
    "profitFactor": "1.8",
    "calmarRatio": "10.0"
  },
  ...
]
```

- Top-level array, one object per run, in `SweepResult.runs` order (which matches input order).
- `BigDecimal` rendered as JSON string (Phase 10 convention). `null` becomes JSON `null`.
- Reuses `ReportSerializer` from `com.qkt.backtest.report` for primitives.

### 7.4 Validation + failure modes

Before writing anything:
- `Files.isDirectory(dir)` — fail-fast if not a directory.
- `Files.isWritable(dir)` — fail-fast if read-only.
- For each `run.label`: `[A-Za-z0-9_-]+` — fail-fast if the label is unsafe for filesystems.
- For each `run.config.toString()`: no commas, no newlines, no double quotes — fail-fast (we don't escape; we reject).

Once validation passes, we write. Not transactional — if a per-run dir write fails halfway, the directory contains a partial result. Caller decides whether to `rm -rf` the dir and retry.

---

## 8. Worked example

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.backtest.report.SweepReportWriter
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Paths

data class EmaConfig(val fast: Int, val slow: Int)

val sweep =
    BacktestSweep(
        configs =
            listOf(
                "ema_9_21" to EmaConfig(9, 21),
                "ema_12_26" to EmaConfig(12, 26),
                "ema_20_50" to EmaConfig(20, 50),
            ),
        backtestFactory = { label, c ->
            Backtest(
                strategies = listOf(label to EmaCrossStrategy(c.fast, c.slow)),
                ticks = historicalTicks,
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        },
        parallelism = 4,  // sequential is parallelism = 1
    )

val result = sweep.run()
val best = result.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }.first()
println("Best: ${best.label} sharpe=${best.result.global.sharpeRatio}")

val dir = Paths.get("./reports/sweep-2026-05-07")
Files.createDirectories(dir)
SweepReportWriter(dir).write(result)
// Files: sweep_summary.csv, sweep_summary.json, runs/ema_9_21/..., runs/ema_12_26/..., runs/ema_20_50/...
```

---

## 9. Testing

### 9.1 Unit tests

- `BacktestSweepSequentialTest` — 3 configs, deterministic results, order matches input order, calls `backtestFactory` once per config, `SweepRun.label/config/result` populated correctly.
- `BacktestSweepParallelTest` — same setup with `parallelism = 2`. Asserts results are deterministic per run (Sharpe identical across the two modes). Asserts `runs.map { it.label }` matches input order.
- `BacktestSweepFailFastTest` — one config's factory throws; assert the exception propagates with the original message, both in sequential and parallel mode. In parallel: assert that not-yet-started tasks were interrupted (via a counter that the factory increments).
- `BacktestSweepValidationTest` — empty configs, blank label, duplicate labels, `parallelism = 0` all throw `IllegalArgumentException` from the constructor.
- `SweepResultTest` — `byLabel` lookup hit + miss; `rankedBy` sort order including null scores (mapped to a default by the caller's score function).

### 9.2 Writer tests

- `SweepReportWriterTest` — `@TempDir`, write a 3-config sweep, read back: assert `sweep_summary.csv` has 3 data rows + correct header, assert each `runs/<label>/` directory contains `result.json` etc.
- `SweepReportWriterValidationTest` — unsafe label, config with embedded comma → both throw `IllegalArgumentException`, no files written.

### 9.3 Test conventions

- AssertJ + JUnit 5, no mocks.
- `FixedClock`, `MonotonicSequenceGenerator`, `SequentialIdGenerator` everywhere — preserved by reusing the existing `Backtest` constructor.
- Parallel tests use `parallelism = 2` minimum so race conditions are exercised. Each test deterministically asserts on result content, not timing.

---

## 10. Migration & risk

- **Risk: Low.** Pure additive. Phase 10's `Backtest`, `BacktestResult`, `BacktestReportWriter` unchanged.
- No new external dependencies.
- The qkt single-thread invariant (skill §7) is acknowledged: parallel applies only to offline sweep tooling, never the engine hot path. Per-run engines remain single-threaded.
- Memory note: callers running parallel sweeps over long backtests with TICK cadence should be aware that N×curve-points memory scales linearly with parallelism. Documented in the changelog cookbook; not enforced.
- The `awaitTermination(1, TimeUnit.MINUTES)` cap is a sanity timeout. If a runaway backtest exceeds 60s after pool shutdown, the method returns and threads are abandoned — JVM-managed cleanup. Acceptable for a tool callers invoke deliberately; documented.
- `SweepReportWriter`'s reject-on-unsafe-config approach (no comma/newline/quote in `config.toString()`) is strict but predictable. Callers who need richer rendering can subclass or write their own writer; YAGNI for v1.

---

## 11. Success criteria

After Phase 10b merges:

- `BacktestSweep` runs N configs through `Backtest` and returns a `SweepResult<C>` with deterministic per-run results and stable ordering.
- `parallelism = 1` is the default; `parallelism > 1` is opt-in and fail-fast on errors.
- A 50-config sweep on 1m crypto data completes in seconds with `parallelism = 8` on a typical desktop.
- `SweepReportWriter` produces a flat `sweep_summary.csv` plus per-run dirs that match Phase 10's `BacktestReportWriter` exactly — pandas can `pd.read_csv("sweep_summary.csv")` and rank/filter directly.
- Phase 10c (walk-forward) can be implemented as a thin layer over `BacktestSweep` — sweep across configs on the train window, pick best by score, evaluate on the test window — with no changes to the sweep harness.
- `./gradlew build` is green. All tests deterministic. No new external dependency on the runtime classpath.

---

## 12. Deferred / Phase 10c+ candidates

These are explicitly **not** in this phase:

- **Walk-forward harness** — Phase 10c. `WalkForwardHarness(sweep, windows, scoreBy)` runs a sweep on each train window, picks the best by score, evaluates on the test window, returns a per-fold report.
- **Cartesian product helper** — pure utility; can land in a follow-up commit if useful.
- **Bayesian / random / hyperband search** — research phase. Brute force is the right baseline.
- **Cross-sweep comparison report** — diff two `SweepResult`s. Caller writes today; ship a writer when there's a real use case.
- **Best-config picker integrated into the sweep API** — `sweep.runAndPickBest { ... }` shorthand. YAGNI; `result.rankedBy { ... }.first()` is one line.
- **Cancellation tokens / progress callbacks** — async machinery we don't need today. Add if real users hit long-running sweeps that they need to abort mid-flight.
- **Persistence + reload of `SweepResult`** — read the JSON summary back into a typed `SweepResult`. YAGNI — callers wanting to compare sweeps across processes can re-run or write their own loader.
