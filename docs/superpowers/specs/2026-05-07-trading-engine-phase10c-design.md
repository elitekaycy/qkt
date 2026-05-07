# Phase 10c — Walk-Forward Analysis

**Status:** Design draft.
**Predecessor:** Phase 10b (parameter sweep).
**Successor:** Phase 11 (DSL).

---

## 1. Mission

Phase 10c closes the validation loop on top of Phase 10b's parameter sweep. Sweep alone is overfitting in disguise — picking the highest-Sharpe config across one period is curve-fitting to that period. Walk-forward fixes this by always validating the winner on data the optimization didn't see: roll a train/test window across history, sweep on each train, evaluate the winner on the immediately-following test, repeat.

The headline output is the **concatenated out-of-sample equity curve**: stitching each fold's test-window equity together, cumulatively. That single time series is the honest answer to "would this strategy have made money?" Per-fold winner counts and train-vs-test score comparison flag overfit at the harness level.

This phase is small and additive. It reuses Phase 10b's `BacktestSweep` for per-fold optimization, builds rolling-window iteration as a pure function, and emits reports via a writer that sits next to the existing `BacktestReportWriter` and `SweepReportWriter`.

---

## 2. Goals

- New package `com.qkt.backtest.walkforward`. `WalkForwardHarness<C>` is the orchestrator.
- Rolling windows by `Duration` — `trainSize`, `testSize`, `stepSize`. No anchored / expanding mode for v1.
- Per fold: run a sweep on the train window (reuses Phase 10b internally), pick winner by `scoreOf`, run a single backtest on the test window, record the result.
- `WalkForwardFold<C>` per-fold record: train + test ranges, winner label/config, winner's train score, full test `BacktestResult`, top-N runner-up labels with scores.
- `WalkForwardResult<C>` aggregate: per-fold list, `winnerCounts: Map<label, Int>`, `meanTrainScore`, `meanTestScore`, `concatenatedTestCurve: List<EquitySample>` (cumulative across folds).
- Caller-supplied `scoreOf: (BacktestResult) -> BigDecimal`. Sweep parallelism configurable; folds run sequentially.
- Fail-fast on any fold error — matches Phase 10b semantics.
- `WalkForwardReportWriter` emits per-fold subdirs (full `BacktestReportWriter` output) + `walkforward_summary.json/csv` + `concatenated_equity.csv` + `winner_counts.csv`.

## Non-goals

- **No anchored / expanding windows.** Rolling only. Add when a real use case appears; trivial additive feature.
- **No tick-count or candle-count window sizing.** `Duration` only. Phase 6 `MarketRequest`, calendar-aware annualization, candle windows — everything in qkt is time-based already.
- **No fraction-based train/test split.** That's not walk-forward; it's just train/test split. If you want a single split, configure rolling with one fold.
- **No custom warmup at the harness level.** Each fold's test backtest is constructed via the caller's factory; if the strategy needs warmup, the caller wires it via the existing `WarmupSpec` on `Backtest.fromSource(...)`. The harness stays pure — it doesn't know about warmup, indicators, or strategy state.
- **No fold-level parallelism.** Folds run sequentially to keep memory bounded. Within each fold, the sweep can be parallel via Phase 10b's machinery (`parallelism > 1`).
- **No pluggable winner selection.** First by `rankedBy(scoreOf)`. Ties broken by input order (consistent with `sortedByDescending`).
- **No per-config aggregate stats** beyond `winnerCounts`. Caller can compute scatter plots, per-config means, etc. from `folds` if needed.
- **No persistence across runs.** Writer dumps to disk; reading reports back into a typed `WalkForwardResult` is a future feature.
- **No live-mode walk-forward.** Strictly offline analysis.
- **No automatic best-config picker for deployment.** The harness reports which configs won which folds; choosing what to deploy from that is the caller's decision (standard quant judgement: "did the same config win N folds in a row, was the test/train ratio acceptable").
- **No statistical significance testing.** No t-tests on train-vs-test, no p-values. The aggregate stats are descriptive; significance is the caller's call.

---

## 3. Architecture

