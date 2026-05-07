# Phase 10b — Parameter Sweep

## Summary

Phase 10b adds the iteration loop on top of Phase 10. After this phase, a quant hands the engine a list of strategy configurations, kicks off a sweep, and gets back a ranked result — with a writer that produces a summary CSV plus the full per-run reports from Phase 10. Sequential by default for determinism; opt-in fixed-pool parallelism for callers who want speed on multi-core hardware.

## What's new

- `com.qkt.backtest.sweep` package — `BacktestSweep<C>`, `SweepRun<C>`, `SweepResult<C>`.
- `BacktestSweep<C>(configs, backtestFactory, parallelism = 1)` — runs N configs through the existing `Backtest` engine.
- `SweepResult.byLabel(label)` — lookup by label.
- `SweepResult.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }` — sort descending by an arbitrary metric extractor.
- `BacktestSweep` validation — non-empty configs, unique non-blank labels, parallelism >= 1.
- Fail-fast error semantics — first exception in any run cancels pending runs and propagates with the original cause.
- `com.qkt.backtest.report.SweepReportWriter(dir)` — emits `sweep_summary.csv`, `sweep_summary.json`, and `runs/<label>/` subdirectories with the full Phase 10 `BacktestReportWriter` output per config.
- Reuses Phase 10's `ReportSerializer` for JSON encoding — no new dependencies.

## Migration from previous phase

None. Phase 10b is purely additive. Phase 10's `Backtest`, `BacktestResult`, and `BacktestReportWriter` are unchanged.

## Usage cookbook

### Sequential sweep (default)

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.candles.TimeWindow
import java.math.BigDecimal

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
    )

val result = sweep.run()
val best = result.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }.first()
println("Best: ${best.label} sharpe=${best.result.global.sharpeRatio}")
```

### Parallel sweep

```kotlin
val sweep =
    BacktestSweep(
        configs = configs,
        backtestFactory = { label, c -> /* ... */ },
        parallelism = 4,
    )
```

Pool size is `min(parallelism, configs.size)`. Result order matches input order regardless of completion order.

### Writing reports to disk

```kotlin
import com.qkt.backtest.report.SweepReportWriter
import java.nio.file.Files
import java.nio.file.Paths

val dir = Paths.get("./reports/sweep-2026-05-07")
Files.createDirectories(dir)
SweepReportWriter(dir).write(result)
```

Output layout:

```
<dir>/
├── sweep_summary.csv        # one row per run with key metrics
├── sweep_summary.json       # structured equivalent
└── runs/
    ├── <label_1>/           # full Phase 10 BacktestReportWriter output
    └── <label_2>/
```

### Charting the summary in pandas

```python
import pandas as pd

df = pd.read_csv("./reports/sweep-2026-05-07/sweep_summary.csv")
print(df.sort_values("sharpeRatio", ascending=False).head(10))
```

### Inspecting a specific run after sweeping

```kotlin
val emaWinner = result.byLabel("ema_12_26")!!
println("equity curve length: ${emaWinner.result.global.equityCurve.size}")
println("trades: ${emaWinner.result.trades.size}")
```

### Cartesian-product helper (caller-owned)

```kotlin
fun <K, V> cartesian(grid: Map<K, List<V>>): List<Map<K, V>> {
    if (grid.isEmpty()) return listOf(emptyMap())
    val keys = grid.keys.toList()
    val values = keys.map { grid[it]!! }
    var combos: List<Map<K, V>> = listOf(emptyMap())
    for ((i, k) in keys.withIndex()) {
        combos = combos.flatMap { combo -> values[i].map { combo + (k to it) } }
    }
    return combos
}

val configs =
    cartesian(mapOf("fast" to listOf(9, 12), "slow" to listOf(21, 26)))
        .map { c -> "ema_${c["fast"]}_${c["slow"]}" to EmaConfig(c["fast"]!!, c["slow"]!!) }
```

This is intentionally a 4-line helper rather than a `BacktestSweep.grid(...)` API — keeping the sweep itself typed (`<C>`) and pure.

## Testing patterns

`BacktestSweep` is deterministic per run, so tests assert on result fields directly:

```kotlin
@Test
fun `sweep produces ranked result`() {
    val sweep =
        BacktestSweep(
            configs = listOf("a" to 1, "b" to 2),
            backtestFactory = { _, _ -> /* fixture Backtest */ },
        )
    val result = sweep.run()
    assertThat(result.runs.map { it.label }).containsExactly("a", "b")
}
```

Parallel mode is tested by asserting result equivalence between `parallelism = 1` and `parallelism = 4`:

```kotlin
val seq = build(parallelism = 1).run()
val par = build(parallelism = 4).run()
assertThat(par.runs.map { it.label }).isEqualTo(seq.runs.map { it.label })
```

Fail-fast is tested by injecting an exception in the factory for one config and asserting the original cause propagates.

The writer test uses JUnit 5's `@TempDir`:

```kotlin
@Test
fun `writer emits expected files`(@TempDir dir: Path) {
    SweepReportWriter(dir).write(result)
    assertThat(dir.resolve("sweep_summary.csv")).exists()
}
```

## Known limitations

- **No walk-forward analysis.** Phase 10c will layer rolling train/test windows on top of `BacktestSweep`.
- **No grid-search optimization heuristics.** Brute-force iteration is the baseline. Bayesian / random / hyperband search is a separate research phase if it ever becomes valuable.
- **Memory scales linearly with parallelism.** A `parallelism = N` sweep over long backtests with TICK cadence holds N×curve-points in memory simultaneously. Default cadence (CANDLE_CLOSE) is bounded.
- **`config.toString()` is the rendering for both CSV and JSON.** Callers should use `data class` for sane output. Configs containing `,`, `"`, or `\n` are rejected at write time — the writer is strict, not escaping.
- **No transactional writer.** A partial failure mid-write leaves a partial directory. Caller decides how to clean up.
- **Pool sanity timeout = 60s.** After `executor.shutdown()`, we wait at most 1 minute for tasks to finish. Beyond that, threads are abandoned to JVM cleanup.
- **No progress callbacks, cancellation tokens, or async APIs.** `sweep.run()` is blocking. `Thread.interrupt()` is the only escape hatch.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase10b-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase10b.md`
- Merge commit: filled in at merge time.
