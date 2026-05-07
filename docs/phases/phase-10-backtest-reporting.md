# Phase 10 — Backtest Reporting

## Summary

Phase 10 turns the backtest from a single-shot smoke run into a serious reporting tool. After this phase, a `Backtest.run()` produces a structured result with the equity curve over time (configurable cadence, default candle-close), per-strategy attribution, and the metrics every quant report carries: profit factor, Sharpe, Calmar, win/loss stats. The data lands in memory for programmatic comparison and on disk as JSON + CSV for charting in any external tool.

The phase is deliberately scoped to **measurement, not iteration**. Parameter sweeps and walk-forward analysis are larger features that build on what we shipped here; both go to a later phase.

## What's new

- `com.qkt.backtest` package — `Backtest`, `BacktestResult`, `TradeRecord` moved here from `com.qkt.app`.
- `SampleCadence` enum — `TICK`, `CANDLE_CLOSE`, `FILL`. New `cadence` parameter on `Backtest`. Default resolves to `CANDLE_CLOSE` when `candleWindow` is set, else `TICK`.
- `EquitySample(timestamp, equity)` — single point on an equity curve.
- `EquityCurveCollector` — subscribes to the bus at the chosen cadence, exposes global and per-strategy curves.
- `PerformanceReport` — full metric bundle: realized/unrealized/total P&L, trade count, win rate, fractional max drawdown, profit factor, avg/largest win+loss, max consecutive losses, Sharpe ratio, Calmar ratio, equity curve.
- `BacktestResult.global: PerformanceReport` and `BacktestResult.perStrategy: Map<String, PerformanceReport>` — replaces the old flat fields.
- `com.qkt.backtest.metrics` — pure-function metrics: `profitFactor`, `winLossStats`, `sharpe`, `calmar`.
- `BacktestReportWriter(dir)` — emits `result.json`, `equity_global.csv`, `equity_<strategyId>.csv`, `trades.csv`, `rejections.csv`.
- `TradingCalendar.tradingPeriodsPerYear(window)` — calendar-aware annualization factor for Sharpe; crypto impl provided.
- `DrawdownTracker.fromCurve(samples)` — pure drawdown computation, used by both backtest and any future curve-based caller.
- `TradeRecord.strategyId` — every trade now carries its originating strategy id.
- `TradingPipeline.onFilled` — callback signature now `(Trade, BigDecimal, String) -> Unit`, where the third arg is the strategyId.

## Migration from previous phase

| Before | After |
|---|---|
| `import com.qkt.app.Backtest` | `import com.qkt.backtest.Backtest` |
| `import com.qkt.app.BacktestResult` | `import com.qkt.backtest.BacktestResult` |
| `import com.qkt.app.TradeRecord` | `import com.qkt.backtest.TradeRecord` |
| `result.totalPnL` | `result.global.totalPnL` |
| `result.realizedTotal` | `result.global.realizedTotal` |
| `result.unrealizedTotal` | `result.global.unrealizedTotal` |
| `result.tradeCount` | `result.global.tradeCount` |
| `result.winRate` | `result.global.winRate` |
| `result.maxDrawdown` (absolute money) | `result.global.maxDrawdown` (FRACTIONAL — Phase 9 convention) |
| `TradeRecord(trade, realized)` | `TradeRecord(trade, realized, strategyId)` |
| `onFilled = { trade, realized -> ... }` | `onFilled = { trade, realized, strategyId -> ... }` |

The biggest semantic change is **drawdown is now fractional**. Tests that asserted absolute-money drawdown values must update both the assertion and the test setup so that the equity curve has a positive peak before the dip — fractional drawdown is undefined when peak is non-positive (returns zero).

## Usage cookbook

### Default backtest (candle-close cadence)

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow

val backtest = Backtest(
    strategies = listOf("ema-cross" to MyStrategy()),
    ticks = historicalTicks,
    candleWindow = TimeWindow.ONE_MINUTE,
    // cadence defaults to CANDLE_CLOSE because candleWindow is set
)
val result = backtest.run()
println("Total P&L: ${result.global.totalPnL}")
println("Sharpe: ${result.global.sharpeRatio}")
println("Max drawdown: ${result.global.maxDrawdown}")
```

### Tick-cadence backtest (diagnostic resolution)

```kotlin
import com.qkt.backtest.SampleCadence

