# Phase 16 — Backtest HTML report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a single self-contained `report.html` per backtest run with embedded SVG charts, drawdown-period table, Monte Carlo fan, per-trade risk, and the existing headline metrics.

**Architecture:** Two pure-function modules (`DrawdownAnalyzer`, `MonteCarlo`) extend `PerformanceReport` with `drawdownPeriods` + `monteCarlo`. Risk per trade flows from `OrderManager`'s bracket evaluation through a side-channel map keyed by client order id, attached to each `TradeRecord` in `Backtest`. A new `HtmlReportWriter` renders the extended report as a single HTML file alongside the existing `result.json`/CSVs. Charts are pure inline SVG (no JS, no external CDN) for offline portability.

**Tech Stack:** Kotlin 1.9, JDK 21, Gradle, JUnit 5, AssertJ. No new runtime dependencies.

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `src/main/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzer.kt` | Compute peak-to-trough segments from an equity curve |
| `src/main/kotlin/com/qkt/backtest/metrics/MonteCarlo.kt` | Bootstrap-with-replacement simulator, percentile output |
| `src/main/kotlin/com/qkt/backtest/DrawdownPeriod.kt` | Data class for a single DD period |
| `src/main/kotlin/com/qkt/backtest/MonteCarloSummary.kt` | Data classes for MC output (`MonteCarloSummary`, `EquityFanPoint`) |
| `src/main/kotlin/com/qkt/backtest/report/SvgChart.kt` | `lineChart`, `lineChartWithUnderwater`, `fanChart` helpers (pure SVG strings) |
| `src/main/kotlin/com/qkt/backtest/report/HtmlReportConfig.kt` | Truncation, threshold, MC seed/sims/min-trades knobs |
| `src/main/kotlin/com/qkt/backtest/report/HtmlReportWriter.kt` | Renders `report.html` from a `BacktestResult` + config |
| `src/test/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzerTest.kt` | Algorithm coverage on synthetic curves |
| `src/test/kotlin/com/qkt/backtest/metrics/MonteCarloTest.kt` | Determinism + percentile sanity checks |
| `src/test/kotlin/com/qkt/backtest/report/SvgChartTest.kt` | Well-formed SVG output |
| `src/test/kotlin/com/qkt/backtest/report/HtmlReportWriterTest.kt` | Structural assertions on the produced HTML |
| `docs/phases/phase-16.md` | Phase changelog |

### Modified files

| Path | Change |
|---|---|
| `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt` | Add `drawdownPeriods: List<DrawdownPeriod>`, `monteCarlo: MonteCarloSummary?` |
| `src/main/kotlin/com/qkt/backtest/TradeRecord.kt` | Add `riskUsd: BigDecimal?` with default `null` |
| `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt` | Populate `drawdownPeriods` + `monteCarlo` |
| `src/main/kotlin/com/qkt/backtest/Backtest.kt` | Read risk from `OrderManager` side-channel when constructing `TradeRecord` |
| `src/main/kotlin/com/qkt/app/OrderManager.kt` | Expose `riskUsdFor(clientOrderId: String): BigDecimal?`; populate from bracket evaluation |
| `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt` | Append `riskUsd` column to `trades.csv`; emit `report.html` |
| `src/main/kotlin/com/qkt/backtest/report/ReportSerializer.kt` | Render `drawdownPeriods` + `monteCarlo` JSON |
| `src/main/kotlin/com/qkt/cli/BuildInfo.kt` | `VERSION = "0.18.0"` |
| `README.md` | Phase 16 line |
| `docs/backlog.md` | Mark backtest fidelity item `done` |

---

## Task 1: `DrawdownAnalyzer` and `DrawdownPeriod`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/DrawdownPeriod.kt`
- Create: `src/main/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzer.kt`
- Test: `src/test/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzerTest.kt`

- [ ] **Step 1: Create `DrawdownPeriod`**

```kotlin
// src/main/kotlin/com/qkt/backtest/DrawdownPeriod.kt
package com.qkt.backtest

import java.math.BigDecimal

data class DrawdownPeriod(
    val peakTimestamp: Long,
    val peakEquity: BigDecimal,
    val troughTimestamp: Long,
    val troughEquity: BigDecimal,
    val recoveryTimestamp: Long?,
    val depthPct: BigDecimal,
    val durationMs: Long,
    val ongoing: Boolean,
)
```

- [ ] **Step 2: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzerTest.kt
package com.qkt.backtest.metrics

