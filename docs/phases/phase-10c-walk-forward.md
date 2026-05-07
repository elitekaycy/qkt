# Phase 10c — Walk-Forward Analysis

## Summary

Phase 10c closes the validation loop on top of Phase 10b's parameter sweep. After this phase, a quant can run rolling train/test windows across history, picking the best config on each train window and evaluating it on out-of-sample test data. The headline output is the **concatenated test equity curve** — the honest answer to "what would have happened if you'd retrained at each fold boundary and deployed the winner?" Per-fold winner counts and mean train-vs-test scores flag overfit at the harness level.

This phase is small and additive. It reuses Phase 10b's `BacktestSweep` for per-fold optimization, builds rolling-window iteration as a pure function, and emits reports via a writer that sits next to the existing `BacktestReportWriter` and `SweepReportWriter`.

## What's new

- `com.qkt.backtest.walkforward` package — `WalkForwardHarness<C>`, `WalkForwardFold<C>`, `WalkForwardResult<C>`.
- `WalkForwardHarness<C>(configs, backtestFactory, totalRange, trainSize, testSize, stepSize, scoreOf, parallelism, topN)` — the orchestrator.
- Rolling-window iteration: train + test + step durations. Per-fold sweep over configs picks winner on train, evaluates on test.
- `WalkForwardResult.concatenatedTestCurve` — cumulative out-of-sample equity curve stitched across folds.
- `WalkForwardResult.winnerCounts` — `Map<configLabel, Int>` showing how often each config won.
- `WalkForwardResult.meanTrainScore` / `meanTestScore` — quantifies overfit at the harness level.
- `com.qkt.backtest.report.WalkForwardReportWriter(dir)` — emits `walkforward_summary.json`, `walkforward_summary.csv`, `concatenated_equity.csv`, `winner_counts.csv`, plus per-fold subdirectories with the full Phase 10 `BacktestReportWriter` output.
- Reuses Phase 10's `ReportSerializer` for JSON encoding — no new dependencies.

## Migration from previous phase

None. Phase 10c is purely additive. Phase 10/10b are unchanged.

## Usage cookbook

### Running walk-forward

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

val harness = WalkForwardHarness(
    configs = listOf(
        "ema_9_21"  to EmaConfig(9, 21),
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
    totalRange = TimeRange(
        from = Instant.parse("2024-01-01T00:00:00Z"),
        to = Instant.parse("2025-01-01T00:00:00Z"),
    ),
    trainSize = Duration.ofDays(180),
    testSize = Duration.ofDays(30),
    stepSize = Duration.ofDays(30),
    scoreOf = { it.global.sharpeRatio ?: BigDecimal.ZERO },
    parallelism = 4,
)

val result = harness.run()
println("Folds: ${result.folds.size}")
println("Winner counts: ${result.winnerCounts}")
println("Mean train Sharpe: ${result.meanTrainScore}")
println("Mean test  Sharpe: ${result.meanTestScore}")
```

### Writing reports to disk

```kotlin
import com.qkt.backtest.report.WalkForwardReportWriter
import java.nio.file.Files
import java.nio.file.Paths

val dir = Paths.get("./reports/walkforward-2026-05-07")
Files.createDirectories(dir)
WalkForwardReportWriter(dir).write(result)
```

Output:

```
<dir>/
├── walkforward_summary.json     # full structured report
├── walkforward_summary.csv      # one row per fold
├── concatenated_equity.csv      # cumulative out-of-sample equity curve
├── winner_counts.csv            # configLabel,winCount
└── folds/
    ├── fold_001/                # full BacktestReportWriter output
    └── fold_002/
```

### Charting the out-of-sample equity curve

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("./reports/walkforward-2026-05-07/concatenated_equity.csv")
df["timestamp"] = pd.to_datetime(df["timestamp"], unit="ms")
df.plot(x="timestamp", y="equity", title="Out-of-sample equity (walk-forward)")
plt.show()
```

### Inspecting a specific fold

```kotlin
val fold = result.folds[2]
println("Fold 3 train: ${fold.trainRange.from} to ${fold.trainRange.to}")
println("  winner: ${fold.winnerLabel} train score=${fold.trainScore}")
println("  top:    ${fold.topConfigs}")
println("  test PnL: ${fold.testResult.global.totalPnL}")
```

## Testing patterns

The harness is deterministic given deterministic inputs (Phase 10/10b property). Tests assert on result fields:

```kotlin
@Test
fun `harness runs N folds and aggregates`() {
    val result = WalkForwardHarness(...).run()
    assertThat(result.folds).hasSize(expected)
    assertThat(result.winnerCounts.values.sum()).isEqualTo(result.folds.size)
}
```

Window math is tested as a pure function:

```kotlin
@Test
fun `rolling stops when testEnd exceeds total`() {
    val folds = rollingWindows(total, train, test, step)
    assertThat(folds).hasSize(expected)
}
```

The writer test uses `@TempDir`:

```kotlin
@Test
fun `writer emits expected files`(@TempDir dir: Path) {
    WalkForwardReportWriter(dir).write(result)
    assertThat(dir.resolve("concatenated_equity.csv")).exists()
}
```

## Known limitations

- **No anchored / expanding windows.** Rolling only.
- **No tick-count / candle-count window sizing.** `Duration` only.
- **No fraction-based train/test split.** Configure rolling with one fold instead.
- **No statistical significance testing.** Aggregate stats are descriptive; significance is the caller's call.
- **No automatic best-config picker for deployment.** Caller decides what to deploy from `winnerCounts` + per-fold scores.
- **No multi-symbol cross-asset walk-forward.** Caller passes a single backtest factory; multi-symbol with cross-asset correlation is out of scope.
- **No live-mode walk-forward** (auto-retrain in production). Strictly offline.
- **Memory bound is `parallelism + folds`.** Each fold keeps the winner's `BacktestResult` (full equity curve) + top-N labels with scores.
- **`winnerConfig.toString()` rejected at write time** if it contains `,`, `"`, or `\n`. Use `data class` for sane output.
- **Not transactional.** A partial failure mid-write leaves a partial directory.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase10c-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase10c.md`
- Merge commit: `69a9493`