```
                                  ┌──────────────────────────────┐
                                  │  WalkForwardHarness<C>       │
                                  │                              │
                                  │  configs, backtestFactory,   │
                                  │  totalRange, train/test/step │
                                  │  scoreOf, parallelism, topN  │
                                  └──────────────┬───────────────┘
                                                 │
                                                 │ run()
                                                 ▼
                                  ┌──────────────────────────────┐
                                  │ Windows.rollingWindows(...)  │
                                  │  pure function               │
                                  │  → List<(TrainRange,         │
                                  │         TestRange)>          │
                                  └──────────────┬───────────────┘
                                                 │
                          for each fold:         │
                          ┌──────────────────────┴────────────────────┐
                          │                                           │
                          ▼                                           ▼
              ┌──────────────────────┐                    ┌──────────────────────┐
              │ BacktestSweep        │                    │ Backtest             │
              │ (Phase 10b)          │                    │ (Phase 10)           │
              │  on train range      │                    │  with winner config  │
              │  parallelism=N       │                    │  on test range       │
              └──────────┬───────────┘                    └──────────┬───────────┘
                         │ SweepResult                              │ BacktestResult
                         ▼                                          ▼
                  ┌──────────────────────────────────────────────────────┐
                  │ WalkForwardFold<C>                                   │
                  │  trainRange, testRange,                              │
                  │  winnerLabel, winnerConfig, trainScore,              │
                  │  testResult, topConfigs (top-N by train score)       │
                  └──────────────────────────┬───────────────────────────┘
                                             │
                                             ▼
                                  ┌──────────────────────────────┐
                                  │ Aggregate                    │
                                  │  winnerCounts                │
                                  │  meanTrainScore              │
                                  │  meanTestScore               │
                                  │  concatenatedTestCurve       │
                                  │  (cumulative)                │
                                  └──────────────┬───────────────┘
                                                 │
                                                 ▼
                                  ┌──────────────────────────────┐
                                  │ WalkForwardResult<C>         │
                                  └──────────────────────────────┘
```

**Read/write split.** `WalkForwardHarness` is the only orchestrator. Each fold builds a fresh `BacktestSweep` (which itself constructs fresh `Backtest` instances per config) and a fresh test `Backtest`. No shared mutable state across folds, even when sweep parallelism is enabled within a fold.

**Determinism.** Pure-function windowing + Phase 10b's deterministic sweep + Phase 10's deterministic backtest = byte-identical results across runs with the same inputs.

---

## 4. Package layout

```
com.qkt.backtest.walkforward/             # new
├── WalkForwardHarness.kt                 # orchestrator
├── WalkForwardFold.kt                    # per-fold record
├── WalkForwardResult.kt                  # full result + aggregates
└── Windows.kt                            # internal: rollingWindows + concatenate

com.qkt.backtest.report/
└── WalkForwardReportWriter.kt            # next to BacktestReportWriter, SweepReportWriter
```

Pure additive. No moves, no breaking changes. Phase 10/10b untouched.

---

## 5. Data model

### 5.1 WalkForwardFold

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.BacktestResult
import com.qkt.common.TimeRange
import java.math.BigDecimal

data class WalkForwardFold<C>(
    val trainRange: TimeRange,
    val testRange: TimeRange,
    val winnerLabel: String,
    val winnerConfig: C,
    val trainScore: BigDecimal,
    val testResult: BacktestResult,
    val topConfigs: List<Pair<String, BigDecimal>>,
)
```

`topConfigs` is sorted descending by train score and contains at most `topN` entries (default 3).

### 5.2 WalkForwardResult

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.EquitySample
import java.math.BigDecimal

data class WalkForwardResult<C>(
    val folds: List<WalkForwardFold<C>>,
    val winnerCounts: Map<String, Int>,
    val meanTrainScore: BigDecimal,
    val meanTestScore: BigDecimal,
    val concatenatedTestCurve: List<EquitySample>,
)
```

`concatenatedTestCurve` is cumulative: each fold's test equity is offset by the running total from prior folds, so the final value reflects "what would have happened if you'd retrained at each fold boundary and deployed the winner."

---

## 6. Harness API

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.time.Duration

