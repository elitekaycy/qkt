# Phase 10 — Backtest Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the backtest into a serious reporting tool — equity curves, per-strategy attribution, full quant metric set (profit factor, Sharpe, Calmar), and JSON+CSV file output.

**Architecture:** Pure-compute metrics in `com.qkt.backtest.metrics`, single-subscriber `EquityCurveCollector` reads equity directly from `PnLCalculator`/`StrategyPnL` at the chosen cadence (default `CANDLE_CLOSE`), `ReportBuilder` assembles `PerformanceReport`, separated `BacktestReportWriter` emits JSON + CSV. Phase 9's drawdown semantics extended via `DrawdownTracker.fromCurve(samples)` so live and backtest agree.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 17 (`BigDecimal.sqrt`), Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase10-design.md`

**Branch:** `phase10-backtest-reporting` (already created and active).

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/backtest/
├── SampleCadence.kt
├── EquitySample.kt
├── PerformanceReport.kt
├── EquityCurveCollector.kt
├── ReportBuilder.kt
├── Backtest.kt                       # moved from app/
├── BacktestResult.kt                 # moved from app/
├── TradeRecord.kt                    # moved from app/
├── metrics/
│   ├── ProfitFactor.kt
│   ├── WinLossStats.kt
│   ├── Sharpe.kt
│   └── Calmar.kt
└── report/
    ├── BacktestReportWriter.kt
    └── ReportSerializer.kt

src/test/kotlin/com/qkt/backtest/
├── SampleCadenceTest.kt              (only if non-trivial; likely skip)
├── EquityCurveCollectorTest.kt
├── ReportBuilderTest.kt
├── BacktestEndToEndTest.kt
├── BacktestDeterminismTest.kt
├── BacktestTest.kt                   # moved from app/
├── BacktestFromStoreTest.kt          # moved from app/
├── BacktestFromSourceTest.kt         # moved from app/
├── BacktestWarmupTest.kt             # moved from app/
├── metrics/
│   ├── ProfitFactorTest.kt
│   ├── WinLossStatsTest.kt
│   ├── SharpeTest.kt
│   └── CalmarTest.kt
└── report/
    └── BacktestReportWriterTest.kt