import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class DrawdownAnalyzerTest {
    private fun sample(t: Long, e: String) = EquitySample(t, BigDecimal(e))

    private val threshold = BigDecimal("-0.01")

    @Test
    fun `empty curve yields empty list`() {
        assertThat(DrawdownAnalyzer.analyze(emptyList(), threshold)).isEmpty()
    }

    @Test
    fun `monotone-up curve yields empty list`() {
        val curve = listOf(sample(0, "100"), sample(1, "110"), sample(2, "120"))
        assertThat(DrawdownAnalyzer.analyze(curve, threshold)).isEmpty()
    }

    @Test
    fun `single peak-trough-recovery cycle is captured`() {
        val curve = listOf(
            sample(0, "100"),
            sample(1, "120"),    // peak
            sample(2, "108"),    // trough -10%
            sample(3, "120"),    // recovery
            sample(4, "125"),
        )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(1)
        val p = periods[0]
        assertThat(p.peakTimestamp).isEqualTo(1L)
        assertThat(p.peakEquity).isEqualByComparingTo("120")
        assertThat(p.troughTimestamp).isEqualTo(2L)
        assertThat(p.troughEquity).isEqualByComparingTo("108")
        assertThat(p.recoveryTimestamp).isEqualTo(3L)
        assertThat(p.depthPct.toDouble()).isCloseTo(-0.10, within(0.001))
        assertThat(p.durationMs).isEqualTo(2L)
        assertThat(p.ongoing).isFalse
    }

    @Test
    fun `multiple non-overlapping cycles are captured`() {
        val curve = listOf(
            sample(0, "100"),
            sample(1, "110"),    // peak 1
            sample(2, "100"),    // trough 1 (-9.09%)
            sample(3, "110"),    // recovery 1
            sample(4, "120"),    // peak 2
            sample(5, "108"),    // trough 2 (-10%)
            sample(6, "121"),    // recovery 2
        )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(2)
    }

    @Test
    fun `open drawdown at end is marked ongoing`() {
        val curve = listOf(
            sample(0, "100"),
            sample(1, "120"),    // peak
            sample(2, "100"),    // trough so far
            sample(3, "105"),    // partial recovery, no full recovery
        )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(1)
        assertThat(periods[0].ongoing).isTrue
        assertThat(periods[0].recoveryTimestamp).isNull()
    }

    @Test
    fun `drawdowns shallower than threshold are filtered`() {
        val curve = listOf(
            sample(0, "100"),
            sample(1, "100.5"),   // peak
            sample(2, "100.0"),   // -0.5% — below 1% threshold
            sample(3, "100.5"),
        )
        assertThat(DrawdownAnalyzer.analyze(curve, threshold)).isEmpty()
    }

    @Test
    fun `output is sorted by depth descending`() {
        val curve = listOf(
            sample(0, "100"),
            sample(1, "110"), sample(2, "108"), sample(3, "110"),  // -1.8%
            sample(4, "120"), sample(5, "108"), sample(6, "121"),  // -10%
            sample(7, "130"), sample(8, "126"), sample(9, "131"),  // -3.07%
        )
        val periods = DrawdownAnalyzer.analyze(curve, threshold)
        assertThat(periods).hasSize(3)
        // sorted by depth descending (deepest first)
        assertThat(periods[0].depthPct).isLessThan(periods[1].depthPct)
        assertThat(periods[1].depthPct).isLessThan(periods[2].depthPct)
    }
}
```

- [ ] **Step 3: Run tests, verify failures**

Run: `./gradlew test --tests com.qkt.backtest.metrics.DrawdownAnalyzerTest`
Expected: all FAIL — `DrawdownAnalyzer` does not exist.

- [ ] **Step 4: Implement `DrawdownAnalyzer`**

```kotlin
// src/main/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzer.kt
package com.qkt.backtest.metrics

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object DrawdownAnalyzer {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)

    fun analyze(
        curve: List<EquitySample>,
        threshold: BigDecimal,
    ): List<DrawdownPeriod> {
        if (curve.size < 2) return emptyList()

        val out = mutableListOf<DrawdownPeriod>()
        var peakTs = curve[0].timestamp
        var peakEq = curve[0].equity
        var troughTs = peakTs
        var troughEq = peakEq
        var inDrawdown = false

        for (i in 1 until curve.size) {
            val s = curve[i]
            if (s.equity > peakEq) {
                if (inDrawdown) {
                    // Recovery: emit the period if recovery happened (s.equity >= prior peak).
                    val depth = depth(peakEq, troughEq)
                    if (depth <= threshold) {
                        out.add(
                            DrawdownPeriod(
                                peakTimestamp = peakTs,
                                peakEquity = peakEq,
                                troughTimestamp = troughTs,
                                troughEquity = troughEq,
                                recoveryTimestamp = s.timestamp,
                                depthPct = depth,
                                durationMs = s.timestamp - peakTs,
                                ongoing = false,
                            ),
                        )
                    }
                    inDrawdown = false
                }
                peakTs = s.timestamp
                peakEq = s.equity
                troughTs = s.timestamp
                troughEq = s.equity
            } else if (s.equity < peakEq) {
                if (!inDrawdown || s.equity < troughEq) {
                    troughTs = s.timestamp
                    troughEq = s.equity
                }
                inDrawdown = true
            }
        }

        if (inDrawdown) {
            val depth = depth(peakEq, troughEq)
            if (depth <= threshold) {
                val end = curve.last().timestamp
                out.add(
                    DrawdownPeriod(
                        peakTimestamp = peakTs,
                        peakEquity = peakEq,
                        troughTimestamp = troughTs,
                        troughEquity = troughEq,
                        recoveryTimestamp = null,
                        depthPct = depth,
                        durationMs = end - peakTs,
                        ongoing = true,
                    ),
                )
            }
        }

        return out.sortedBy { it.depthPct } // ascending = deepest first (most negative first)
    }

    private fun depth(
        peak: BigDecimal,
        trough: BigDecimal,
    ): BigDecimal {
        if (peak.signum() == 0) return BigDecimal.ZERO
        return trough.subtract(peak).divide(peak, mc)
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.backtest.metrics.DrawdownAnalyzerTest`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/DrawdownPeriod.kt \
        src/main/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzer.kt \
        src/test/kotlin/com/qkt/backtest/metrics/DrawdownAnalyzerTest.kt
git commit -m "feat(backtest): drawdown-period analyzer over equity curves"
```

---

## Task 2: `MonteCarlo` and `MonteCarloSummary`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/MonteCarloSummary.kt`
- Create: `src/main/kotlin/com/qkt/backtest/metrics/MonteCarlo.kt`
- Test: `src/test/kotlin/com/qkt/backtest/metrics/MonteCarloTest.kt`

- [ ] **Step 1: Create data classes**

```kotlin
// src/main/kotlin/com/qkt/backtest/MonteCarloSummary.kt
package com.qkt.backtest

import java.math.BigDecimal

data class EquityFanPoint(
    val tradeIndex: Int,
    val p5: BigDecimal,
    val p25: BigDecimal,
    val p50: BigDecimal,
    val p75: BigDecimal,
    val p95: BigDecimal,
)

data class MonteCarloSummary(
    val simulations: Int,
    val finalEquityP5: BigDecimal,
    val finalEquityP25: BigDecimal,
    val finalEquityP50: BigDecimal,
    val finalEquityP75: BigDecimal,
    val finalEquityP95: BigDecimal,
    val maxDrawdownP5: BigDecimal,
    val maxDrawdownP95: BigDecimal,
    val probabilityNegativeFinal: BigDecimal,
    val equityFanByTradeIndex: List<EquityFanPoint>,
)
```

- [ ] **Step 2: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/backtest/metrics/MonteCarloTest.kt
package com.qkt.backtest.metrics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MonteCarloTest {
    @Test
    fun `fixed seed produces deterministic percentiles`() {
        val returns = listOf("1", "-0.5", "2", "-1", "0.5", "3", "-2", "1.5").map { BigDecimal(it) }
        val a = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
        val b = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `all-positive returns yield non-negative final equity`() {
        val returns = listOf("1", "2", "3").map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 200, seed = 1L)
        assertThat(s.finalEquityP5).isGreaterThanOrEqualTo(BigDecimal("100"))
        assertThat(s.probabilityNegativeFinal).isEqualByComparingTo("0")
    }

    @Test
    fun `all-negative returns yield negative final equity bias`() {
        val returns = listOf("-1", "-2", "-3").map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 200, seed = 1L)
        assertThat(s.finalEquityP95).isLessThan(BigDecimal("100"))
        assertThat(s.probabilityNegativeFinal).isEqualByComparingTo("0")
        // probabilityNegativeFinal counts equity < 0; with starting=100 and small losses, may stay positive
    }

    @Test
    fun `equity fan length matches trade count`() {
        val returns = listOf("1", "-1", "2", "-2", "0.5").map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 100, seed = 7L)
        // Fan starts at index 0 (after first trade) and runs to size-1
        assertThat(s.equityFanByTradeIndex).hasSize(returns.size)
    }

    @Test
    fun `percentiles are ordered`() {
        val returns = listOf("1", "-1", "2", "-2", "0.5", "0.5", "-0.5", "1.5", "-1.5", "0.1")
            .map { BigDecimal(it) }
        val s = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
        assertThat(s.finalEquityP5).isLessThanOrEqualTo(s.finalEquityP25)
        assertThat(s.finalEquityP25).isLessThanOrEqualTo(s.finalEquityP50)
        assertThat(s.finalEquityP50).isLessThanOrEqualTo(s.finalEquityP75)
        assertThat(s.finalEquityP75).isLessThanOrEqualTo(s.finalEquityP95)
    }
}
```

- [ ] **Step 3: Run tests, verify failures**

Run: `./gradlew test --tests com.qkt.backtest.metrics.MonteCarloTest`
Expected: all FAIL — `MonteCarlo` does not exist.

- [ ] **Step 4: Implement `MonteCarlo`**

```kotlin
// src/main/kotlin/com/qkt/backtest/metrics/MonteCarlo.kt
package com.qkt.backtest.metrics