class WalkForwardHarness<C>(
    private val configs: List<Pair<String, C>>,
    private val backtestFactory: (label: String, config: C, range: TimeRange) -> Backtest,
    private val totalRange: TimeRange,
    private val trainSize: Duration,
    private val testSize: Duration,
    private val stepSize: Duration,
    private val scoreOf: (BacktestResult) -> BigDecimal,
    private val parallelism: Int = 1,
    private val topN: Int = 3,
) {
    init {
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
        require(topN >= 1) { "topN must be >= 1, got $topN" }
        require(!trainSize.isZero && !trainSize.isNegative) { "trainSize must be positive" }
        require(!testSize.isZero && !testSize.isNegative) { "testSize must be positive" }
        require(!stepSize.isZero && !stepSize.isNegative) { "stepSize must be positive" }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) {
            "config labels must be unique: ${configs.map { it.first }}"
        }
        require(configs.all { it.first.isNotBlank() }) { "config labels must be non-blank" }
        require((trainSize + testSize).toMillis() <= totalRange.durationMs) {
            "totalRange duration (${totalRange.durationMs}ms) too short for trainSize + testSize"
        }
    }

    fun run(): WalkForwardResult<C>
}
```

### 6.1 Why one factory with `range`

The harness needs both train and test backtests scoped to specific time ranges. A single factory `(label, config, range) -> Backtest` lets the caller close over their `MarketSource`, `TimeWindow`, calendar, and risk rules once, varying only the range per invocation. The harness internally builds a per-fold `BacktestSweep` by partial-applying the train range to the factory.

### 6.2 Caller usage

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.backtest.walkforward.WalkForwardHarness
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.source.MarketRequest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class EmaConfig(val fast: Int, val slow: Int)

val harness =
    WalkForwardHarness(
        configs =
            listOf(
                "ema_9_21" to EmaConfig(9, 21),
                "ema_12_26" to EmaConfig(12, 26),
                "ema_20_50" to EmaConfig(20, 50),
            ),
        backtestFactory = { label, c, range ->
            Backtest.fromSource(
                strategies = listOf(label to EmaCrossStrategy(c.fast, c.slow)),
                source = source,
                request = MarketRequest(symbols = listOf("BTCUSDT"), from = range.from, to = range.to),
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        },
        totalRange =
            TimeRange(
                from = Instant.parse("2024-01-01T00:00:00Z"),
                to = Instant.parse("2025-01-01T00:00:00Z"),
            ),
        trainSize = Duration.ofDays(180),
        testSize = Duration.ofDays(30),
        stepSize = Duration.ofDays(30),
        scoreOf = { it.global.sharpeRatio ?: BigDecimal.ZERO },
        parallelism = 4,
        topN = 3,
    )

val result = harness.run()
println("Folds: ${result.folds.size}")
println("Winner counts: ${result.winnerCounts}")
println("Mean train Sharpe: ${result.meanTrainScore}")
println("Mean test  Sharpe: ${result.meanTestScore}")
```

---

## 7. Windowing logic

Pure function in `Windows.kt`:

```kotlin
internal fun rollingWindows(
    total: TimeRange,
    trainSize: Duration,
    testSize: Duration,
    stepSize: Duration,
): List<Pair<TimeRange, TimeRange>>
```

Algorithm:

```
folds = []
trainStart = total.from
loop:
    trainEnd = trainStart + trainSize
    testEnd  = trainEnd + testSize
    if testEnd > total.to: break
    folds.add((TimeRange(trainStart, trainEnd), TimeRange(trainEnd, testEnd)))
    trainStart += stepSize
return folds
```

Always returns at least one fold (constructor enforces `trainSize + testSize <= total.durationMs`).

**Overlap behavior:**
- `stepSize >= testSize` → test windows are non-overlapping. Standard for typical retraining cadence.
- `stepSize < testSize` → test windows overlap. Useful when train cadence is more frequent than test horizon (e.g., retrain weekly but evaluate over the next month). Caller's choice; the harness doesn't restrict.

---

## 8. Per-fold loop

```kotlin
fun run(): WalkForwardResult<C> {
    val windows = rollingWindows(totalRange, trainSize, testSize, stepSize)
    val folds =
        windows.map { (trainRange, testRange) ->
            val sweep =
                BacktestSweep(
                    configs = configs,
                    backtestFactory = { label, config -> backtestFactory(label, config, trainRange) },
                    parallelism = parallelism,
                )
            val sweepResult = sweep.run()
            val ranked = sweepResult.rankedBy(scoreOf)
            val winner = ranked.first()
            val top = ranked.take(topN).map { it.label to scoreOf(it.result) }
            val testBacktest = backtestFactory(winner.label, winner.config, testRange)
            val testResult = testBacktest.run()
            WalkForwardFold(
                trainRange = trainRange,
                testRange = testRange,
                winnerLabel = winner.label,
                winnerConfig = winner.config,
                trainScore = scoreOf(winner.result),
                testResult = testResult,
                topConfigs = top,
            )
        }

    return WalkForwardResult(
        folds = folds,
        winnerCounts = folds.groupingBy { it.winnerLabel }.eachCount(),
        meanTrainScore = mean(folds.map { it.trainScore }),
        meanTestScore = mean(folds.map { scoreOf(it.testResult) }),
        concatenatedTestCurve = concatenate(folds.map { it.testResult.global.equityCurve }),
    )
}
```