val backtest = Backtest(
    strategies = listOf("scalper" to MyScalper()),
    ticks = historicalTicks,
    cadence = SampleCadence.TICK,
)
```

If you omit `candleWindow` and don't pass `cadence`, the default resolves to `TICK` automatically.

### Per-strategy comparison

```kotlin
val result = Backtest(
    strategies = listOf(
        "trend" to TrendStrategy(),
        "meanrev" to MeanReversionStrategy(),
    ),
    ticks = historicalTicks,
    candleWindow = TimeWindow.ONE_MINUTE,
).run()

for ((id, report) in result.perStrategy) {
    println("$id: PnL=${report.totalPnL}, Sharpe=${report.sharpeRatio}, drawdown=${report.maxDrawdown}")
}
```

### Writing reports to disk

```kotlin
import com.qkt.backtest.report.BacktestReportWriter
import java.nio.file.Files
import java.nio.file.Paths

val dir = Paths.get("./reports/run-2026-05-07")
Files.createDirectories(dir)
BacktestReportWriter(dir).write(result)
// Files: result.json, equity_global.csv, equity_<strategyId>.csv, trades.csv, rejections.csv
```

### Charting an equity curve in pandas

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv("./reports/run-2026-05-07/equity_global.csv")
df["timestamp"] = pd.to_datetime(df["timestamp"], unit="ms")
df.plot(x="timestamp", y="equity", title="Equity curve")
plt.show()
```

### Inspecting metrics directly

```kotlin
import com.qkt.backtest.metrics.profitFactor
import com.qkt.backtest.metrics.sharpe
import com.qkt.candles.TimeWindow
import com.qkt.common.TradingCalendar

val realizeds = result.trades.map { it.realized }
val pf = profitFactor(realizeds)  // BigDecimal? — null when no losses
val annualization = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.ONE_MINUTE)
val sharpeRatio = sharpe(result.global.equityCurve.map { it.equity }, annualization)
```

## Testing patterns

The metrics are pure functions — test them with literal `BigDecimal` inputs:

```kotlin
@Test
fun `profitFactor on mixed list`() {
    val realizeds = listOf(BigDecimal("10"), BigDecimal("-5"), BigDecimal("20"))
    assertThat(profitFactor(realizeds)).isEqualByComparingTo(BigDecimal("6.0"))
}
```

End-to-end tests use real `Backtest` runs with deterministic fixture ticks:

```kotlin
val result = Backtest(
    strategies = listOf("s1" to fixtureStrategy),
    ticks = listOf(Tick("X", Money.of("100"), 1_000L), ...),
    candleWindow = TimeWindow.ONE_MINUTE,
).run()
assertThat(result.global.equityCurve).hasSize(expectedCandleCount)
assertThat(result.perStrategy["s1"]!!.equityCurve).isNotEmpty()
```

Tests that assert drawdown must construct an equity curve with a positive peak before the dip, since fractional drawdown returns 0 when no positive peak exists:

```kotlin
@Test
fun `drawdown captures unrealized swings on open positions`() {
    // Buy at 100, watch price rise to 120 (peak +20), then drop to 110 (-10)
    // fractional drawdown = (20 - 10) / 20 = 0.5
    val result = Backtest(...).run()
    assertThat(result.global.maxDrawdown).isEqualByComparingTo(BigDecimal("0.5"))
}
```

The report writer test uses JUnit 5's `@TempDir`:

```kotlin
@Test
fun `writer emits expected files`(@TempDir dir: Path) {
    BacktestReportWriter(dir).write(result)
    assertThat(dir.resolve("result.json")).exists()
}
```

## Known limitations

- **No parameter sweep / grid search.** Deferred to a future phase.
- **No walk-forward analysis.** Same.
- **No HTML report.** JSON + CSV only; HTML belongs to a presentation phase after the DSL.
- **No "total return %" or CAGR.** Both require an initial-capital concept the engine doesn't have.
- **No round-trip / hold-time metrics.** Inferring "completed trades" from a fill stream is ambiguous with scale-in/out; per-fill realized P&L is used as the proxy.
- **TICK / FILL Sharpe is approximate.** Annualization for irregular sample spacing uses the run-average interval; the `result.cadence` field tells consumers which mode produced the curve.
- **Sortino, Ulcer, recovery factor — not shipped.** Add only with a concrete demand.
- **No transactional writer.** If a CSV write fails after the JSON wrote, the directory contains a partial result. Caller decides how to handle `IOException`.
- **JSON serializer is hand-rolled.** No Jackson / kotlinx.serialization dependency added. Adequate for `BigDecimal` + ASCII identifiers; not stressed against arbitrary string content.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase10-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase10.md`
- Merge commit: `634b2e3`