import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.MonteCarloSummary
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random

object MonteCarlo {
    private val mc = MathContext(16, RoundingMode.HALF_EVEN)

    fun run(
        tradeReturns: List<BigDecimal>,
        startingEquity: BigDecimal,
        simulations: Int,
        seed: Long,
    ): MonteCarloSummary {
        require(simulations > 0) { "simulations must be > 0: $simulations" }
        require(tradeReturns.isNotEmpty()) { "tradeReturns must not be empty" }
        val rng = Random(seed)
        val n = tradeReturns.size
        val curves = Array(simulations) { Array(n) { BigDecimal.ZERO } }
        val finalEquities = Array(simulations) { BigDecimal.ZERO }
        val maxDrawdowns = Array(simulations) { BigDecimal.ZERO }

        for (sim in 0 until simulations) {
            var equity = startingEquity
            var peak = startingEquity
            var maxDd = BigDecimal.ZERO
            for (i in 0 until n) {
                val pick = rng.nextInt(n)
                equity = equity.add(tradeReturns[pick])
                curves[sim][i] = equity
                if (equity > peak) peak = equity
                if (peak.signum() != 0) {
                    val dd = equity.subtract(peak).divide(peak, mc)
                    if (dd < maxDd) maxDd = dd
                }
            }
            finalEquities[sim] = equity
            maxDrawdowns[sim] = maxDd
        }

        val sortedFinals = finalEquities.toList().sorted()
        val sortedDds = maxDrawdowns.toList().sorted()

        val fan = (0 until n).map { i ->
            val column = (0 until simulations).map { curves[it][i] }.sorted()
            EquityFanPoint(
                tradeIndex = i,
                p5 = column.percentile(0.05),
                p25 = column.percentile(0.25),
                p50 = column.percentile(0.50),
                p75 = column.percentile(0.75),
                p95 = column.percentile(0.95),
            )
        }

        val negativeCount = finalEquities.count { it.signum() < 0 }
        val probNeg = BigDecimal(negativeCount).divide(BigDecimal(simulations), mc)

        return MonteCarloSummary(
            simulations = simulations,
            finalEquityP5 = sortedFinals.percentile(0.05),
            finalEquityP25 = sortedFinals.percentile(0.25),
            finalEquityP50 = sortedFinals.percentile(0.50),
            finalEquityP75 = sortedFinals.percentile(0.75),
            finalEquityP95 = sortedFinals.percentile(0.95),
            maxDrawdownP5 = sortedDds.percentile(0.05),
            maxDrawdownP95 = sortedDds.percentile(0.95),
            probabilityNegativeFinal = probNeg,
            equityFanByTradeIndex = fan,
        )
    }