**Fail-fast** is inherited from `BacktestSweep` (Phase 10b) and the test backtest's `.run()`. If any backtest in any fold throws, the exception propagates and walk-forward stops at that fold. No partial-result handling.

**Memory bound:** the harness keeps only the winner's `BacktestResult` from each fold's sweep, plus N×top-N scores. The full `SweepResult` is discarded after picking. So in-flight memory is `O(folds + parallelism)`, not `O(folds × configs)`.

---

## 9. Concatenation

```kotlin
internal fun concatenate(curves: List<List<EquitySample>>): List<EquitySample>
```

Algorithm:

```
out = []
runningOffset = 0
for curve in curves:
    if curve is empty: continue
    for sample in curve:
        out.add(EquitySample(sample.timestamp, sample.equity + runningOffset))
    runningOffset = curve.last.equity + runningOffset  // running total includes this fold
return out
```

Result: equity flows continuously across fold boundaries. The first sample of fold N+1 starts at the final equity of fold N. Final value = sum of every fold's final equity = "compounded out-of-sample P&L."

Edge cases:
- All folds produce empty curves → returns empty list.
- Single fold → returns that fold's curve unchanged.
- Folds with `cadence != CANDLE_CLOSE` are concatenated as-is; timestamps are preserved (so the concatenated curve has irregular spacing between fold boundaries, but cumulative equity is correct).

---

## 10. Writer

```kotlin
class WalkForwardReportWriter(private val dir: Path) {
    fun <C> write(result: WalkForwardResult<C>)
}
```

Output:

```
<dir>/
├── walkforward_summary.json         # full structured report (folds + aggregates)
├── walkforward_summary.csv          # one row per fold:
│                                    #   foldIndex,trainStart,trainEnd,testStart,testEnd,
│                                    #   winnerLabel,winnerConfig,trainScore,testScore,
│                                    #   testTotalPnL,testSharpe,testDrawdown
├── concatenated_equity.csv          # timestamp,equity (cumulative across folds)
├── winner_counts.csv                # configLabel,winCount
└── folds/
    ├── fold_001/                    # full BacktestReportWriter output for this fold's test run
    │   ├── result.json
    │   ├── equity_global.csv
    │   ├── equity_<sid>.csv
    │   ├── trades.csv
    │   └── rejections.csv
    ├── fold_002/
    └── ...
```

### 10.1 Validation

Same posture as `SweepReportWriter`:
- `Files.isDirectory(dir)` and `Files.isWritable(dir)` checked first.
- Each `winnerLabel` matches `[A-Za-z0-9_-]+` — fail-fast on unsafe filesystem chars.
- Each `winnerConfig.toString()` contains no `,`, `"`, or `\n` — same rule as Phase 10b's writer.
- Each fold subdir name is `fold_<NNN>` (zero-padded to 3 digits) so listing is sorted by default.

### 10.2 JSON shape

```json
{
  "folds": [
    {
      "foldIndex": 1,
      "trainRange": { "from": "2024-01-01T00:00:00Z", "to": "2024-06-29T00:00:00Z" },
      "testRange":  { "from": "2024-06-29T00:00:00Z", "to": "2024-07-29T00:00:00Z" },
      "winnerLabel": "ema_12_26",
      "winnerConfig": "EmaConfig(fast=12, slow=26)",
      "trainScore": "1.4500",
      "testScore": "1.1200",
      "testTotalPnL": "234.50",
      "testMaxDrawdown": "0.085",
      "topConfigs": [
        {"label": "ema_12_26", "score": "1.4500"},
        {"label": "ema_9_21",  "score": "1.4100"},
        {"label": "ema_20_50", "score": "1.3500"}
      ]
    }
  ],
  "winnerCounts": { "ema_12_26": 7, "ema_9_21": 2, "ema_20_50": 1 },
  "meanTrainScore": "1.4100",
  "meanTestScore": "1.1500"
}
```

### 10.3 Failure modes

Not transactional. If a per-fold subdir write fails after the summary CSV wrote, the directory contains a partial result. Caller decides how to handle `IOException`. Same posture as Phase 10/10b.

---

## 11. Testing