```

### Modified files

- `src/main/kotlin/com/qkt/common/TradingCalendar.kt` — add `tradingPeriodsPerYear(window: TimeWindow): BigDecimal` with default-impl that errors.
- `src/main/kotlin/com/qkt/common/CryptoCalendar.kt` — override `tradingPeriodsPerYear`.
- `src/main/kotlin/com/qkt/risk/DrawdownTracker.kt` — add `companion object { fun fromCurve(...) }`.
- `src/main/kotlin/com/qkt/app/TradingPipeline.kt` — change `onFilled` signature to include `strategyId: String`.
- `src/main/kotlin/com/qkt/app/Main.kt` — update onFilled call, update `BacktestResult` field reads.
- `src/main/kotlin/com/qkt/app/LiveSession.kt` — update onFilled call.
- `src/main/kotlin/com/qkt/app/MaxAudit.kt` — update if it reads `BacktestResult` fields (verify in Task 13).
- All `*BacktestTest*.kt` files moved + updated for new package + new `BacktestResult` shape.

### Deleted files

After move:
- `src/main/kotlin/com/qkt/app/Backtest.kt`
- `src/main/kotlin/com/qkt/app/BacktestResult.kt`
- `src/main/kotlin/com/qkt/app/TradeRecord.kt`
- `src/test/kotlin/com/qkt/app/BacktestTest.kt`
- `src/test/kotlin/com/qkt/app/BacktestFromStoreTest.kt`
- `src/test/kotlin/com/qkt/app/BacktestFromSourceTest.kt`
- `src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt`

---

## Tasks

### Task 1: Add `tradingPeriodsPerYear` to TradingCalendar

**Files:**
- Modify: `src/main/kotlin/com/qkt/common/TradingCalendar.kt`
- Modify: `src/main/kotlin/com/qkt/common/CryptoCalendar.kt`
- Test: `src/test/kotlin/com/qkt/common/TradingCalendarTradingPeriodsPerYearTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/common/TradingCalendarTradingPeriodsPerYearTest.kt`:

```kotlin
package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingCalendarTradingPeriodsPerYearTest {
    @Test
    fun `crypto returns 525960 for 1 minute window`() {
        val periods = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.ONE_MINUTE)
        assertThat(periods).isEqualByComparingTo(BigDecimal("525960"))
    }

    @Test
    fun `crypto scales inversely with window size for 5 minute window`() {
        val periods = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.FIVE_MINUTES)
        assertThat(periods).isEqualByComparingTo(BigDecimal("105192"))
    }

    @Test
    fun `crypto returns 8766 for 1 hour window`() {
        val periods = TradingCalendar.crypto().tradingPeriodsPerYear(TimeWindow.ONE_HOUR)
        assertThat(periods).isEqualByComparingTo(BigDecimal("8766"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TradingCalendarTradingPeriodsPerYearTest*'`
Expected: FAIL — `tradingPeriodsPerYear` is not defined.

- [ ] **Step 3: Add interface method with default-impl that errors**

Edit `src/main/kotlin/com/qkt/common/TradingCalendar.kt` — add to the interface body before the companion:

```kotlin
import com.qkt.candles.TimeWindow
import java.math.BigDecimal

interface TradingCalendar {
    val name: String

    fun isInSession(symbol: String, t: Instant): Boolean
    fun sessionRange(symbol: String, t: Instant): TimeRange
    fun anchorEpochFor(anchor: SessionAnchor, t: Instant): Long
    fun rangeFor(anchor: SessionAnchor, anchorEpoch: Long): TimeRange

    fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal =
        error("tradingPeriodsPerYear not implemented for $name")

    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar
        fun fxDefault(): TradingCalendar = FxCalendar
        fun nyse(): TradingCalendar = NyseCalendar
    }
}
```

- [ ] **Step 4: Override in CryptoCalendar**

Edit `src/main/kotlin/com/qkt/common/CryptoCalendar.kt` — add the override. The crypto formula: `365.25 days × 24 hours × 60 minutes / window_minutes`. Total minutes/year = `525960`.

```kotlin
import com.qkt.candles.TimeWindow
import java.math.BigDecimal

// inside CryptoCalendar (object or class):
override fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal {
    val minutesPerYear = BigDecimal("525960")
    val windowMinutes = BigDecimal(window.durationMs).divide(BigDecimal("60000"), Money.CONTEXT)
    return minutesPerYear.divide(windowMinutes, Money.CONTEXT)
}
```

(If `CryptoCalendar` is `object`, add the override directly. Read it first if unsure.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests '*TradingCalendarTradingPeriodsPerYearTest*'`
Expected: PASS, all 3 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/common/TradingCalendar.kt \
        src/main/kotlin/com/qkt/common/CryptoCalendar.kt \
        src/test/kotlin/com/qkt/common/TradingCalendarTradingPeriodsPerYearTest.kt
git commit -m "feat(common): add tradingPeriodsPerYear annualization factor"
```

---

### Task 2: Add `DrawdownTracker.fromCurve` companion

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/DrawdownTracker.kt`
- Test: `src/test/kotlin/com/qkt/risk/DrawdownTrackerFromCurveTest.kt` (new)

`fromCurve` is decoupled from any specific `EquitySample` type to keep `risk/` from depending on `backtest/`. It takes an iterable of `BigDecimal` equity values in sample order.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/risk/DrawdownTrackerFromCurveTest.kt`:

```kotlin
package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownTrackerFromCurveTest {
    @Test
    fun `empty curve returns zero`() {
        assertThat(DrawdownTracker.fromCurve(emptyList())).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `monotone increasing curve has zero drawdown`() {
        val curve = listOf(BigDecimal("10"), BigDecimal("20"), BigDecimal("30"))
        assertThat(DrawdownTracker.fromCurve(curve)).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `peak then trough yields fractional drawdown`() {
        // peak 100, trough 60 → drawdown = 40 / 100 = 0.4
        val curve = listOf(BigDecimal("0"), BigDecimal("100"), BigDecimal("60"), BigDecimal("80"))
        assertThat(DrawdownTracker.fromCurve(curve)).isEqualByComparingTo(BigDecimal("0.4"))
    }

    @Test
    fun `peak never positive returns zero`() {
        val curve = listOf(BigDecimal("-10"), BigDecimal("-20"), BigDecimal("-5"))
        assertThat(DrawdownTracker.fromCurve(curve)).isEqualByComparingTo(Money.ZERO)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*DrawdownTrackerFromCurveTest*'`
Expected: FAIL — `fromCurve` is not defined.

- [ ] **Step 3: Add the companion function**

Edit `src/main/kotlin/com/qkt/risk/DrawdownTracker.kt` — append the companion:

```kotlin
package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

class DrawdownTracker(
    private val equityTracker: EquityTracker,
) {
    fun globalDrawdown(): BigDecimal {
        val peak = equityTracker.peakEquity()
        if (peak.signum() <= 0) return Money.ZERO
        val current = equityTracker.currentEquity()
        if (current >= peak) return Money.ZERO
        return peak.subtract(current).divide(peak, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun strategyDrawdown(strategyId: String): BigDecimal {
        val peak = equityTracker.peakEquityFor(strategyId)
        if (peak.signum() <= 0) return Money.ZERO
        val current = equityTracker.currentEquityFor(strategyId)
        if (current >= peak) return Money.ZERO
        return peak.subtract(current).divide(peak, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    companion object {
        fun fromCurve(samples: List<BigDecimal>): BigDecimal {
            var peak = Money.ZERO
            var maxDd = Money.ZERO
            for (e in samples) {
                if (e > peak) peak = e
                if (peak.signum() > 0 && e < peak) {
                    val dd = peak.subtract(e).divide(peak, Money.CONTEXT)
                    if (dd > maxDd) maxDd = dd
                }
            }
            return maxDd.setScale(Money.SCALE, Money.ROUNDING)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*DrawdownTrackerFromCurveTest*'`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/risk/DrawdownTracker.kt \
        src/test/kotlin/com/qkt/risk/DrawdownTrackerFromCurveTest.kt
git commit -m "feat(risk): add DrawdownTracker.fromCurve companion fn"
```

---

### Task 3: Create `SampleCadence` and `EquitySample`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/SampleCadence.kt`
- Create: `src/main/kotlin/com/qkt/backtest/EquitySample.kt`

These are simple value types — no tests needed (covered indirectly by collector tests in Task 9).

- [ ] **Step 1: Create `SampleCadence.kt`**

```kotlin
package com.qkt.backtest

enum class SampleCadence { TICK, CANDLE_CLOSE, FILL }
```

- [ ] **Step 2: Create `EquitySample.kt`**

```kotlin
package com.qkt.backtest

import java.math.BigDecimal

data class EquitySample(val timestamp: Long, val equity: BigDecimal)
```

- [ ] **Step 3: Verify they compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/SampleCadence.kt \
        src/main/kotlin/com/qkt/backtest/EquitySample.kt
git commit -m "feat(backtest): add SampleCadence and EquitySample"
```

---

### Task 4: Create `ProfitFactor` metric

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/metrics/ProfitFactor.kt`
- Create: `src/test/kotlin/com/qkt/backtest/metrics/ProfitFactorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProfitFactorTest {
    @Test
    fun `empty list returns null`() {
        assertThat(profitFactor(emptyList())).isNull()
    }

    @Test
    fun `all wins returns null since no losses`() {
        val realizeds = listOf(BigDecimal("10"), BigDecimal("5"))
        assertThat(profitFactor(realizeds)).isNull()
    }

    @Test
    fun `all losses returns zero`() {
        val realizeds = listOf(BigDecimal("-10"), BigDecimal("-5"))
        assertThat(profitFactor(realizeds)).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `mixed returns wins over abs losses`() {
        // wins = 30; losses = -20; PF = 30 / 20 = 1.5
        val realizeds = listOf(BigDecimal("10"), BigDecimal("20"), BigDecimal("-15"), BigDecimal("-5"))
        assertThat(profitFactor(realizeds)).isEqualByComparingTo(BigDecimal("1.5"))
    }

    @Test
    fun `zeros are ignored`() {
        val realizeds = listOf(BigDecimal("10"), BigDecimal("0"), BigDecimal("-5"))
        assertThat(profitFactor(realizeds)).isEqualByComparingTo(BigDecimal("2.0"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ProfitFactorTest*'`
Expected: FAIL — `profitFactor` is not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/metrics/ProfitFactor.kt`:

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal

fun profitFactor(realizeds: List<BigDecimal>): BigDecimal? {
    if (realizeds.isEmpty()) return null
    val wins = realizeds.filter { it.signum() > 0 }.fold(Money.ZERO) { a, v -> a.add(v) }
    val losses = realizeds.filter { it.signum() < 0 }.fold(Money.ZERO) { a, v -> a.add(v) }
    if (losses.signum() == 0) {
        return if (wins.signum() == 0) null else null
    }
    return wins.divide(losses.abs(), Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ProfitFactorTest*'`
Expected: PASS, all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/metrics/ProfitFactor.kt \
        src/test/kotlin/com/qkt/backtest/metrics/ProfitFactorTest.kt
git commit -m "feat(backtest): add profitFactor metric"
```

---

### Task 5: Create `WinLossStats` metric

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/metrics/WinLossStats.kt`
- Create: `src/test/kotlin/com/qkt/backtest/metrics/WinLossStatsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WinLossStatsTest {
    @Test
    fun `empty list returns zeros`() {
        val stats = winLossStats(emptyList())
        assertThat(stats.avgWin).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.avgLoss).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.largestWin).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.largestLoss).isEqualByComparingTo(Money.ZERO)
        assertThat(stats.maxConsecutiveLosses).isEqualTo(0)
    }

    @Test
    fun `mixed list computes avgs and extremes`() {
        // wins: [10, 20, 30] → avg 20, largest 30
        // losses: [-5, -15] → avg -10, largest -15
        val stats = winLossStats(listOf(
            BigDecimal("10"), BigDecimal("-5"), BigDecimal("20"), BigDecimal("-15"), BigDecimal("30")
        ))
        assertThat(stats.avgWin).isEqualByComparingTo(BigDecimal("20"))
        assertThat(stats.avgLoss).isEqualByComparingTo(BigDecimal("-10"))
        assertThat(stats.largestWin).isEqualByComparingTo(BigDecimal("30"))
        assertThat(stats.largestLoss).isEqualByComparingTo(BigDecimal("-15"))
    }

    @Test
    fun `max consecutive losses counts longest negative run in order`() {
        // sequence: + - - + - - - + → longest run = 3
        val stats = winLossStats(listOf(
            BigDecimal("10"), BigDecimal("-1"), BigDecimal("-2"), BigDecimal("5"),
            BigDecimal("-3"), BigDecimal("-4"), BigDecimal("-5"), BigDecimal("8")
        ))
        assertThat(stats.maxConsecutiveLosses).isEqualTo(3)
    }

    @Test
    fun `zeros do not break consecutive loss counting`() {
        // a zero between two losses interrupts the run
        val stats = winLossStats(listOf(BigDecimal("-1"), BigDecimal("0"), BigDecimal("-2")))
        assertThat(stats.maxConsecutiveLosses).isEqualTo(1)
    }

    @Test
    fun `single win gives zero consecutive losses`() {
        val stats = winLossStats(listOf(BigDecimal("10")))
        assertThat(stats.maxConsecutiveLosses).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WinLossStatsTest*'`
Expected: FAIL — `winLossStats` is not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/metrics/WinLossStats.kt`:

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal

data class WinLossStats(
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val largestWin: BigDecimal,
    val largestLoss: BigDecimal,
    val maxConsecutiveLosses: Int,
)

fun winLossStats(realizeds: List<BigDecimal>): WinLossStats {
    val wins = realizeds.filter { it.signum() > 0 }
    val losses = realizeds.filter { it.signum() < 0 }

    val avgWin =
        if (wins.isEmpty()) Money.ZERO
        else wins.fold(Money.ZERO) { a, v -> a.add(v) }
            .divide(BigDecimal(wins.size), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)

    val avgLoss =
        if (losses.isEmpty()) Money.ZERO
        else losses.fold(Money.ZERO) { a, v -> a.add(v) }
            .divide(BigDecimal(losses.size), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)

    val largestWin = wins.maxOrNull() ?: Money.ZERO
    val largestLoss = losses.minOrNull() ?: Money.ZERO

    var maxRun = 0
    var run = 0
    for (v in realizeds) {
        if (v.signum() < 0) {
            run += 1
            if (run > maxRun) maxRun = run
        } else {
            run = 0
        }
    }

    return WinLossStats(
        avgWin = avgWin.setScale(Money.SCALE, Money.ROUNDING),
        avgLoss = avgLoss.setScale(Money.SCALE, Money.ROUNDING),
        largestWin = largestWin.setScale(Money.SCALE, Money.ROUNDING),
        largestLoss = largestLoss.setScale(Money.SCALE, Money.ROUNDING),
        maxConsecutiveLosses = maxRun,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WinLossStatsTest*'`
Expected: PASS, all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/metrics/WinLossStats.kt \
        src/test/kotlin/com/qkt/backtest/metrics/WinLossStatsTest.kt
git commit -m "feat(backtest): add winLossStats metric"
```

---

### Task 6: Create `Sharpe` metric

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/metrics/Sharpe.kt`
- Create: `src/test/kotlin/com/qkt/backtest/metrics/SharpeTest.kt`

`Sharpe` takes the equity-curve values (just `BigDecimal`s, not `EquitySample`) and an annualization factor, returning `BigDecimal?` (null when undefined).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.metrics

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SharpeTest {
    @Test
    fun `fewer than two samples returns null`() {
        assertThat(sharpe(emptyList(), BigDecimal("525960"))).isNull()
        assertThat(sharpe(listOf(BigDecimal("100")), BigDecimal("525960"))).isNull()
    }

    @Test
    fun `constant equity returns null due to zero stddev`() {
        val curve = listOf(BigDecimal("100"), BigDecimal("100"), BigDecimal("100"))
        assertThat(sharpe(curve, BigDecimal("525960"))).isNull()
    }

    @Test
    fun `monotonic up gives positive sharpe`() {
        // returns: (110-100)/100=0.1, (120-110)/110≈0.0909, (130-120)/120≈0.0833
        val curve = listOf(BigDecimal("100"), BigDecimal("110"), BigDecimal("120"), BigDecimal("130"))
        val s = sharpe(curve, BigDecimal("252"))!!
        assertThat(s.signum()).isEqualTo(1)
    }

    @Test
    fun `oscillating curve gives finite sharpe`() {
        val curve = listOf(BigDecimal("100"), BigDecimal("110"), BigDecimal("100"), BigDecimal("110"))
        val s = sharpe(curve, BigDecimal("252"))
        assertThat(s).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SharpeTest*'`
Expected: FAIL — `sharpe` not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/metrics/Sharpe.kt`:

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.MathContext

private val EPSILON = BigDecimal("0.00000001")

fun sharpe(equityCurve: List<BigDecimal>, annualizationFactor: BigDecimal): BigDecimal? {
    if (equityCurve.size < 2) return null

    val returns = mutableListOf<BigDecimal>()
    for (i in 0 until equityCurve.size - 1) {
        val prev = equityCurve[i]
        val next = equityCurve[i + 1]
        val denom = prev.abs().max(EPSILON)
        val r = next.subtract(prev).divide(denom, Money.CONTEXT)
        returns.add(r)
    }

    val n = BigDecimal(returns.size)
    val mean = returns.fold(Money.ZERO) { a, v -> a.add(v) }.divide(n, Money.CONTEXT)
    val variance = returns
        .map { it.subtract(mean).pow(2) }
        .fold(Money.ZERO) { a, v -> a.add(v) }
        .divide(BigDecimal(returns.size - 1).max(BigDecimal.ONE), Money.CONTEXT)
    if (variance.signum() <= 0) return null

    val stddev = variance.sqrt(MathContext.DECIMAL64)
    if (stddev.signum() == 0) return null

    val annFactor = annualizationFactor.sqrt(MathContext.DECIMAL64)
    return mean
        .divide(stddev, Money.CONTEXT)
        .multiply(annFactor, Money.CONTEXT)
        .setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SharpeTest*'`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/metrics/Sharpe.kt \
        src/test/kotlin/com/qkt/backtest/metrics/SharpeTest.kt
git commit -m "feat(backtest): add sharpe ratio metric"
```

---

### Task 7: Create `Calmar` metric

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/metrics/Calmar.kt`
- Create: `src/test/kotlin/com/qkt/backtest/metrics/CalmarTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CalmarTest {
    @Test
    fun `zero drawdown returns null`() {
        assertThat(calmar(BigDecimal("100"), Money.ZERO)).isNull()
    }

    @Test
    fun `positive return divided by drawdown`() {
        // 100 / 0.2 = 500
        assertThat(calmar(BigDecimal("100"), BigDecimal("0.2")))
            .isEqualByComparingTo(BigDecimal("500"))
    }

    @Test
    fun `negative return divided by drawdown`() {
        assertThat(calmar(BigDecimal("-50"), BigDecimal("0.5")))
            .isEqualByComparingTo(BigDecimal("-100"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*CalmarTest*'`
Expected: FAIL — `calmar` not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/metrics/Calmar.kt`:

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal

fun calmar(totalReturn: BigDecimal, maxDrawdown: BigDecimal): BigDecimal? {
    if (maxDrawdown.signum() == 0) return null
    return totalReturn.divide(maxDrawdown, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*CalmarTest*'`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/metrics/Calmar.kt \
        src/test/kotlin/com/qkt/backtest/metrics/CalmarTest.kt
git commit -m "feat(backtest): add calmar ratio metric"
```

---

### Task 8: Create `PerformanceReport` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`

Simple data class — no test (covered by ReportBuilder tests later).

- [ ] **Step 1: Create file**

```kotlin
package com.qkt.backtest

import java.math.BigDecimal

data class PerformanceReport(
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    val winRate: BigDecimal,
    val maxDrawdown: BigDecimal,
    val profitFactor: BigDecimal?,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val largestWin: BigDecimal,
    val largestLoss: BigDecimal,
    val maxConsecutiveLosses: Int,
    val sharpeRatio: BigDecimal?,
    val calmarRatio: BigDecimal?,
    val equityCurve: List<EquitySample>,
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/PerformanceReport.kt
git commit -m "feat(backtest): add PerformanceReport data class"
```

---

### Task 9: Create `EquityCurveCollector` (CANDLE_CLOSE cadence)

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt`
- Create: `src/test/kotlin/com/qkt/backtest/EquityCurveCollectorTest.kt`

Collector starts with `CANDLE_CLOSE` only — Task 10 adds TICK and FILL.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquityCurveCollectorTest {
    private fun candle(close: String, endMs: Long): Candle =
        Candle(
            symbol = "X",
            open = Money.of(close),
            high = Money.of(close),
            low = Money.of(close),
            close = Money.of(close),
            volume = Money.of("1"),
            startTime = endMs - 60_000L,
            endTime = endMs,
        )

    @Test
    fun `CANDLE_CLOSE samples global and per-strategy equity at candle endTime`() {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)

        val collector = EquityCurveCollector(
            cadence = SampleCadence.CANDLE_CLOSE,
            bus = bus,
            pnl = pnl,
            strategyPnL = strategyPnL,
            strategyIds = listOf("s1"),
        )

        bus.publish(CandleEvent(candle("100", 60_000L)))
        bus.publish(CandleEvent(candle("100", 120_000L)))

        assertThat(collector.global()).hasSize(2)
        assertThat(collector.global()[0].timestamp).isEqualTo(60_000L)
        assertThat(collector.global()[1].timestamp).isEqualTo(120_000L)
        assertThat(collector.global()[0].equity).isEqualByComparingTo(Money.ZERO)

        assertThat(collector.forStrategy("s1")).hasSize(2)
        assertThat(collector.forStrategy("s1")[0].equity).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unknown strategyId returns empty list`() {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)

        val collector = EquityCurveCollector(
            cadence = SampleCadence.CANDLE_CLOSE,
            bus = bus,
            pnl = pnl,
            strategyPnL = strategyPnL,
            strategyIds = listOf("s1"),
        )

        assertThat(collector.forStrategy("nonexistent")).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*EquityCurveCollectorTest*'`
Expected: FAIL — `EquityCurveCollector` not defined.

- [ ] **Step 3: Implement (CANDLE_CLOSE only)**

Create `src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt`:

```kotlin
package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.events.CandleEvent
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal

class EquityCurveCollector(
    private val cadence: SampleCadence,
    bus: EventBus,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    strategyIds: List<String>,
) {
    private val globalCurve: MutableList<EquitySample> = mutableListOf()
    private val perStrategy: Map<String, MutableList<EquitySample>> =
        strategyIds.associateWith { mutableListOf() }

    init {
        when (cadence) {
            SampleCadence.CANDLE_CLOSE ->
                bus.subscribe<CandleEvent> { e -> sample(e.candle.endTime) }
            SampleCadence.TICK -> Unit  // wired in Task 10
            SampleCadence.FILL -> Unit  // wired in Task 10
        }
    }

    fun global(): List<EquitySample> = globalCurve.toList()

    fun forStrategy(strategyId: String): List<EquitySample> =
        perStrategy[strategyId]?.toList() ?: emptyList()

    private fun sample(timestamp: Long) {
        val globalEquity: BigDecimal = pnl.realizedTotal().add(pnl.unrealizedTotal())
        globalCurve.add(EquitySample(timestamp, globalEquity))
        for ((strategyId, list) in perStrategy) {
            list.add(EquitySample(timestamp, strategyPnL.totalFor(strategyId)))
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*EquityCurveCollectorTest*'`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt \
        src/test/kotlin/com/qkt/backtest/EquityCurveCollectorTest.kt
git commit -m "feat(backtest): add EquityCurveCollector with CANDLE_CLOSE cadence"
```

---

### Task 10: Add TICK and FILL cadences to collector

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt`
- Modify: `src/test/kotlin/com/qkt/backtest/EquityCurveCollectorTest.kt`

- [ ] **Step 1: Add the failing tests**

Append to `EquityCurveCollectorTest.kt` (inside the class):

```kotlin
@Test
fun `TICK cadence samples on every TickEvent`() {
    val clock = com.qkt.common.FixedClock(0L)
    val sequencer = com.qkt.common.MonotonicSequenceGenerator()
    val bus = com.qkt.bus.EventBus(clock, sequencer)
    val priceTracker = com.qkt.marketdata.MarketPriceTracker()
    val positions = com.qkt.positions.PositionTracker()
    val pnl = com.qkt.pnl.PnLCalculator(positions, priceTracker)
    val strategyPositions = com.qkt.positions.StrategyPositionTracker()
    val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)

    val collector = EquityCurveCollector(
        cadence = SampleCadence.TICK,
        bus = bus,
        pnl = pnl,
        strategyPnL = strategyPnL,
        strategyIds = listOf("s1"),
    )

    bus.publish(com.qkt.events.TickEvent(com.qkt.marketdata.Tick("X", com.qkt.common.Money.of("100"), 1_000L)))
    bus.publish(com.qkt.events.TickEvent(com.qkt.marketdata.Tick("X", com.qkt.common.Money.of("100"), 2_000L)))

    assertThat(collector.global()).hasSize(2)
    assertThat(collector.global()[0].timestamp).isEqualTo(1_000L)
    assertThat(collector.global()[1].timestamp).isEqualTo(2_000L)
}

@Test
fun `FILL cadence samples on every OrderFilled event`() {
    val clock = com.qkt.common.FixedClock(0L)
    val sequencer = com.qkt.common.MonotonicSequenceGenerator()
    val bus = com.qkt.bus.EventBus(clock, sequencer)
    val priceTracker = com.qkt.marketdata.MarketPriceTracker()
    val positions = com.qkt.positions.PositionTracker()
    val pnl = com.qkt.pnl.PnLCalculator(positions, priceTracker)
    val strategyPositions = com.qkt.positions.StrategyPositionTracker()
    val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)

    val collector = EquityCurveCollector(
        cadence = SampleCadence.FILL,
        bus = bus,
        pnl = pnl,
        strategyPnL = strategyPnL,
        strategyIds = listOf("s1"),
    )

    bus.publish(
        com.qkt.events.BrokerEvent.OrderFilled(
            clientOrderId = "c1",
            brokerOrderId = "b1",
            symbol = "X",
            side = com.qkt.common.Side.BUY,
            price = com.qkt.common.Money.of("100"),
            quantity = com.qkt.common.Money.of("1"),
            strategyId = "s1",
            timestamp = 5_000L,
        )
    )

    assertThat(collector.global()).hasSize(1)
    assertThat(collector.global()[0].timestamp).isEqualTo(5_000L)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*EquityCurveCollectorTest*'`
Expected: FAIL on the two new tests (`global()` returns empty for TICK/FILL).

- [ ] **Step 3: Wire TICK and FILL**

Edit `src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt` — replace the `init` block:

```kotlin
init {
    when (cadence) {
        SampleCadence.CANDLE_CLOSE ->
            bus.subscribe<CandleEvent> { e -> sample(e.candle.endTime) }
        SampleCadence.TICK ->
            bus.subscribe<com.qkt.events.TickEvent> { e -> sample(e.tick.timestamp) }
        SampleCadence.FILL ->
            bus.subscribe<com.qkt.events.BrokerEvent.OrderFilled> { e -> sample(e.timestamp) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*EquityCurveCollectorTest*'`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt \
        src/test/kotlin/com/qkt/backtest/EquityCurveCollectorTest.kt
git commit -m "feat(backtest): wire TICK and FILL cadences in EquityCurveCollector"
```

---

### Task 11: Add `strategyId` to TradeRecord and pipeline callback

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradeRecord.kt` — add field
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt` — change callback signature
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt` — pass strategyId to TradeRecord
- Modify: `src/main/kotlin/com/qkt/app/Main.kt` — update callsite
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` — update callsite
- Test: keep existing pipeline/backtest tests green

- [ ] **Step 1: Read the current TradeRecord callsites**

Run: `grep -n "TradeRecord(" /home/dickson/Desktop/personal/qkt/src --include="*.kt" -r`
Expected: callsite in `Backtest.kt:106` is the only constructor call.

- [ ] **Step 2: Add `strategyId` to TradeRecord**

Edit `src/main/kotlin/com/qkt/app/TradeRecord.kt`:

```kotlin
package com.qkt.app

import com.qkt.execution.Trade
import java.math.BigDecimal

data class TradeRecord(
    val trade: Trade,
    val realized: BigDecimal,
    val strategyId: String,
)
```

- [ ] **Step 3: Update pipeline callback signature**

Edit `src/main/kotlin/com/qkt/app/TradingPipeline.kt`:

Line 59: change `val onFilled: (Trade, BigDecimal) -> Unit = { _, _ -> },`
to: `val onFilled: (Trade, BigDecimal, String) -> Unit = { _, _, _ -> },`

Line 141: change `onFilled(trade, realized)` to `onFilled(trade, realized, e.strategyId)`.

- [ ] **Step 4: Update `Backtest.kt` callsite**

Edit `src/main/kotlin/com/qkt/app/Backtest.kt:106`:

Change:
```kotlin
onFilled = { trade, realized -> tradeRecords.add(TradeRecord(trade, realized)) },
```
to:
```kotlin
onFilled = { trade, realized, strategyId -> tradeRecords.add(TradeRecord(trade, realized, strategyId)) },
```

- [ ] **Step 5: Update `Main.kt` callsite**

Edit `src/main/kotlin/com/qkt/app/Main.kt:75`:

Change `onFilled = { trade, _ ->` to `onFilled = { trade, _, _ ->`.

- [ ] **Step 6: Update `LiveSession.kt` callsite**

Edit `src/main/kotlin/com/qkt/app/LiveSession.kt:87`:

Change `onFilled = { trade, _ -> trades.add(trade) },` to `onFilled = { trade, _, _ -> trades.add(trade) },`.

- [ ] **Step 7: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Existing pipeline/backtest tests still pass since `TradeRecord` constructor calls in tests, if any, will need a `strategyId` arg.

If a test fails for a missing arg, search for it: `grep -rn "TradeRecord(" src/test/`. Update each callsite to pass `strategyId = ""` (or a meaningful id when the test exercises a strategy).

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/app/TradeRecord.kt \
        src/main/kotlin/com/qkt/app/TradingPipeline.kt \
        src/main/kotlin/com/qkt/app/Backtest.kt \
        src/main/kotlin/com/qkt/app/Main.kt \
        src/main/kotlin/com/qkt/app/LiveSession.kt
git commit -m "feat(app): plumb strategyId through onFilled callback and TradeRecord"
```

---

### Task 12: Create `ReportBuilder`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt`
- Create: `src/test/kotlin/com/qkt/backtest/ReportBuilderTest.kt`

`ReportBuilder` takes the raw inputs and assembles a `PerformanceReport`. We test on a small fixture: 4 trades, a 5-point equity curve.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest

import com.qkt.app.TradeRecord
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReportBuilderTest {
    private fun tradeRecord(realized: String, strategyId: String = "s1", ts: Long = 0): TradeRecord =
        TradeRecord(
            trade = Trade(orderId = "o", symbol = "X", price = Money.of("100"),
                quantity = Money.of("1"), side = Side.BUY, timestamp = ts),
            realized = BigDecimal(realized),
            strategyId = strategyId,
        )

    @Test
    fun `buildGlobal aggregates trades and curve`() {
        val trades = listOf(
            tradeRecord("10"),
            tradeRecord("-5"),
            tradeRecord("20"),
            tradeRecord("-3"),
        )
        val curve = listOf(
            EquitySample(0L, BigDecimal("0")),
            EquitySample(1L, BigDecimal("10")),
            EquitySample(2L, BigDecimal("5")),
            EquitySample(3L, BigDecimal("25")),
            EquitySample(4L, BigDecimal("22")),
        )

        val report = ReportBuilder.buildGlobal(
            trades = trades,
            equityCurve = curve,
            finalRealized = BigDecimal("22"),
            finalUnrealized = Money.ZERO,
            annualizationFactor = BigDecimal("525960"),
        )

        assertThat(report.realizedTotal).isEqualByComparingTo(BigDecimal("22"))
        assertThat(report.totalPnL).isEqualByComparingTo(BigDecimal("22"))
        assertThat(report.tradeCount).isEqualTo(4)
        // wins=2, total closing trades=4, winRate = 0.5
        assertThat(report.winRate).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(report.profitFactor).isNotNull
        assertThat(report.avgWin).isEqualByComparingTo(BigDecimal("15"))
        assertThat(report.avgLoss).isEqualByComparingTo(BigDecimal("-4"))
        assertThat(report.maxConsecutiveLosses).isEqualTo(1)
        assertThat(report.equityCurve).hasSize(5)
        // peak=25, low after=22 → drawdown = 3/25 = 0.12
        assertThat(report.maxDrawdown).isEqualByComparingTo(BigDecimal("0.12"))
    }

    @Test
    fun `buildPerStrategy filters trades by strategyId`() {
        val trades = listOf(
            tradeRecord("10", strategyId = "a"),
            tradeRecord("-5", strategyId = "b"),
            tradeRecord("20", strategyId = "a"),
        )
        val curveA = listOf(EquitySample(0L, BigDecimal("0")), EquitySample(1L, BigDecimal("30")))

        val reportA = ReportBuilder.buildPerStrategy(
            strategyId = "a",
            trades = trades.filter { it.strategyId == "a" },
            equityCurve = curveA,
            finalRealized = BigDecimal("30"),
            finalUnrealized = Money.ZERO,
            annualizationFactor = BigDecimal("525960"),
        )

        assertThat(reportA.tradeCount).isEqualTo(2)
        assertThat(reportA.realizedTotal).isEqualByComparingTo(BigDecimal("30"))
    }

    @Test
    fun `empty trades produces zero metrics`() {
        val report = ReportBuilder.buildGlobal(
            trades = emptyList(),
            equityCurve = listOf(EquitySample(0L, Money.ZERO)),
            finalRealized = Money.ZERO,
            finalUnrealized = Money.ZERO,
            annualizationFactor = BigDecimal("525960"),
        )
        assertThat(report.tradeCount).isEqualTo(0)
        assertThat(report.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(report.profitFactor).isNull()
        assertThat(report.sharpeRatio).isNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ReportBuilderTest*'`
Expected: FAIL — `ReportBuilder` not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt`:

```kotlin
package com.qkt.backtest

import com.qkt.app.TradeRecord
import com.qkt.backtest.metrics.calmar
import com.qkt.backtest.metrics.profitFactor
import com.qkt.backtest.metrics.sharpe
import com.qkt.backtest.metrics.winLossStats
import com.qkt.common.Money
import com.qkt.risk.DrawdownTracker
import java.math.BigDecimal

object ReportBuilder {
    fun buildGlobal(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
    ): PerformanceReport = build(trades, equityCurve, finalRealized, finalUnrealized, annualizationFactor)

    fun buildPerStrategy(
        strategyId: String,
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
    ): PerformanceReport {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        return build(trades, equityCurve, finalRealized, finalUnrealized, annualizationFactor)
    }

    private fun build(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
    ): PerformanceReport {
        val realizeds = trades.map { it.realized }
        val closing = realizeds.filter { it.signum() != 0 }
        val wins = closing.count { it.signum() > 0 }
        val winRate =
            if (closing.isEmpty()) Money.ZERO
            else BigDecimal(wins).divide(BigDecimal(closing.size), Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)

        val pf = profitFactor(realizeds)
        val wl = winLossStats(realizeds)
        val drawdown = DrawdownTracker.fromCurve(equityCurve.map { it.equity })
        val sharpeR = sharpe(equityCurve.map { it.equity }, annualizationFactor)
        val calmarR = calmar(finalRealized.add(finalUnrealized), drawdown)

        return PerformanceReport(
            realizedTotal = finalRealized.setScale(Money.SCALE, Money.ROUNDING),
            unrealizedTotal = finalUnrealized.setScale(Money.SCALE, Money.ROUNDING),
            totalPnL = finalRealized.add(finalUnrealized).setScale(Money.SCALE, Money.ROUNDING),
            tradeCount = trades.size,
            winRate = winRate,
            maxDrawdown = drawdown,
            profitFactor = pf,
            avgWin = wl.avgWin,
            avgLoss = wl.avgLoss,
            largestWin = wl.largestWin,
            largestLoss = wl.largestLoss,
            maxConsecutiveLosses = wl.maxConsecutiveLosses,
            sharpeRatio = sharpeR,
            calmarRatio = calmarR,
            equityCurve = equityCurve,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*ReportBuilderTest*'`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/ReportBuilder.kt \
        src/test/kotlin/com/qkt/backtest/ReportBuilderTest.kt
git commit -m "feat(backtest): add ReportBuilder for PerformanceReport assembly"
```

---

### Task 13: Move `Backtest`, `BacktestResult`, `TradeRecord` to `com.qkt.backtest`

**Files:**
- Move: `src/main/kotlin/com/qkt/app/Backtest.kt` → `src/main/kotlin/com/qkt/backtest/Backtest.kt`
- Move: `src/main/kotlin/com/qkt/app/BacktestResult.kt` → `src/main/kotlin/com/qkt/backtest/BacktestResult.kt`
- Move: `src/main/kotlin/com/qkt/app/TradeRecord.kt` → `src/main/kotlin/com/qkt/backtest/TradeRecord.kt`
- Move: `src/test/kotlin/com/qkt/app/BacktestTest.kt` → `src/test/kotlin/com/qkt/backtest/BacktestTest.kt`
- Move: `src/test/kotlin/com/qkt/app/BacktestFromStoreTest.kt` → `src/test/kotlin/com/qkt/backtest/BacktestFromStoreTest.kt`
- Move: `src/test/kotlin/com/qkt/app/BacktestFromSourceTest.kt` → `src/test/kotlin/com/qkt/backtest/BacktestFromSourceTest.kt`
- Move: `src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt` → `src/test/kotlin/com/qkt/backtest/BacktestWarmupTest.kt`
- Modify: `Backtest.kt` body — restructure to integrate cadence + collector + ReportBuilder + new BacktestResult shape
- Modify: every callsite that imports from `com.qkt.app` for these symbols

This task is a single mechanical move-and-rewrite. All tests already exist; they break together and get fixed together.

- [ ] **Step 1: Move the three production files**

```bash
git mv src/main/kotlin/com/qkt/app/TradeRecord.kt src/main/kotlin/com/qkt/backtest/TradeRecord.kt
git mv src/main/kotlin/com/qkt/app/BacktestResult.kt src/main/kotlin/com/qkt/backtest/BacktestResult.kt
git mv src/main/kotlin/com/qkt/app/Backtest.kt src/main/kotlin/com/qkt/backtest/Backtest.kt
```

- [ ] **Step 2: Update package declarations**

Edit each moved file's first line — change `package com.qkt.app` → `package com.qkt.backtest`.

- [ ] **Step 3: Restructure `BacktestResult.kt`**

Replace the file with:

```kotlin
package com.qkt.backtest

import com.qkt.events.RiskRejectedEvent
import com.qkt.positions.Position

data class BacktestResult(
    val trades: List<TradeRecord>,
    val rejections: List<RiskRejectedEvent>,
    val finalPositions: Map<String, Position>,
    val global: PerformanceReport,
    val perStrategy: Map<String, PerformanceReport>,
    val cadence: SampleCadence,
)
```

- [ ] **Step 4: Restructure `Backtest.kt` to integrate Phase 10 surfaces**

Replace the file body with the version below (preserves the constructors and feed plumbing; rewires the `run()` body):

```kotlin
package com.qkt.backtest

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.app.IndicatorWarmer
import com.qkt.app.TradingPipeline
import com.qkt.engine.Engine
import com.qkt.events.RiskRejectedEvent
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.marketdata.store.DataStore
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.math.BigDecimal
import java.time.Instant

class Backtest(
    private val strategies: List<Pair<String, Strategy>>,
    private val rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    private val warmupSpec: WarmupSpec = WarmupSpec.None,
    private val symbols: List<String> = emptyList(),
    private val cadence: SampleCadence = SampleCadence.CANDLE_CLOSE,
) {
    init {
        require(cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
            "SampleCadence.CANDLE_CLOSE requires candleWindow"
        }
    }

    constructor(
        strategies: List<Pair<String, Strategy>>,
        rules: List<RiskRule> = emptyList(),
        ticks: List<Tick>,
        candleWindow: TimeWindow? = null,
        initialTimestamp: Long = 0L,
        cadence: SampleCadence = SampleCadence.CANDLE_CLOSE,
    ) : this(
        strategies = strategies,
        rules = rules,
        feed = HistoricalTickFeed(ticks),
        candleWindow = candleWindow,
        initialTimestamp = initialTimestamp,
        cadence = cadence,
    )

    fun run(): BacktestResult {
        val clock = FixedClock(time = initialTimestamp)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        riskState.warmupComplete = true
        val riskEngine = RiskEngine(rules, emptyList(), positions, riskState)

        val tradeRecords = mutableListOf<TradeRecord>()
        val rejections = mutableListOf<RiskRejectedEvent>()
        val collector = EquityCurveCollector(
            cadence = cadence,
            bus = bus,
            pnl = pnl,
            strategyPnL = strategyPnL,
            strategyIds = strategies.map { it.first },
        )

        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                strategyPositions = strategyPositions,
                strategyPnL = strategyPnL,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = strategies,
                riskEngine = riskEngine,
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = calendar,
                source = source,
                candleWindow = candleWindow,
                onFilled = { trade, realized, strategyId ->
                    tradeRecords.add(TradeRecord(trade, realized, strategyId))
                },
                onRejected = { e -> rejections.add(e) },
                onCandle = {},
            )

        if (source !== NullMarketSource && warmupSpec !is WarmupSpec.None && symbols.isNotEmpty()) {
            IndicatorWarmer(source, pipeline).warmup(
                symbols = symbols,
                spec = warmupSpec,
                now = Instant.ofEpochMilli(initialTimestamp),
            )
        }

        feed.use { f ->
            while (true) {
                val tick = f.next() ?: break
                clock.time = tick.timestamp
                pipeline.ingest(tick)
            }
        }

        val annualizationFactor = annualizationFactor()
        val globalReport = ReportBuilder.buildGlobal(
            trades = tradeRecords,
            equityCurve = collector.global(),
            finalRealized = pnl.realizedTotal(),
            finalUnrealized = pnl.unrealizedTotal(),
            annualizationFactor = annualizationFactor,
        )
        val perStrategy = strategies.associate { (id, _) ->
            id to ReportBuilder.buildPerStrategy(
                strategyId = id,
                trades = tradeRecords.filter { it.strategyId == id },
                equityCurve = collector.forStrategy(id),
                finalRealized = strategyPnL.realizedFor(id),
                finalUnrealized = strategyPnL.unrealizedTotalFor(id),
                annualizationFactor = annualizationFactor,
            )
        }

        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            finalPositions = positions.allPositions(),
            global = globalReport,
            perStrategy = perStrategy,
            cadence = cadence,
        )
    }

    private fun annualizationFactor(): BigDecimal {
        if (cadence == SampleCadence.CANDLE_CLOSE && candleWindow != null) {
            return calendar.tradingPeriodsPerYear(candleWindow)
        }
        // TICK and FILL cadences: approximate periods/year from the run timespan.
        // The collector hasn't been invoked yet for this branch when called pre-run; we
        // only call this fn AFTER the run loop, so the curve is populated by then.
        // For empty curves we return a safe default (252).
        // (Caller invokes annualizationFactor() after feed loop.)
        return BigDecimal("252")  // placeholder; replaced in next branch when curve known
    }

    companion object {
        fun fromStore(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            store: DataStore,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            cadence: SampleCadence = SampleCadence.CANDLE_CLOSE,
        ): Backtest {
            val (from, to) = store.resolveRange(request)
            val resolved = MarketRequest(symbols = request.symbols, from = from, to = to)
            return fromSource(
                strategies = strategies,
                rules = rules,
                source = LocalMarketSource(store, FixedClock(time = to.toEpochMilli())),
                request = resolved,
                candleWindow = candleWindow,
                cadence = cadence,
            )
        }

        fun fromSource(
            strategies: List<Pair<String, Strategy>>,
            rules: List<RiskRule> = emptyList(),
            source: MarketSource,
            request: MarketRequest,
            candleWindow: TimeWindow? = null,
            warmupSpec: WarmupSpec = WarmupSpec.None,
            cadence: SampleCadence = SampleCadence.CANDLE_CLOSE,
        ): Backtest {
            require(MarketSourceCapability.TICKS in source.capabilities) {
                "Backtest requires a MarketSource that supports TICKS; ${source.name} has ${source.capabilities}"
            }
            val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
            val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
            val range = TimeRange(from, to)
            val perSymbolFeeds: List<TickFeed> =
                request.symbols.map { sym -> SequenceTickFeed(source.ticks(sym, range)) }
            val feed: TickFeed =
                if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
            return Backtest(
                strategies = strategies,
                rules = rules,
                feed = feed,
                candleWindow = candleWindow,
                initialTimestamp = from.toEpochMilli(),
                source = source,
                warmupSpec = warmupSpec,
                symbols = request.symbols,
                cadence = cadence,
            )
        }
    }
}
```

- [ ] **Step 5: Replace TICK/FILL annualization placeholder with curve-based estimate**

The `annualizationFactor()` helper above uses a placeholder for TICK/FILL. Replace it with curve-based estimation. Update `run()` to compute the factor after the feed loop:

```kotlin
// After feed.use { ... } finishes, before building reports:
val annualizationFactor: BigDecimal = when (cadence) {
    SampleCadence.CANDLE_CLOSE -> calendar.tradingPeriodsPerYear(candleWindow!!)
    SampleCadence.TICK, SampleCadence.FILL -> {
        val curve = collector.global()
        if (curve.size < 2) {
            BigDecimal("252")
        } else {
            val spanMs = curve.last().timestamp - curve.first().timestamp
            if (spanMs <= 0L) BigDecimal("252")
            else {
                val avgIntervalMs = BigDecimal(spanMs).divide(
                    BigDecimal(curve.size - 1), Money.CONTEXT,
                )
                val msPerYear = BigDecimal("31557600000")  // 365.25d × 86400s × 1000ms
                msPerYear.divide(avgIntervalMs, Money.CONTEXT)
            }
        }
    }
}
```

Then drop the private `annualizationFactor()` method and use the local `val` directly. (Replace the two `annualizationFactor = annualizationFactor()` lines with `annualizationFactor = annualizationFactor`.)

- [ ] **Step 6: Update test file packages**

Move the four test files:

```bash
mkdir -p src/test/kotlin/com/qkt/backtest
git mv src/test/kotlin/com/qkt/app/BacktestTest.kt src/test/kotlin/com/qkt/backtest/BacktestTest.kt
git mv src/test/kotlin/com/qkt/app/BacktestFromStoreTest.kt src/test/kotlin/com/qkt/backtest/BacktestFromStoreTest.kt
git mv src/test/kotlin/com/qkt/app/BacktestFromSourceTest.kt src/test/kotlin/com/qkt/backtest/BacktestFromSourceTest.kt
git mv src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt src/test/kotlin/com/qkt/backtest/BacktestWarmupTest.kt
```

Edit each: change first line from `package com.qkt.app` to `package com.qkt.backtest`. Resolve any imports (most should remain valid since `Backtest` is now in this package).

- [ ] **Step 7: Update test field accesses for new BacktestResult shape**

For each moved test, search for direct accesses on the old flat fields and update:

| Old | New |
|---|---|
| `result.totalPnL` | `result.global.totalPnL` |
| `result.realizedTotal` | `result.global.realizedTotal` |
| `result.unrealizedTotal` | `result.global.unrealizedTotal` |
| `result.tradeCount` | `result.global.tradeCount` |
| `result.winRate` | `result.global.winRate` |
| `result.maxDrawdown` | `result.global.maxDrawdown` |

The old `maxDrawdown` was absolute money; the new one is fractional. Tests asserting absolute drawdown values must update their assertions.

Search via: `grep -rn "result\." src/test/kotlin/com/qkt/backtest/`

- [ ] **Step 8: Update non-test callsites**

`Main.kt`, `MaxAudit.kt` (if it uses BacktestResult), `LiveSession.kt` (it already updated in Task 11):

```bash
grep -rn "import com.qkt.app.Backtest\|import com.qkt.app.BacktestResult\|import com.qkt.app.TradeRecord" src/
```

For each match, change the import to `com.qkt.backtest.*` and update field reads (`result.totalPnL` → `result.global.totalPnL`).

- [ ] **Step 9: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

If failures: walk each compilation error, update the import or field reference, re-run.

- [ ] **Step 10: Commit**

```bash
git add -A src/main/kotlin/com/qkt/ src/test/kotlin/com/qkt/
git status
git commit -m "feat(backtest): move Backtest to com.qkt.backtest with rich result"
```

(Use `-A` here is acceptable since we just verified `git status` is clean except for the move — there's nothing else uncommitted at this checkpoint.)

---

### Task 14: Create `ReportSerializer` (JSON helpers)

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/report/ReportSerializer.kt`
- Test: covered indirectly by `BacktestReportWriterTest` in Task 16

`ReportSerializer` is internal — pure helpers for emitting JSON-encoded primitives without external deps.

- [ ] **Step 1: Create file**

```kotlin
package com.qkt.backtest.report

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter

internal object ReportSerializer {
    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    fun jsonBigDecimal(v: BigDecimal): String = "\"${v.toPlainString()}\""

    fun jsonNullableBigDecimal(v: BigDecimal?): String =
        if (v == null) "null" else jsonBigDecimal(v)

    fun isoUtc(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/ReportSerializer.kt
git commit -m "feat(backtest): add ReportSerializer JSON helpers"
```

---

### Task 15: Create `BacktestReportWriter`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt`
- Create: `src/test/kotlin/com/qkt/backtest/report/BacktestReportWriterTest.kt`

The writer takes a directory path and writes 4+ files. We test by writing to a JUnit `@TempDir` and reading the files back as text.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.report

import com.qkt.app.IndicatorWarmer  // unused but ensures package is loadable
import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestReportWriterTest {
    private fun ticks(): List<Tick> =
        (1..5).map { i ->
            Tick("X", Money.of((100 + i).toString()), i * 60_000L)
        }

    @Test
    fun `writer produces result_json equity_csv trades_csv rejections_csv`(@TempDir dir: Path) {
        val noopStrategy = object : Strategy {
            override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {}
        }
        val backtest = Backtest(
            strategies = listOf("s1" to noopStrategy),
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
        )
        val result = backtest.run()

        BacktestReportWriter(dir).write(result)

        assertThat(dir.resolve("result.json")).exists()
        assertThat(dir.resolve("equity_global.csv")).exists()
        assertThat(dir.resolve("equity_s1.csv")).exists()
        assertThat(dir.resolve("trades.csv")).exists()
        assertThat(dir.resolve("rejections.csv")).exists()

        val json = Files.readString(dir.resolve("result.json"))
        assertThat(json).contains("\"cadence\": \"CANDLE_CLOSE\"")
        assertThat(json).contains("\"global\":")
        assertThat(json).contains("\"perStrategy\":")

        val eqCsv = Files.readString(dir.resolve("equity_global.csv"))
        assertThat(eqCsv.lines().first()).isEqualTo("timestamp,equity")
    }

    @Test
    fun `unsafe strategyId rejected before any file written`(@TempDir dir: Path) {
        val noopStrategy = object : Strategy {
            override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {}
        }
        val backtest = Backtest(
            strategies = listOf("../danger" to noopStrategy),
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
        )
        // Run will succeed (id passes pipeline check since not blank), but writer rejects.
        val result = backtest.run()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BacktestReportWriter(dir).write(result)
        }
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BacktestReportWriterTest*'`
Expected: FAIL — `BacktestReportWriter` not defined.

- [ ] **Step 3: Implement the writer**

Create `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt`:

```kotlin
package com.qkt.backtest.report

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.EquitySample
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.TradeRecord
import com.qkt.events.RiskRejectedEvent
import java.nio.file.Files
import java.nio.file.Path

class BacktestReportWriter(private val dir: Path) {
    private val safeId = Regex("[A-Za-z0-9_-]+")

    fun write(result: BacktestResult) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (id in result.perStrategy.keys) {
            require(safeId.matches(id)) { "Unsafe strategyId for filesystem write: $id" }
        }

        Files.writeString(dir.resolve("result.json"), renderJson(result))
        Files.writeString(dir.resolve("equity_global.csv"), renderEquityCsv(result.global.equityCurve))
        for ((id, report) in result.perStrategy) {
            Files.writeString(dir.resolve("equity_$id.csv"), renderEquityCsv(report.equityCurve))
        }
        Files.writeString(dir.resolve("trades.csv"), renderTradesCsv(result.trades))
        Files.writeString(dir.resolve("rejections.csv"), renderRejectionsCsv(result.rejections))
    }

    private fun renderEquityCsv(curve: List<EquitySample>): String {
        val sb = StringBuilder("timestamp,equity\n")
        for (s in curve) sb.append(s.timestamp).append(',').append(s.equity.toPlainString()).append('\n')
        return sb.toString()
    }

    private fun renderTradesCsv(trades: List<TradeRecord>): String {
        val sb = StringBuilder("timestamp,strategy,symbol,side,quantity,price,realized,brokerOrderId\n")
        for (r in trades) {
            sb.append(r.trade.timestamp).append(',')
                .append(r.strategyId).append(',')
                .append(r.trade.symbol).append(',')
                .append(r.trade.side).append(',')
                .append(r.trade.quantity.toPlainString()).append(',')
                .append(r.trade.price.toPlainString()).append(',')
                .append(r.realized.toPlainString()).append(',')
                .append(r.trade.orderId).append('\n')
        }
        return sb.toString()
    }

    private fun renderRejectionsCsv(rejections: List<RiskRejectedEvent>): String {
        val sb = StringBuilder("timestamp,reason,strategy,symbol\n")
        for (e in rejections) {
            sb.append(e.timestamp).append(',')
                .append(e.reason).append(',')
                .append(e.request.strategyId).append(',')
                .append(e.request.symbol).append('\n')
        }
        return sb.toString()
    }

    private fun renderJson(result: BacktestResult): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"cadence\": ").append(ReportSerializer.jsonString(result.cadence.name)).append(",\n")
        sb.append("  \"global\": ").append(renderReport(result.global, indent = 2)).append(",\n")
        sb.append("  \"perStrategy\": {")
        if (result.perStrategy.isNotEmpty()) {
            sb.append('\n')
            val entries = result.perStrategy.entries.toList()
            for ((i, e) in entries.withIndex()) {
                sb.append("    ").append(ReportSerializer.jsonString(e.key)).append(": ")
                    .append(renderReport(e.value, indent = 4))
                if (i != entries.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("  }")
        } else {
            sb.append("}")
        }
        sb.append("\n}")
        return sb.toString()
    }

    private fun renderReport(r: PerformanceReport, indent: Int): String {
        val pad = " ".repeat(indent)
        val sb = StringBuilder("{")
        fun field(name: String, value: String, last: Boolean = false) {
            sb.append('\n').append(pad).append("  ")
                .append(ReportSerializer.jsonString(name)).append(": ").append(value)
            if (!last) sb.append(",")
        }
        field("realizedTotal", ReportSerializer.jsonBigDecimal(r.realizedTotal))
        field("unrealizedTotal", ReportSerializer.jsonBigDecimal(r.unrealizedTotal))
        field("totalPnL", ReportSerializer.jsonBigDecimal(r.totalPnL))
        field("tradeCount", r.tradeCount.toString())
        field("winRate", ReportSerializer.jsonBigDecimal(r.winRate))
        field("maxDrawdown", ReportSerializer.jsonBigDecimal(r.maxDrawdown))
        field("profitFactor", ReportSerializer.jsonNullableBigDecimal(r.profitFactor))
        field("avgWin", ReportSerializer.jsonBigDecimal(r.avgWin))
        field("avgLoss", ReportSerializer.jsonBigDecimal(r.avgLoss))
        field("largestWin", ReportSerializer.jsonBigDecimal(r.largestWin))
        field("largestLoss", ReportSerializer.jsonBigDecimal(r.largestLoss))
        field("maxConsecutiveLosses", r.maxConsecutiveLosses.toString())
        field("sharpeRatio", ReportSerializer.jsonNullableBigDecimal(r.sharpeRatio))
        field("calmarRatio", ReportSerializer.jsonNullableBigDecimal(r.calmarRatio))
        sb.append(",\n").append(pad).append("  \"equityCurve\": [")
        if (r.equityCurve.isNotEmpty()) {
            sb.append('\n')
            val entries = r.equityCurve
            for ((i, s) in entries.withIndex()) {
                sb.append(pad).append("    {\"timestamp\": ").append(s.timestamp)
                    .append(", \"iso\": ").append(ReportSerializer.jsonString(ReportSerializer.isoUtc(s.timestamp)))
                    .append(", \"equity\": ").append(ReportSerializer.jsonBigDecimal(s.equity))
                    .append("}")
                if (i != entries.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append(pad).append("  ]")
        } else {
            sb.append("]")
        }
        sb.append('\n').append(pad).append("}")
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BacktestReportWriterTest*'`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt \
        src/test/kotlin/com/qkt/backtest/report/BacktestReportWriterTest.kt
git commit -m "feat(backtest): add BacktestReportWriter with JSON and CSV outputs"
```

---

### Task 16: Add end-to-end test

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/BacktestEndToEndTest.kt`

This is the integration test that exercises the full pipeline: fixture ticks → strategy → fills → equity curve → reports.

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestEndToEndTest {
    @Test
    fun `single buy then sell produces nonempty per-strategy report`() {
        val ticks = (1..10).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }
        var ticksSeen = 0
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
                ticksSeen += 1
                if (ticksSeen == 2) emit(Signal.Buy("X", Money.of("1")))
                if (ticksSeen == 8) emit(Signal.Sell("X", Money.of("1")))
            }
        }

        val backtest = Backtest(
            strategies = listOf("s1" to strategy),
            ticks = ticks,
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
        )
        val result = backtest.run()

        assertThat(result.global.tradeCount).isGreaterThan(0)
        assertThat(result.global.equityCurve).isNotEmpty
        assertThat(result.perStrategy).containsKey("s1")
        assertThat(result.perStrategy["s1"]!!.equityCurve).isNotEmpty
        assertThat(result.cadence).isEqualTo(SampleCadence.CANDLE_CLOSE)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests '*BacktestEndToEndTest*'`
Expected: PASS.

If failure: read the error. Most likely missing strategy contract (e.g. `Signal.Buy` constructor signature). Fix the test to match the existing `Signal` API — read `src/main/kotlin/com/qkt/strategy/Signal.kt` if needed and adjust.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/BacktestEndToEndTest.kt
git commit -m "test(backtest): add end-to-end test for rich BacktestResult"
```

---

### Task 17: Add determinism test

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/BacktestDeterminismTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestDeterminismTest {
    private fun newBacktest(): Backtest {
        val ticks = (1..30).map { Tick("X", Money.of((100 + it % 5).toString()), it * 60_000L) }
        var n = 0
        val strategy = object : Strategy {
            override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
                n += 1
                if (n == 5) emit(Signal.Buy("X", Money.of("1")))
                if (n == 20) emit(Signal.Sell("X", Money.of("1")))
            }
        }
        return Backtest(
            strategies = listOf("s1" to strategy),
            ticks = ticks,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `two runs produce equal results`() {
        val a = newBacktest().run()
        val b = newBacktest().run()
        assertThat(a.global.totalPnL).isEqualByComparingTo(b.global.totalPnL)
        assertThat(a.global.equityCurve).isEqualTo(b.global.equityCurve)
        assertThat(a.global.maxDrawdown).isEqualByComparingTo(b.global.maxDrawdown)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests '*BacktestDeterminismTest*'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/BacktestDeterminismTest.kt
git commit -m "test(backtest): add determinism check across runs"
```

---

### Task 18: Run full test suite + precheck

**Files:** none changed

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All tests pass.

If failures: fix individually. Common issues at this checkpoint:
- A test in `app/` still references the old `BacktestResult` flat fields → update to `result.global.*`.
- A test passes `TradeRecord(trade, realized)` without strategyId → add `strategyId = ""`.

- [ ] **Step 2: Run precheck**

Run: `bash scripts/precheck.sh`
Expected: all checks green.

- [ ] **Step 3: Read commit log for hygiene**

Run: `git log --oneline main..HEAD`
Expected: each subject ≤70 chars, conventional types, no emoji, no AI footer.

If any commit has a bad subject, amend (only the most recent — never published commits). For older commits with bad subjects on this branch, leave as-is and note in the merge commit.

- [ ] **Step 4: No commit (this task is verification only)**

---

### Task 19: Write phase changelog

**Files:**
- Create: `docs/phases/phase-10-backtest-reporting.md`

Per qkt skill §6, every phase ships a user-facing changelog. Cookbook-style examples for cadence, per-strategy reports, writer, charting in pandas.

- [ ] **Step 1: Create the changelog**

```markdown
# Phase 10 — Backtest Reporting

## Summary

Phase 10 turns the backtest from a single-shot smoke run into a serious reporting tool. After this phase, a `Backtest.run()` produces a structured result with the equity curve over time (configurable cadence, default candle-close), per-strategy attribution, and the metrics every quant report carries: profit factor, Sharpe, Calmar, win/loss stats. The data lands in memory for programmatic comparison and on disk as JSON + CSV for charting in any external tool.

## What's new

- `com.qkt.backtest` package — `Backtest`, `BacktestResult`, `TradeRecord` moved here from `com.qkt.app`.
- `SampleCadence` enum — `TICK`, `CANDLE_CLOSE`, `FILL`. New `cadence` parameter on `Backtest` (default `CANDLE_CLOSE`).
- `EquitySample(timestamp, equity)` — single point on an equity curve.
- `EquityCurveCollector` — subscribes to the bus at the chosen cadence, exposes global and per-strategy curves.
- `PerformanceReport` — full metric bundle: realized/unrealized/total P&L, trade count, win rate, fractional max drawdown, profit factor, avg/largest win+loss, max consecutive losses, Sharpe ratio, Calmar ratio, equity curve.
- `BacktestResult.global: PerformanceReport` and `BacktestResult.perStrategy: Map<String, PerformanceReport>` — replaces the old flat fields.
- `com.qkt.backtest.metrics` — pure-function metrics: `profitFactor`, `winLossStats`, `sharpe`, `calmar`.
- `BacktestReportWriter(dir)` — emits `result.json`, `equity_global.csv`, `equity_<strategyId>.csv`, `trades.csv`, `rejections.csv`.
- `TradingCalendar.tradingPeriodsPerYear(window)` — calendar-aware annualization factor for Sharpe; crypto impl provided.
- `DrawdownTracker.fromCurve(samples)` — pure drawdown computation, used by both backtest and any future curve-based caller.
- `TradeRecord.strategyId` — every trade now carries its originating strategy id.

## Migration from previous phase

| Before | After |
|---|---|
| `import com.qkt.app.Backtest` | `import com.qkt.backtest.Backtest` |
| `import com.qkt.app.BacktestResult` | `import com.qkt.backtest.BacktestResult` |
| `result.totalPnL` | `result.global.totalPnL` |
| `result.realizedTotal` | `result.global.realizedTotal` |
| `result.unrealizedTotal` | `result.global.unrealizedTotal` |
| `result.tradeCount` | `result.global.tradeCount` |
| `result.winRate` | `result.global.winRate` |
| `result.maxDrawdown` (absolute) | `result.global.maxDrawdown` (FRACTIONAL — Phase 9 convention) |
| `TradeRecord(trade, realized)` | `TradeRecord(trade, realized, strategyId)` |
| `onFilled = { trade, realized -> ... }` | `onFilled = { trade, realized, strategyId -> ... }` |

## Usage cookbook

### Default backtest (candle-close cadence)

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow

val backtest = Backtest(
    strategies = listOf("ema-cross" to MyStrategy()),
    ticks = historicalTicks,
    candleWindow = TimeWindow.ONE_MINUTE,
    // cadence defaults to CANDLE_CLOSE
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
    candleWindow = null,  // not required for TICK cadence
    cadence = SampleCadence.TICK,
)
```

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
```

## Known limitations

- **No parameter sweep / grid search.** Deferred to a future phase (10b or 12).
- **No walk-forward analysis.** Same.
- **No HTML report.** JSON + CSV only; HTML belongs to a presentation phase after the DSL.
- **No "total return %" or CAGR.** Both require an initial-capital concept the engine doesn't have.
- **No round-trip / hold-time metrics.** Inferring "completed trades" from a fill stream is ambiguous with scale-in/out; per-fill realized P&L is used as the proxy.
- **TICK / FILL Sharpe is approximate.** Annualization for irregular sample spacing uses the run-average interval; document the cadence in any report.
- **Sortino, Ulcer, recovery factor — not shipped.** Add only with a concrete demand.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase10-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase10.md`
- Merge commit: `<filled in at merge time>`
```

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-10-backtest-reporting.md
git commit -m "docs: phase 10 changelog"
```

---

### Task 20: Final verification + finishing-a-development-branch

**Files:** none changed

- [ ] **Step 1: Run all tests one more time**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run precheck**

Run: `bash scripts/precheck.sh`
Expected: all checks green. Note any warnings.

- [ ] **Step 3: Read commit log**

Run: `git log --oneline main..HEAD`

Expected: ~19 commits, each with a clean conventional-commit subject. No body, no footer, no emoji, no AI references.

- [ ] **Step 4: Hand off to finishing-a-development-branch**

Announce: "I'm using the finishing-a-development-branch skill to complete this work."

Invoke: `superpowers:finishing-a-development-branch`. Present the standard 4 options (merge locally, PR, keep, discard) — qkt convention is `git merge --no-ff` with `merge: phase 10 backtest reporting` on the merge commit.

After merge, fill in the merge commit SHA in `docs/phases/phase-10-backtest-reporting.md` (References section) — that requires a follow-up commit on `main` with `docs: link phase 10 changelog to merge commit`.

---

## Self-Review Notes

Spec coverage check:

| Spec section | Plan task |
|---|---|
| §2 Goals — `tradingPeriodsPerYear` | Task 1 |
| §2 Goals — `DrawdownTracker.fromCurve` | Task 2 |
| §2 Goals — `SampleCadence` / `EquitySample` | Task 3 |
| §2 Goals — metrics (PF, win/loss, Sharpe, Calmar) | Tasks 4-7 |
| §2 Goals — `PerformanceReport` | Task 8 |
| §2 Goals — `EquityCurveCollector` | Tasks 9-10 |
| §2 Goals — `ReportBuilder` | Task 12 |
| §2 Goals — `BacktestResult` restructure | Task 13 |
| §2 Goals — package move | Task 13 |
| §2 Goals — `BacktestReportWriter` | Tasks 14-15 |
| §6 Sampling mechanics | Tasks 9-10 |
| §10 Backtest constructor changes | Task 13 |
| §11 Migration | Tasks 11, 13 |
| §12 Testing | Tasks 4-10, 12, 15-17 |
| §16 Success criteria | Task 19 (changelog) |

Type-consistency check:
- `EquityCurveCollector` parameter `pnl: PnLProvider` — ensure `PnLCalculator` implements `PnLProvider`. (Yes — Phase 9 already wired this. If not, the test in Task 9 will fail at compile time and we add the interface impl.)
- `DrawdownTracker.fromCurve` takes `List<BigDecimal>` (just equity values, not `EquitySample`) — `ReportBuilder` calls `equityCurve.map { it.equity }`. Consistent.
- `Backtest` constructor `cadence: SampleCadence = SampleCadence.CANDLE_CLOSE` — used in all secondary constructors and `fromSource`/`fromStore` companions. Consistent.
- `TradeRecord(trade, realized, strategyId)` — three positional args throughout.
- Pipeline `onFilled: (Trade, BigDecimal, String) -> Unit` — three positional args. Backtest, Main, LiveSession all updated in Tasks 11, 13.

No placeholders. Every step has runnable code or shell commands.