    private fun List<BigDecimal>.percentile(p: Double): BigDecimal {
        if (isEmpty()) return BigDecimal.ZERO
        val idx = (p * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[idx]
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.backtest.metrics.MonteCarloTest`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/MonteCarloSummary.kt \
        src/main/kotlin/com/qkt/backtest/metrics/MonteCarlo.kt \
        src/test/kotlin/com/qkt/backtest/metrics/MonteCarloTest.kt
git commit -m "feat(backtest): bootstrap monte carlo simulator with deterministic seed"
```

---

## Task 3: SVG chart helpers

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/report/SvgChart.kt`
- Test: `src/test/kotlin/com/qkt/backtest/report/SvgChartTest.kt`

Three pure functions that emit SVG strings. No external graphics library.

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/backtest/report/SvgChartTest.kt
package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SvgChartTest {
    @Test
    fun `lineChart starts with svg tag and has viewBox`() {
        val pts = listOf(0L to BigDecimal("100"), 1L to BigDecimal("110"), 2L to BigDecimal("105"))
        val svg = SvgChart.lineChart(pts, width = 800, height = 400, title = "equity")
        assertThat(svg).startsWith("<svg")
        assertThat(svg).contains("viewBox=\"0 0 800 400\"")
        assertThat(svg).contains("equity")
    }

    @Test
    fun `lineChart with empty points still produces valid SVG`() {
        val svg = SvgChart.lineChart(emptyList(), width = 400, height = 200, title = "empty")
        assertThat(svg).startsWith("<svg")
        assertThat(svg).endsWith("</svg>")
    }

    @Test
    fun `lineChartWithUnderwater shades drawdown regions`() {
        val curve = listOf(
            EquitySample(0L, BigDecimal("100")),
            EquitySample(1L, BigDecimal("120")),
            EquitySample(2L, BigDecimal("108")),
            EquitySample(3L, BigDecimal("121")),
        )
        val dds = listOf(
            DrawdownPeriod(
                peakTimestamp = 1L,
                peakEquity = BigDecimal("120"),
                troughTimestamp = 2L,
                troughEquity = BigDecimal("108"),
                recoveryTimestamp = 3L,
                depthPct = BigDecimal("-0.10"),
                durationMs = 2L,
                ongoing = false,
            ),
        )
        val svg = SvgChart.lineChartWithUnderwater(curve, dds, width = 800, height = 400)
        assertThat(svg).contains("<rect")
    }

    @Test
    fun `fanChart renders five percentile bands`() {
        val fan = (0..9).map { i ->
            EquityFanPoint(
                tradeIndex = i,
                p5 = BigDecimal(i * 5),
                p25 = BigDecimal(i * 7),
                p50 = BigDecimal(i * 10),
                p75 = BigDecimal(i * 13),
                p95 = BigDecimal(i * 15),
            )
        }
        val svg = SvgChart.fanChart(fan, width = 800, height = 400)
        assertThat(svg).contains("<svg")
        // Two filled bands (p5–p95 outer, p25–p75 inner) + one median line
        assertThat(svg.split("<path").size - 1).isGreaterThanOrEqualTo(3)
    }
}
```

- [ ] **Step 2: Run tests, verify failures**

Run: `./gradlew test --tests com.qkt.backtest.report.SvgChartTest`
Expected: all FAIL — class does not exist.

- [ ] **Step 3: Implement `SvgChart`**

```kotlin
// src/main/kotlin/com/qkt/backtest/report/SvgChart.kt
package com.qkt.backtest.report

import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.EquitySample
import java.math.BigDecimal

object SvgChart {
    private const val PADDING_LEFT = 60
    private const val PADDING_BOTTOM = 30
    private const val PADDING_TOP = 20
    private const val PADDING_RIGHT = 20

    fun lineChart(
        points: List<Pair<Long, BigDecimal>>,
        width: Int,
        height: Int,
        title: String,
    ): String {
        if (points.isEmpty()) return emptySvg(width, height, title)
        val xs = points.map { it.first }
        val ys = points.map { it.second.toDouble() }
        val xMin = xs.min().toDouble()
        val xMax = xs.max().toDouble()
        val yMin = ys.min()
        val yMax = ys.max()
        val poly = points.joinToString(" ") { (t, e) ->
            "${scaleX(t.toDouble(), xMin, xMax, width)},${scaleY(e.toDouble(), yMin, yMax, height)}"
        }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">")
            append("<title>$title</title>")
            appendAxes(width, height, yMin, yMax)
            append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"1.5\" points=\"$poly\"/>")
            append("</svg>")
        }
    }

    fun lineChartWithUnderwater(
        curve: List<EquitySample>,
        drawdowns: List<DrawdownPeriod>,
        width: Int,
        height: Int,
    ): String {
        if (curve.isEmpty()) return emptySvg(width, height, "equity")
        val xMin = curve.first().timestamp.toDouble()
        val xMax = curve.last().timestamp.toDouble()
        val yMin = curve.minOf { it.equity }.toDouble()
        val yMax = curve.maxOf { it.equity }.toDouble()
        val poly = curve.joinToString(" ") { s ->
            "${scaleX(s.timestamp.toDouble(), xMin, xMax, width)}," +
                "${scaleY(s.equity.toDouble(), yMin, yMax, height)}"
        }
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">")
            append("<title>equity with drawdowns</title>")
            for (dd in drawdowns) {
                val x0 = scaleX(dd.peakTimestamp.toDouble(), xMin, xMax, width)
                val recovery = dd.recoveryTimestamp?.toDouble() ?: xMax
                val x1 = scaleX(recovery, xMin, xMax, width)
                append(
                    "<rect x=\"$x0\" y=\"$PADDING_TOP\" width=\"${x1 - x0}\" " +
                        "height=\"${height - PADDING_TOP - PADDING_BOTTOM}\" fill=\"#ff7f7f\" opacity=\"0.15\"/>",
                )
            }
            appendAxes(width, height, yMin, yMax)
            append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"1.5\" points=\"$poly\"/>")
            append("</svg>")
        }
    }

    fun fanChart(
        fan: List<EquityFanPoint>,
        width: Int,
        height: Int,
    ): String {
        if (fan.isEmpty()) return emptySvg(width, height, "monte-carlo")
        val xMin = 0.0
        val xMax = (fan.size - 1).toDouble()
        val yMin = fan.minOf { it.p5 }.toDouble()
        val yMax = fan.maxOf { it.p95 }.toDouble()

        fun pathBetween(
            upper: List<BigDecimal>,
            lower: List<BigDecimal>,
        ): String {
            val sb = StringBuilder("M")
            for ((i, v) in upper.withIndex()) {
                sb.append(scaleX(i.toDouble(), xMin, xMax, width))
                sb.append(',')
                sb.append(scaleY(v.toDouble(), yMin, yMax, height))
                sb.append(' ')
            }
            sb.append("L")
            for ((i, v) in lower.withIndex().reversed()) {
                sb.append(scaleX(i.toDouble(), xMin, xMax, width))
                sb.append(',')
                sb.append(scaleY(v.toDouble(), yMin, yMax, height))
                sb.append(' ')
            }
            sb.append("Z")
            return sb.toString()
        }

        val outer = pathBetween(fan.map { it.p95 }, fan.map { it.p5 })
        val inner = pathBetween(fan.map { it.p75 }, fan.map { it.p25 })
        val median = fan.joinToString(" ") { p ->
            "${scaleX(p.tradeIndex.toDouble(), xMin, xMax, width)}," +
                "${scaleY(p.p50.toDouble(), yMin, yMax, height)}"
        }

        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">")
            append("<title>monte-carlo fan</title>")
            appendAxes(width, height, yMin, yMax)
            append("<path d=\"$outer\" fill=\"#1f77b4\" opacity=\"0.15\"/>")
            append("<path d=\"$inner\" fill=\"#1f77b4\" opacity=\"0.30\"/>")
            append("<polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"1.5\" points=\"$median\"/>")
            append("</svg>")
        }
    }

    private fun emptySvg(
        width: Int,
        height: Int,
        title: String,
    ): String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\">" +
            "<title>$title</title><text x=\"$PADDING_LEFT\" y=\"${height / 2}\">no data</text></svg>"

    private fun StringBuilder.appendAxes(
        width: Int,
        height: Int,
        yMin: Double,
        yMax: Double,
    ) {
        append(
            "<line x1=\"$PADDING_LEFT\" y1=\"${height - PADDING_BOTTOM}\" " +
                "x2=\"${width - PADDING_RIGHT}\" y2=\"${height - PADDING_BOTTOM}\" stroke=\"#888\"/>",
        )
        append(
            "<line x1=\"$PADDING_LEFT\" y1=\"$PADDING_TOP\" " +
                "x2=\"$PADDING_LEFT\" y2=\"${height - PADDING_BOTTOM}\" stroke=\"#888\"/>",
        )
        append(
            "<text x=\"5\" y=\"${PADDING_TOP + 5}\" font-size=\"10\">${"%.4g".format(yMax)}</text>",
        )
        append(
            "<text x=\"5\" y=\"${height - PADDING_BOTTOM}\" font-size=\"10\">${"%.4g".format(yMin)}</text>",
        )
    }

    private fun scaleX(
        v: Double,
        min: Double,
        max: Double,
        width: Int,
    ): Double {
        if (max == min) return PADDING_LEFT.toDouble()
        val range = (width - PADDING_LEFT - PADDING_RIGHT)
        return PADDING_LEFT + (v - min) / (max - min) * range
    }

    private fun scaleY(
        v: Double,
        min: Double,
        max: Double,
        height: Int,
    ): Double {
        if (max == min) return (height - PADDING_BOTTOM).toDouble()
        val range = (height - PADDING_TOP - PADDING_BOTTOM)
        return PADDING_TOP + (max - v) / (max - min) * range
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew test --tests com.qkt.backtest.report.SvgChartTest`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/SvgChart.kt \
        src/test/kotlin/com/qkt/backtest/report/SvgChartTest.kt
git commit -m "feat(backtest): pure-SVG chart helpers for line and fan plots"
```

---

## Task 4: Extend `PerformanceReport` with DD-periods + MonteCarlo

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/report/ReportSerializer.kt`

- [ ] **Step 1: Extend `PerformanceReport`**

```kotlin
data class PerformanceReport(
    /* existing fields */,
    val drawdownPeriods: List<DrawdownPeriod>,
    val monteCarlo: MonteCarloSummary?,
)
```

- [ ] **Step 2: Update `ReportBuilder` to populate the new fields**

Read the current `ReportBuilder` to find where it constructs `PerformanceReport`. Inside that method, add:

```kotlin
val drawdownPeriods = DrawdownAnalyzer.analyze(equityCurve, BigDecimal("-0.01"))
val tradeReturns = trades.map { it.realized }
val monteCarlo =
    if (trades.size >= 30) {
        MonteCarlo.run(
            tradeReturns = tradeReturns,
            startingEquity = startingEquity,
            simulations = 1000,
            seed = 42L,
        )
    } else {
        null
    }
return PerformanceReport(
    /* existing args */,
    drawdownPeriods = drawdownPeriods,
    monteCarlo = monteCarlo,
)
```

> The `startingEquity` and `trades` parameters depend on `ReportBuilder`'s current signature. Read the file and adapt the variable names. The thresholds and seeds match `HtmlReportConfig` defaults — Task 7 wires them through configurably.

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL. If existing tests fail because of the new required fields, update them to pass `drawdownPeriods = emptyList()` and `monteCarlo = null` (or use a default-arg copy if the data class permits).

> Suggested approach: give `PerformanceReport.drawdownPeriods` and `monteCarlo` defaults of `emptyList()` and `null` so existing test constructors continue to work. The defaults are semantically correct (no DDs, no MC computed) for stub fixtures.

- [ ] **Step 4: Update `ReportSerializer` to emit new fields in JSON**

Add a `renderDrawdownPeriods` and a `renderMonteCarlo` helper, then include them in `renderReport`. Mirror the existing field-rendering pattern in `BacktestReportWriter.renderReport`.

- [ ] **Step 5: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/PerformanceReport.kt \
        src/main/kotlin/com/qkt/backtest/ReportBuilder.kt \
        src/main/kotlin/com/qkt/backtest/report/ReportSerializer.kt
git commit -m "feat(backtest): PerformanceReport carries drawdown periods and monte carlo"
```

---

## Task 5: Risk-per-order side channel in `OrderManager`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`

The risk amount is computed when a bracket signal lands. We expose a lookup keyed by client order id.

- [ ] **Step 1: Read existing `OrderManager` to find bracket evaluation site**

Run: `grep -n "outerBracket\|stopLoss\|stopPrice\|fun submit" src/main/kotlin/com/qkt/app/OrderManager.kt | head -10`

- [ ] **Step 2: Add the side-channel map and accessor**

Inside `OrderManager`:

```kotlin
private val riskByClientOrderId = java.util.concurrent.ConcurrentHashMap<String, java.math.BigDecimal>()

fun riskUsdFor(clientOrderId: String): java.math.BigDecimal? = riskByClientOrderId[clientOrderId]

internal fun recordRisk(
    clientOrderId: String,
    quantity: java.math.BigDecimal,
    entry: java.math.BigDecimal,
    stop: java.math.BigDecimal,
) {
    val risk = entry.subtract(stop).abs().multiply(quantity)
    riskByClientOrderId[clientOrderId] = risk
}
```

- [ ] **Step 3: Call `recordRisk` from the bracket-evaluation path**

When the order manager evaluates a bracketed entry and computes the SL price, call:

```kotlin
recordRisk(clientOrderId = entryRequest.id, quantity = entryRequest.quantity, entry = entryPrice, stop = slPrice)
```

> The exact call site depends on the existing bracket flow. Find where SL price is computed; insert the call right there.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. No tests yet — Task 6 exercises this end-to-end.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "feat(app): OrderManager exposes per-order risk-USD lookup"
```

---

## Task 6: Thread `riskUsd` into `TradeRecord`

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/TradeRecord.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt`

- [ ] **Step 1: Add `riskUsd` to `TradeRecord`**

```kotlin
data class TradeRecord(
    val trade: Trade,
    val strategyId: String,
    val realized: BigDecimal,
    val riskUsd: BigDecimal? = null,
)
```

- [ ] **Step 2: Populate from `OrderManager` when constructing in `Backtest`**

Find where `Backtest` builds `TradeRecord` (search for `TradeRecord(`). Read the surrounding code; locate the `OrderManager` reference. Pass `riskUsd = orderManager.riskUsdFor(trade.orderId)`:

```kotlin
val record = TradeRecord(
    trade = trade,
    strategyId = strategyId,
    realized = realized,
    riskUsd = pipeline.orderManager.riskUsdFor(trade.orderId),
)
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. The default `riskUsd = null` keeps existing test constructors working.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/TradeRecord.kt src/main/kotlin/com/qkt/backtest/Backtest.kt
git commit -m "feat(backtest): trade records carry per-trade risk amount"
```

---

## Task 7: `HtmlReportConfig` and `HtmlReportWriter`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/report/HtmlReportConfig.kt`
- Create: `src/main/kotlin/com/qkt/backtest/report/HtmlReportWriter.kt`
- Test: `src/test/kotlin/com/qkt/backtest/report/HtmlReportWriterTest.kt`

- [ ] **Step 1: Create `HtmlReportConfig`**

```kotlin
package com.qkt.backtest.report

import java.math.BigDecimal

data class HtmlReportConfig(
    val tradeTableHead: Int = 200,
    val tradeTableTail: Int = 200,
    val drawdownThresholdPct: BigDecimal = BigDecimal("-0.01"),
    val monteCarloSimulations: Int = 1000,
    val monteCarloSeed: Long = 42L,
    val minTradesForMonteCarlo: Int = 30,
)
```

- [ ] **Step 2: Write failing test**

```kotlin
// src/test/kotlin/com/qkt/backtest/report/HtmlReportWriterTest.kt
package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.EquityFanPoint
import com.qkt.backtest.EquitySample
import com.qkt.backtest.MonteCarloSummary
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.TradeRecord
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class HtmlReportWriterTest {
    @Test
    fun `writes a self-contained html with all sections`(@TempDir tmp: Path) {
        val report = stubReport()
        val result = stubResult(report)
        HtmlReportWriter(HtmlReportConfig()).write(result, tmp.resolve("report.html"))

        val html = java.nio.file.Files.readString(tmp.resolve("report.html"))
        assertThat(html).startsWith("<!doctype html>")
        assertThat(html).contains("<svg")                       // at least one chart
        assertThat(html).contains("Drawdown periods")           // section header
        assertThat(html).contains("Monte Carlo")                // section header
        assertThat(html).contains("Trades")                     // section header
        assertThat(html).contains("riskUsd")                    // trade-table column
        assertThat(html).doesNotContain("<script")              // no JS
    }

    private fun stubReport(): PerformanceReport =
        PerformanceReport(
            realizedTotal = BigDecimal("100"),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal("100"),
            tradeCount = 50,
            winRate = BigDecimal("0.6"),
            maxDrawdown = BigDecimal("-0.05"),
            profitFactor = BigDecimal("1.5"),
            avgWin = BigDecimal("3"),
            avgLoss = BigDecimal("-2"),
            largestWin = BigDecimal("10"),
            largestLoss = BigDecimal("-7"),
            maxConsecutiveLosses = 3,
            sharpeRatio = BigDecimal("1.2"),
            calmarRatio = BigDecimal("0.8"),
            equityCurve =
                listOf(
                    EquitySample(0L, BigDecimal("100")),
                    EquitySample(1L, BigDecimal("110")),
                    EquitySample(2L, BigDecimal("105")),
                    EquitySample(3L, BigDecimal("115")),
                ),
            drawdownPeriods =
                listOf(
                    DrawdownPeriod(1L, BigDecimal("110"), 2L, BigDecimal("105"), 3L,
                        BigDecimal("-0.045"), 2L, false),
                ),
            monteCarlo =
                MonteCarloSummary(
                    simulations = 100,
                    finalEquityP5 = BigDecimal("80"),
                    finalEquityP25 = BigDecimal("95"),
                    finalEquityP50 = BigDecimal("110"),
                    finalEquityP75 = BigDecimal("120"),
                    finalEquityP95 = BigDecimal("140"),
                    maxDrawdownP5 = BigDecimal("-0.02"),
                    maxDrawdownP95 = BigDecimal("-0.15"),
                    probabilityNegativeFinal = BigDecimal("0.05"),
                    equityFanByTradeIndex =
                        (0..9).map {
                            EquityFanPoint(it,
                                BigDecimal(80 + it),
                                BigDecimal(95 + it),
                                BigDecimal(105 + it),
                                BigDecimal(115 + it),
                                BigDecimal(135 + it),
                            )
                        },
                ),
        )

    private fun stubResult(report: PerformanceReport): BacktestResult =
        BacktestResult(
            cadence = SampleCadence.PER_TICK,
            global = report,
            perStrategy = mapOf("test" to report),
            trades =
                listOf(
                    TradeRecord(
                        Trade("o-1", "BTCUSDT", BigDecimal("100"), BigDecimal("1"),
                            com.qkt.common.Side.BUY, 0L),
                        "test",
                        BigDecimal("5"),
                        BigDecimal("10"),
                    ),
                ),
            rejections = emptyList(),
        )
}
```

> The exact `BacktestResult` fields and `Trade` constructor depend on the project; read the surrounding tests if the constructor doesn't match.

- [ ] **Step 3: Implement `HtmlReportWriter`**

```kotlin
// src/main/kotlin/com/qkt/backtest/report/HtmlReportWriter.kt
package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.DrawdownPeriod
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.TradeRecord
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class HtmlReportWriter(
    private val config: HtmlReportConfig = HtmlReportConfig(),
) {
    fun write(
        result: BacktestResult,
        path: Path,
    ) {
        Files.writeString(path, render(result))
    }

    fun render(result: BacktestResult): String {
        val sb = StringBuilder()
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<title>qkt backtest report</title>")
        sb.append("<style>${css()}</style></head><body>")

        sb.append("<header><h1>qkt backtest report</h1></header>")
        sb.append("<section class=\"headline\">")
        sb.append(headlineCards(result.global))
        sb.append("</section>")

        sb.append("<section class=\"equity\"><h2>Equity</h2>")
        sb.append(
            SvgChart.lineChartWithUnderwater(
                curve = result.global.equityCurve,
                drawdowns = result.global.drawdownPeriods,
                width = 1000,
                height = 360,
            ),
        )
        sb.append("</section>")

        sb.append("<section class=\"drawdowns\"><h2>Drawdown periods</h2>")
        sb.append(drawdownTable(result.global.drawdownPeriods))
        sb.append("</section>")

        sb.append("<section class=\"trade-stats\"><h2>Trade statistics</h2>")
        sb.append(tradeStatsTable(result.global))
        sb.append("</section>")

        sb.append("<section class=\"trades\"><h2>Trades</h2>")
        sb.append(tradesTable(result.trades))
        sb.append("</section>")

        sb.append("<section class=\"monte-carlo\"><h2>Monte Carlo</h2>")
        sb.append(monteCarloSection(result.global))
        sb.append("</section>")

        sb.append("<section class=\"rejections\"><h2>Rejections</h2>")
        sb.append("<p>${result.rejections.size} rejections (see rejections.csv).</p>")
        sb.append("</section>")

        sb.append("<footer><p>Generated by qkt</p></footer>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun css(): String =
        """
        body { font-family: system-ui, sans-serif; margin: 24px; color: #222; }
        h1 { margin: 0 0 8px 0; }
        section { margin: 24px 0; }
        .headline { display: flex; gap: 12px; flex-wrap: wrap; }
        .card { padding: 12px 16px; border: 1px solid #ddd; border-radius: 6px; min-width: 140px; }
        .card .label { font-size: 12px; color: #666; }
        .card .value { font-size: 22px; font-weight: 600; }
        .pos { color: #2a7d2a; } .neg { color: #b22; }
        table { border-collapse: collapse; width: 100%; font-size: 13px; }
        th, td { border-bottom: 1px solid #eee; padding: 6px 8px; text-align: right; }
        th:first-child, td:first-child { text-align: left; }
        @media print { .rejections { display: none; } section { page-break-inside: avoid; } }
        """.trimIndent()

    private fun headlineCards(r: PerformanceReport): String {
        fun card(label: String, value: String, classes: String = "") =
            "<div class=\"card $classes\"><div class=\"label\">$label</div>" +
                "<div class=\"value\">$value</div></div>"
        return buildString {
            append(card("Total PnL", r.totalPnL.toPlainString(),
                if (r.totalPnL.signum() >= 0) "pos" else "neg"))
            append(card("Trades", r.tradeCount.toString()))
            append(card("Win rate", r.winRate.toPlainString()))
            append(card("Sharpe", r.sharpeRatio?.toPlainString() ?: "n/a"))
            append(card("Calmar", r.calmarRatio?.toPlainString() ?: "n/a"))
            append(card("Max DD", r.maxDrawdown.toPlainString(), "neg"))
            append(card("Profit factor", r.profitFactor?.toPlainString() ?: "n/a"))
        }
    }

    private fun drawdownTable(periods: List<DrawdownPeriod>): String {
        if (periods.isEmpty()) return "<p>No drawdowns above threshold.</p>"
        return buildString {
            append("<table><thead><tr>")
            append("<th>Peak</th><th>Trough</th><th>Recovery</th><th>Depth</th>")
            append("<th>Duration ms</th><th>Status</th></tr></thead><tbody>")
            for (p in periods) {
                append("<tr><td>${p.peakTimestamp}</td><td>${p.troughTimestamp}</td>")
                append("<td>${p.recoveryTimestamp ?: "ongoing"}</td>")
                append("<td>${p.depthPct.toPlainString()}</td>")
                append("<td>${p.durationMs}</td>")
                append("<td>${if (p.ongoing) "ongoing" else "recovered"}</td></tr>")
            }
            append("</tbody></table>")
        }
    }

    private fun tradeStatsTable(r: PerformanceReport): String =
        buildString {
            append("<table><tbody>")
            append("<tr><td>Average win</td><td>${r.avgWin.toPlainString()}</td></tr>")
            append("<tr><td>Average loss</td><td>${r.avgLoss.toPlainString()}</td></tr>")
            append("<tr><td>Largest win</td><td>${r.largestWin.toPlainString()}</td></tr>")
            append("<tr><td>Largest loss</td><td>${r.largestLoss.toPlainString()}</td></tr>")
            append("<tr><td>Max consecutive losses</td><td>${r.maxConsecutiveLosses}</td></tr>")
            append("</tbody></table>")
        }

    private fun tradesTable(trades: List<TradeRecord>): String {
        val sample =
            if (trades.size <= config.tradeTableHead + config.tradeTableTail) {
                trades
            } else {
                trades.take(config.tradeTableHead) + trades.takeLast(config.tradeTableTail)
            }
        return buildString {
            append("<table><thead><tr>")
            append("<th>Timestamp</th><th>Strategy</th><th>Symbol</th><th>Side</th>")
            append("<th>Qty</th><th>Price</th><th>riskUsd</th><th>Realized</th>")
            append("</tr></thead><tbody>")
            for (r in sample) {
                append("<tr>")
                append("<td>${r.trade.timestamp}</td>")
                append("<td>${r.strategyId}</td>")
                append("<td>${r.trade.symbol}</td>")
                append("<td>${r.trade.side}</td>")
                append("<td>${r.trade.quantity.toPlainString()}</td>")
                append("<td>${r.trade.price.toPlainString()}</td>")
                append("<td>${r.riskUsd?.toPlainString() ?: "n/a"}</td>")
                append("<td>${r.realized.toPlainString()}</td>")
                append("</tr>")
            }
            append("</tbody></table>")
            if (sample.size < trades.size) {
                append("<p>Showing first ${config.tradeTableHead} and last ${config.tradeTableTail} ")
                append("of ${trades.size} trades. Full list in trades.csv.</p>")
            }
        }
    }

    private fun monteCarloSection(r: PerformanceReport): String {
        val mc = r.monteCarlo ?: return "<p>Insufficient trades for Monte Carlo " +
            "(need ${config.minTradesForMonteCarlo}+).</p>"
        return buildString {
            append("<table><tbody>")
            append("<tr><td>Simulations</td><td>${mc.simulations}</td></tr>")
            append("<tr><td>P5 final equity</td><td>${mc.finalEquityP5.toPlainString()}</td></tr>")
            append("<tr><td>P50 final equity</td><td>${mc.finalEquityP50.toPlainString()}</td></tr>")
            append("<tr><td>P95 final equity</td><td>${mc.finalEquityP95.toPlainString()}</td></tr>")
            append("<tr><td>P5 max DD</td><td>${mc.maxDrawdownP5.toPlainString()}</td></tr>")
            append("<tr><td>P95 max DD</td><td>${mc.maxDrawdownP95.toPlainString()}</td></tr>")
            append("<tr><td>P(final &lt; 0)</td><td>${mc.probabilityNegativeFinal.toPlainString()}</td></tr>")
            append("</tbody></table>")
            append(SvgChart.fanChart(mc.equityFanByTradeIndex, width = 1000, height = 360))
        }
    }
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew test --tests com.qkt.backtest.report.HtmlReportWriterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/HtmlReportConfig.kt \
        src/main/kotlin/com/qkt/backtest/report/HtmlReportWriter.kt \
        src/test/kotlin/com/qkt/backtest/report/HtmlReportWriterTest.kt
git commit -m "feat(backtest): HtmlReportWriter renders self-contained report.html"
```

---

## Task 8: Wire HTML writer + `riskUsd` CSV column into `BacktestReportWriter`

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt`

- [ ] **Step 1: Update `renderTradesCsv` to include `riskUsd` column**

```kotlin
private fun renderTradesCsv(trades: List<TradeRecord>): String {
    val sb = StringBuilder("timestamp,strategy,symbol,side,quantity,price,realized,riskUsd,brokerOrderId\n")
    for (r in trades) {
        sb
            .append(r.trade.timestamp).append(',')
            .append(r.strategyId).append(',')
            .append(r.trade.symbol).append(',')
            .append(r.trade.side).append(',')
            .append(r.trade.quantity.toPlainString()).append(',')
            .append(r.trade.price.toPlainString()).append(',')
            .append(r.realized.toPlainString()).append(',')
            .append(r.riskUsd?.toPlainString() ?: "").append(',')
            .append(r.trade.orderId).append('\n')
    }
    return sb.toString()
}
```

- [ ] **Step 2: Append `report.html` write to `write(result)`**

After the existing CSV/JSON writes:

```kotlin
HtmlReportWriter().write(result, dir.resolve("report.html"))
```

> If the report writer should use a configurable `HtmlReportConfig`, accept one as a `BacktestReportWriter` constructor parameter with a sensible default. Alternatively keep the default config and document customization in the changelog.

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt
git commit -m "feat(backtest): emit report.html alongside json csv with riskUsd column"
```

---

## Task 9: Version bump + README + phase changelog + backlog

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt` (`VERSION = "0.18.0"`)
- Modify: `README.md`
- Create: `docs/phases/phase-16.md`
- Modify: `docs/backlog.md`

- [ ] **Step 1: Bump version**

```kotlin
const val VERSION: String = "0.18.0"
```

- [ ] **Step 2: README**

Update the latest-release line to `v0.18.0`. Add under the existing list:

```
- **Backtest HTML report** — single self-contained `report.html` per run with embedded SVG equity + drawdown chart, Monte Carlo fan, drawdown-period table, per-trade risk column ([phase 16](docs/phases/phase-16.md)).
```

- [ ] **Step 3: Phase changelog**

Create `docs/phases/phase-16.md` with the seven required sections per qkt SKILL §6: Summary, What's new, Migration, Usage cookbook (cover: open report.html in browser; reading the headline cards; interpreting MC fan; spotting an ongoing DD; reading per-trade risk; printing to PDF), Testing patterns, Known limitations (slippage/spread/regime deferred, MC i.i.d. assumption, large trade table truncation), References (spec, plan).

- [ ] **Step 4: Update backlog**

In `docs/backlog.md`, flip the relevant items to `done`:

```
- `done` — Backtest fidelity audit + DD-days + Monte Carlo + per-trade risk in HTML report (see [phase 16](phases/phase-16.md))
- `done` — Backtest report HTML output (see [phase 16](phases/phase-16.md))
```

Leave "Backtest vs live execution parity" as `tbd` — that's a separate concern.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt README.md \
        docs/phases/phase-16.md docs/backlog.md
git commit -m "chore(cli): bump version to 0.18.0 and add phase 16 changelog"
```

---

## Task 10: Final precheck and merge

- [ ] **Step 1: Run precheck**

Run: `./scripts/precheck.sh`
Expected: All steps green.

- [ ] **Step 2: Verify commit log conventions**

Run: `git log --oneline main..HEAD`
Expected: every commit follows §3 conventions, no AI references, no emoji.

- [ ] **Step 3: Use `superpowers:finishing-a-development-branch`**

Announce and follow that skill to merge.

---

## Self-Review

**Spec coverage check:**
- HTML output (single self-contained file, no JS, browser-printable) — Tasks 7, 8
- Drawdown-period table with start/trough/recovery/depth/duration — Tasks 1 (analyzer), 7 (rendering)
- Monte Carlo fan + percentiles + probability of negative final — Tasks 2 (sim), 3 (fan SVG), 7 (rendering)
- Per-trade risk capture from bracket evaluation through to the report — Tasks 5, 6, 8
- Equity + drawdown chart — Tasks 3 (SVG), 7 (composition)
- Configuration knobs (head/tail truncation, threshold, MC sims/seed/min) — Task 7
- JSON shape includes new fields — Task 4
- Version bump + README + changelog + backlog — Task 9

**Placeholder scan:** Tasks 4, 5, 6, 8 contain explicit `>` notes flagging existing-code touchpoints the implementer must read before patching (the exact `ReportBuilder` signature, the bracket-evaluation site in `OrderManager`, the `TradeRecord` construction site in `Backtest`, the `BacktestReportWriter` config injection point). These are verification cues, not gaps. No `TBD`/`TODO`/"fill in later" markers remain in the plan body.

**Type consistency check:**
- `DrawdownPeriod` field order — Task 1 defines, Task 3 (SVG) reads `peakTimestamp`/`troughEquity`/`recoveryTimestamp`, Task 7 (HTML table) reads same. Consistent.
- `MonteCarloSummary.equityFanByTradeIndex: List<EquityFanPoint>` — Task 2 produces, Task 3 (fan chart) consumes, Task 7 renders. Consistent.
- `TradeRecord.riskUsd: BigDecimal?` — Task 6 defines, Task 7 (table) reads, Task 8 (CSV) reads. Consistent.
- `OrderManager.riskUsdFor(orderId): BigDecimal?` — Task 5 defines, Task 6 calls. Consistent.
- `HtmlReportConfig.minTradesForMonteCarlo` — Task 7 defines, Task 4 hard-codes 30 (matches default). Consistent for v1; future generalization is a follow-on.

**Open verifications during execution:**
- Exact `ReportBuilder` signature and where it constructs `PerformanceReport` (Task 4).
- Bracket-evaluation site in `OrderManager` where SL price is finalized (Task 5).
- `TradeRecord` construction site in `Backtest` (Task 6).
- `BacktestResult` and `Trade` constructors for the test fixture (Task 7).
- Decision on whether `BacktestReportWriter` accepts an `HtmlReportConfig` parameter or hardcodes the default (Task 8).