### 11.1 Unit tests

- `WindowsRollingTest` — exact-fit (one fold), off-by-one (testEnd just past totalEnd → stop), single fold, multi-fold non-overlapping (`step >= testSize`), multi-fold overlapping (`step < testSize`).
- `WindowsConcatenateTest` — empty list, single fold, multi-fold cumulative offsets, fold with empty curve in the middle.
- `MeanTest` — pure helper if extracted.

### 11.2 Integration tests

- `WalkForwardHarnessTest` — fixture data (3-month range, 1-month train, 1-week test, 1-week step → ~9 folds). Asserts:
  - Fold count matches expected.
  - Each `winnerLabel` is in input config labels.
  - `topConfigs` is sorted descending by score.
  - `meanTrainScore` and `meanTestScore` are non-null.
  - `concatenatedTestCurve` length equals sum of per-fold test curve lengths.
- `WalkForwardHarnessFailFastTest` — one config's factory throws on a specific train range; assert exception propagates with original cause; subsequent folds not started.
- `WalkForwardHarnessValidationTest` — bad inputs (empty configs, blank label, duplicate label, zero/negative durations, totalRange too short) all throw `IllegalArgumentException` from constructor.
- `WalkForwardDeterminismTest` — same inputs twice → byte-identical `WalkForwardResult` (compare `concatenatedTestCurve` and per-fold test results).

### 11.3 Writer tests

- `WalkForwardReportWriterTest` — `@TempDir`, write a 3-fold result, assert all expected files exist, assert summary CSV has correct header + 3 data rows, assert per-fold subdirs exist with `result.json`.
- `WalkForwardReportWriterValidationTest` — unsafe label and comma-bearing config both throw before any file is written.

### 11.4 Test conventions

- AssertJ + JUnit 5, no mocks.
- `FixedClock`, `MonotonicSequenceGenerator`, `SequentialIdGenerator` everywhere.
- Fixture ticks generated deterministically (e.g., a 1-year synthetic price series at 1-minute intervals).

---

## 12. Migration & risk

- **Risk: Low-Medium.** Pure additive package. No engine changes. Reuses Phase 10b for per-fold sweep and Phase 10 for the test backtest; if those work, walk-forward works.
- The two new code paths are pure functions: rolling-window iteration and curve concatenation. Both are easy to test with literal inputs.
- Memory: bounded by `parallelism + folds`. Each fold keeps the winner's `BacktestResult` (with full equity curve) + top-N scores. For 50 folds × CANDLE_CLOSE 1m equity curves, total memory is well under 100MB. Safe.
- No new external dependencies on the runtime classpath.

---

## 13. Success criteria

After Phase 10c merges:

- `WalkForwardHarness` runs N folds across a `TimeRange`, picks winners on each train, evaluates on each test, returns `WalkForwardResult`.
- `result.concatenatedTestCurve` is a copy-pasteable CSV-ready time series — `pd.read_csv("concatenated_equity.csv").plot()` is the one-liner that shows out-of-sample equity.
- `result.winnerCounts` flags whether the same config wins repeatedly (robust) or different configs each fold (overfit / regime-dependent).
- `result.meanTrainScore` vs `result.meanTestScore` quantifies overfit at the harness level.
- Memory remains bounded by `parallelism + folds`, regardless of config count.
- `./gradlew build` is green. All tests deterministic. No new external dependency on the runtime classpath.

---

## 14. Deferred / Phase 10d+ candidates

These are explicitly **not** in this phase:

- **Anchored / expanding windows** — `train` always starts at `total.from`, grows over time. Trivial additive option once a use case appears.
- **Tick-count or candle-count window sizing** — pulls in "uniform-density" assumptions; defer.
- **Statistical significance tests** — t-tests, bootstrapping, p-values on train-vs-test scores. Research phase.
- **Automatic best-config picker for live deployment** — "use the config that won the last K folds" or "use the median-score config across all folds." Caller writes today; ship a helper if there's a real use case.
- **Walk-forward over multiple symbols simultaneously** — currently caller passes one `MarketRequest` per backtest; multi-symbol walk-forward with cross-asset correlation is a separate research phase.
- **Live-mode walk-forward** (auto-retrain on schedule in production) — an entirely different beast. Out of scope.
- **Aggregated diagnostic plots / HTML report** — JSON + CSV are enough for now. HTML is presentation layer.
- **Persistence + reload of `WalkForwardResult`** — read JSON back into typed object. YAGNI.
