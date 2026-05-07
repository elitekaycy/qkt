# Phase 10c — Walk-Forward Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `WalkForwardHarness<C>` that runs rolling-window train/test folds, picks each train winner via Phase 10b's `BacktestSweep`, evaluates on test, and aggregates results into a `WalkForwardResult` with a concatenated out-of-sample equity curve.

**Architecture:** Pure additive package `com.qkt.backtest.walkforward`. Pure-function rolling-window iteration + curve concatenation in `Windows.kt`. Per-fold orchestration in `WalkForwardHarness` reusing Phase 10b's `BacktestSweep` for the train sweep and Phase 10's `Backtest` for the test run. Writer extension at `com.qkt.backtest.report.WalkForwardReportWriter`.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase10c-design.md`

**Branch:** `phase10c-walk-forward` (already created and active).

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/backtest/walkforward/
├── Windows.kt                       # rollingWindows + concatenate (pure)
├── WalkForwardFold.kt               # data class
├── WalkForwardResult.kt             # data class with aggregates
└── WalkForwardHarness.kt            # orchestrator

src/main/kotlin/com/qkt/backtest/report/
└── WalkForwardReportWriter.kt       # writes summary + per-fold subdirs

src/test/kotlin/com/qkt/backtest/walkforward/
├── WindowsRollingTest.kt
├── WindowsConcatenateTest.kt
├── WalkForwardHarnessValidationTest.kt
├── WalkForwardHarnessTest.kt
├── WalkForwardHarnessFailFastTest.kt
└── WalkForwardDeterminismTest.kt

src/test/kotlin/com/qkt/backtest/report/
└── WalkForwardReportWriterTest.kt
```

### Modified files

None. Phase 10c is purely additive.

---

## Tasks

### Task 1: `rollingWindows` pure function

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/walkforward/Windows.kt`
- Create: `src/test/kotlin/com/qkt/backtest/walkforward/WindowsRollingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/backtest/walkforward/WindowsRollingTest.kt`:

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.common.TimeRange
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WindowsRollingTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun range(
        startDays: Long,
        endDays: Long,
    ): TimeRange = TimeRange(t0.plus(Duration.ofDays(startDays)), t0.plus(Duration.ofDays(endDays)))

    @Test
    fun `exact-fit single fold`() {
        val total = range(0, 30)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
            )
        assertThat(folds).hasSize(1)
        assertThat(folds[0].first).isEqualTo(range(0, 20))
        assertThat(folds[0].second).isEqualTo(range(20, 30))
    }

    @Test
    fun `multi-fold non-overlapping when step equals testSize`() {
        val total = range(0, 60)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
            )
        // fold 0: train 0-20, test 20-30
        // fold 1: train 10-30, test 30-40
        // fold 2: train 20-40, test 40-50
        // fold 3: train 30-50, test 50-60
        // fold 4 would be train 40-60, test 60-70 → testEnd > total.to → stop
        assertThat(folds).hasSize(4)
        assertThat(folds[0].first).isEqualTo(range(0, 20))
        assertThat(folds[3].second).isEqualTo(range(50, 60))
    }

    @Test
    fun `overlapping test windows when step is less than testSize`() {
        val total = range(0, 50)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(20),
                stepSize = Duration.ofDays(5),
            )
        // fold 0: train 0-20, test 20-40
        // fold 1: train 5-25, test 25-45
        // fold 2: train 10-30, test 30-50
        // fold 3: train 15-35, test 35-55 → past total → stop
        assertThat(folds).hasSize(3)
        assertThat(folds[1].second).isEqualTo(range(25, 45))
    }

    @Test
    fun `off-by-one stops when testEnd exceeds total`() {
        val total = range(0, 25)
        val folds =
            rollingWindows(
                total = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(5),
            )
        // fold 0: train 0-20, test 20-30 → testEnd 30 > total.to 25 → stop, no folds
        assertThat(folds).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WindowsRollingTest*'`
Expected: FAIL — `rollingWindows` not defined.

- [ ] **Step 3: Implement `rollingWindows`**

Create `src/main/kotlin/com/qkt/backtest/walkforward/Windows.kt`:

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.common.TimeRange
import java.time.Duration

internal fun rollingWindows(
    total: TimeRange,
    trainSize: Duration,
    testSize: Duration,
    stepSize: Duration,
): List<Pair<TimeRange, TimeRange>> {
    val folds = mutableListOf<Pair<TimeRange, TimeRange>>()
    var trainStart = total.from
    while (true) {
        val trainEnd = trainStart.plus(trainSize)
        val testEnd = trainEnd.plus(testSize)
        if (testEnd > total.to) break
        folds.add(TimeRange(trainStart, trainEnd) to TimeRange(trainEnd, testEnd))
        trainStart = trainStart.plus(stepSize)
    }
    return folds
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WindowsRollingTest*'`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/walkforward/Windows.kt \
        src/test/kotlin/com/qkt/backtest/walkforward/WindowsRollingTest.kt
git commit -m "feat(backtest): add rollingWindows pure function for walk-forward"
```

---

### Task 2: `concatenate` curve helper

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/walkforward/Windows.kt`
- Create: `src/test/kotlin/com/qkt/backtest/walkforward/WindowsConcatenateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.EquitySample
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WindowsConcatenateTest {
    @Test
    fun `empty list returns empty`() {
        assertThat(concatenate(emptyList<List<EquitySample>>())).isEmpty()
    }

    @Test
    fun `single curve passes through unchanged`() {
        val curve =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
                EquitySample(2L, BigDecimal("15")),
            )
        val out = concatenate(listOf(curve))
        assertThat(out).hasSize(3)
        assertThat(out[2].equity).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `multi-fold cumulative offsets each fold by prior total`() {
        val foldA =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
            )
        val foldB =
            listOf(
                EquitySample(2L, BigDecimal("0")),
                EquitySample(3L, BigDecimal("5")),
            )
        // foldA ends at 10. foldB samples become 10, 15. Total length 4.
        val out = concatenate(listOf(foldA, foldB))
        assertThat(out).hasSize(4)
        assertThat(out[0].equity).isEqualByComparingTo(BigDecimal("0"))
        assertThat(out[1].equity).isEqualByComparingTo(BigDecimal("10"))
        assertThat(out[2].equity).isEqualByComparingTo(BigDecimal("10"))
        assertThat(out[3].equity).isEqualByComparingTo(BigDecimal("15"))
    }

    @Test
    fun `empty fold in middle does not affect offset`() {
        val foldA =
            listOf(
                EquitySample(0L, BigDecimal("0")),
                EquitySample(1L, BigDecimal("10")),
            )
        val foldEmpty = emptyList<EquitySample>()
        val foldC =
            listOf(
                EquitySample(2L, BigDecimal("0")),
                EquitySample(3L, BigDecimal("3")),
            )
        val out = concatenate(listOf(foldA, foldEmpty, foldC))
        assertThat(out).hasSize(4)
        assertThat(out.last().equity).isEqualByComparingTo(BigDecimal("13"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WindowsConcatenateTest*'`
Expected: FAIL — `concatenate` not defined.

- [ ] **Step 3: Add `concatenate` to `Windows.kt`**

Append to `src/main/kotlin/com/qkt/backtest/walkforward/Windows.kt`:

```kotlin
import com.qkt.backtest.EquitySample
import com.qkt.common.Money
import java.math.BigDecimal

internal fun concatenate(curves: List<List<EquitySample>>): List<EquitySample> {
    val out = mutableListOf<EquitySample>()
    var runningOffset: BigDecimal = Money.ZERO
    for (curve in curves) {
        if (curve.isEmpty()) continue
        for (sample in curve) {
            out.add(EquitySample(sample.timestamp, sample.equity.add(runningOffset)))
        }
        runningOffset = runningOffset.add(curve.last().equity)
    }
    return out
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WindowsConcatenateTest*'`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/walkforward/Windows.kt \
        src/test/kotlin/com/qkt/backtest/walkforward/WindowsConcatenateTest.kt
git commit -m "feat(backtest): add concatenate helper for cumulative curve stitching"
```

---

### Task 3: `WalkForwardFold` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardFold.kt`

Trivial data class — no test (covered by harness tests).

- [ ] **Step 1: Create file**

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

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardFold.kt
git commit -m "feat(backtest): add WalkForwardFold data class"
```

---

### Task 4: `WalkForwardResult` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardResult.kt`

- [ ] **Step 1: Create file**

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

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardResult.kt
git commit -m "feat(backtest): add WalkForwardResult data class"
```

---

### Task 5: `WalkForwardHarness` constructor + validation

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardHarness.kt` (validation only)
- Create: `src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardHarnessValidationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WalkForwardHarnessValidationTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")
    private val total = TimeRange(t0, t0.plus(Duration.ofDays(60)))

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun bt(): Backtest =
        Backtest(
            strategies = listOf("s" to noopStrategy),
            ticks = listOf(Tick("X", Money.of("100"), 1L)),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `parallelism less than 1 fails`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
                parallelism = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parallelism")
    }

    @Test
    fun `topN less than 1 fails`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
                topN = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("topN")
    }

    @Test
    fun `non-positive sizes fail`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ZERO,
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("trainSize")
    }

    @Test
    fun `empty configs fails`() {
        assertThatThrownBy {
            WalkForwardHarness<Int>(
                configs = emptyList(),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("configs")
    }

    @Test
    fun `duplicate labels fail`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1, "a" to 2),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unique")
    }

    @Test
    fun `total range too short fails`() {
        assertThatThrownBy {
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = TimeRange(t0, t0.plus(Duration.ofDays(20))),
                trainSize = Duration.ofDays(15),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(5),
                scoreOf = { BigDecimal.ZERO },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("totalRange")
    }

    @Test
    fun `valid construction succeeds`() {
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { _, _, _ -> bt() },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        assertThat(harness).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WalkForwardHarnessValidationTest*'`
Expected: FAIL — `WalkForwardHarness` not defined.

- [ ] **Step 3: Create the file with constructor + validation only**

Create `src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardHarness.kt`:

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
        require(!trainSize.isZero && !trainSize.isNegative) { "trainSize must be positive, got $trainSize" }
        require(!testSize.isZero && !testSize.isNegative) { "testSize must be positive, got $testSize" }
        require(!stepSize.isZero && !stepSize.isNegative) { "stepSize must be positive, got $stepSize" }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) {
            "config labels must be unique: ${configs.map { it.first }}"
        }
        require(configs.all { it.first.isNotBlank() }) { "config labels must be non-blank" }
        require(trainSize.plus(testSize).toMillis() <= totalRange.durationMs) {
            "totalRange duration (${totalRange.durationMs}ms) too short for trainSize + testSize"
        }
    }

    fun run(): WalkForwardResult<C> = error("not yet implemented; see Task 6")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WalkForwardHarnessValidationTest*'`
Expected: PASS, all 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardHarness.kt \
        src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardHarnessValidationTest.kt
git commit -m "feat(backtest): add WalkForwardHarness constructor with input validation"
```

---

### Task 6: Implement `WalkForwardHarness.run()`

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardHarness.kt`
- Create: `src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardHarnessTest.kt`

- [ ] **Step 1: Write the failing test**

The test uses an in-process synthetic tick stream. The harness's `backtestFactory` constructs a `Backtest` from a tick list filtered to the requested range. We use `Tick(symbol, price, timestamp)` directly so no `MarketSource` plumbing is needed in the test.

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WalkForwardHarnessTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticksForRange(range: TimeRange): List<Tick> {
        val startMs = range.from.toEpochMilli()
        val endMs = range.to.toEpochMilli()
        return generateSequence(startMs) { it + 60_000L }
            .takeWhile { it < endMs }
            .map { ms -> Tick("X", Money.of("100"), ms) }
            .toList()
    }

    @Test
    fun `harness runs N folds and returns aggregate`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(60)))
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1, "b" to 2, "c" to 3),
                backtestFactory = { label, _, range ->
                    Backtest(
                        strategies = listOf(label to noopStrategy),
                        ticks = ticksForRange(range),
                        candleWindow = TimeWindow.ONE_MINUTE,
                        initialTimestamp = range.from.toEpochMilli(),
                    )
                },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { it.global.totalPnL },
            )

        val result = harness.run()

        assertThat(result.folds).hasSize(4)
        assertThat(result.folds.first().trainRange.from).isEqualTo(t0)
        assertThat(result.folds.last().testRange.to).isEqualTo(t0.plus(Duration.ofDays(60)))
        assertThat(result.folds.all { it.winnerLabel in listOf("a", "b", "c") }).isTrue()
        assertThat(result.winnerCounts.values.sum()).isEqualTo(4)
        assertThat(result.concatenatedTestCurve).isNotEmpty()
    }

    @Test
    fun `topConfigs sorted descending and limited to topN`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4),
                backtestFactory = { label, _, range ->
                    Backtest(
                        strategies = listOf(label to noopStrategy),
                        ticks = ticksForRange(range),
                        candleWindow = TimeWindow.ONE_MINUTE,
                        initialTimestamp = range.from.toEpochMilli(),
                    )
                },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { it.global.totalPnL },
                topN = 2,
            )
        val result = harness.run()

        for (fold in result.folds) {
            assertThat(fold.topConfigs).hasSizeLessThanOrEqualTo(2)
            for (i in 0 until fold.topConfigs.size - 1) {
                assertThat(fold.topConfigs[i].second)
                    .isGreaterThanOrEqualTo(fold.topConfigs[i + 1].second)
            }
        }
    }

    @Test
    fun `concatenated curve length equals sum of test curve lengths`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("a" to 1),
                backtestFactory = { label, _, range ->
                    Backtest(
                        strategies = listOf(label to noopStrategy),
                        ticks = ticksForRange(range),
                        candleWindow = TimeWindow.ONE_MINUTE,
                        initialTimestamp = range.from.toEpochMilli(),
                    )
                },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { it.global.totalPnL },
            )
        val result = harness.run()

        val expectedLength =
            result.folds.sumOf { it.testResult.global.equityCurve.size }
        assertThat(result.concatenatedTestCurve).hasSize(expectedLength)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WalkForwardHarnessTest*'`
Expected: FAIL — `run()` throws `not yet implemented`.

- [ ] **Step 3: Implement `run()`**

Replace the body of `src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardHarness.kt`:

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.common.Money
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
        require(!trainSize.isZero && !trainSize.isNegative) { "trainSize must be positive, got $trainSize" }
        require(!testSize.isZero && !testSize.isNegative) { "testSize must be positive, got $testSize" }
        require(!stepSize.isZero && !stepSize.isNegative) { "stepSize must be positive, got $stepSize" }
        require(configs.isNotEmpty()) { "configs must not be empty" }
        require(configs.map { it.first }.toSet().size == configs.size) {
            "config labels must be unique: ${configs.map { it.first }}"
        }
        require(configs.all { it.first.isNotBlank() }) { "config labels must be non-blank" }
        require(trainSize.plus(testSize).toMillis() <= totalRange.durationMs) {
            "totalRange duration (${totalRange.durationMs}ms) too short for trainSize + testSize"
        }
    }

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
                val winnerScore = scoreOf(winner.result)
                val top = ranked.take(topN).map { it.label to scoreOf(it.result) }
                val testBacktest = backtestFactory(winner.label, winner.config, testRange)
                val testResult = testBacktest.run()
                WalkForwardFold(
                    trainRange = trainRange,
                    testRange = testRange,
                    winnerLabel = winner.label,
                    winnerConfig = winner.config,
                    trainScore = winnerScore,
                    testResult = testResult,
                    topConfigs = top,
                )
            }

        val trainScores = folds.map { it.trainScore }
        val testScores = folds.map { scoreOf(it.testResult) }

        return WalkForwardResult(
            folds = folds,
            winnerCounts = folds.groupingBy { it.winnerLabel }.eachCount(),
            meanTrainScore = mean(trainScores),
            meanTestScore = mean(testScores),
            concatenatedTestCurve = concatenate(folds.map { it.testResult.global.equityCurve }),
        )
    }
}

private fun mean(values: List<BigDecimal>): BigDecimal {
    if (values.isEmpty()) return Money.ZERO
    return values
        .fold(Money.ZERO) { a, v -> a.add(v) }
        .divide(BigDecimal(values.size), Money.CONTEXT)
        .setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WalkForwardHarnessTest*'`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/walkforward/WalkForwardHarness.kt \
        src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardHarnessTest.kt
git commit -m "feat(backtest): implement WalkForwardHarness run loop"
```

---

### Task 7: Fail-fast test for harness

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardHarnessFailFastTest.kt`

The harness inherits fail-fast from `BacktestSweep` and `Backtest.run()`. This task locks in the behavior with a test.

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WalkForwardHarnessFailFastTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticksForRange(range: TimeRange): List<Tick> {
        val startMs = range.from.toEpochMilli()
        val endMs = range.to.toEpochMilli()
        return generateSequence(startMs) { it + 60_000L }
            .takeWhile { it < endMs }
            .map { ms -> Tick("X", Money.of("100"), ms) }
            .toList()
    }

    @Test
    fun `harness propagates exception from backtestFactory`() {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("ok" to 1, "boom" to 2),
                backtestFactory = { _, config, range ->
                    if (config == 2) error("boom from config 2")
                    Backtest(
                        strategies = listOf("s" to noopStrategy),
                        ticks = ticksForRange(range),
                        candleWindow = TimeWindow.ONE_MINUTE,
                        initialTimestamp = range.from.toEpochMilli(),
                    )
                },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )

        assertThatThrownBy { harness.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom from config 2")
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests '*WalkForwardHarnessFailFastTest*'`
Expected: PASS — exception propagates from the first fold's sweep.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardHarnessFailFastTest.kt
git commit -m "test(backtest): cover fail-fast for WalkForwardHarness"
```

---

### Task 8: Determinism test

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardDeterminismTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.backtest.walkforward

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WalkForwardDeterminismTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticksForRange(range: TimeRange): List<Tick> {
        val startMs = range.from.toEpochMilli()
        val endMs = range.to.toEpochMilli()
        return generateSequence(startMs) { it + 60_000L }
            .takeWhile { it < endMs }
            .map { ms -> Tick("X", Money.of("100"), ms) }
            .toList()
    }

    private fun newHarness(): WalkForwardHarness<Int> {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        return WalkForwardHarness(
            configs = listOf("a" to 1, "b" to 2),
            backtestFactory = { label, _, range ->
                Backtest(
                    strategies = listOf(label to noopStrategy),
                    ticks = ticksForRange(range),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    initialTimestamp = range.from.toEpochMilli(),
                )
            },
            totalRange = total,
            trainSize = Duration.ofDays(20),
            testSize = Duration.ofDays(10),
            stepSize = Duration.ofDays(10),
            scoreOf = { it.global.totalPnL },
        )
    }

    @Test
    fun `two runs produce equal results`() {
        val a = newHarness().run()
        val b = newHarness().run()
        assertThat(a.folds.size).isEqualTo(b.folds.size)
        assertThat(a.winnerCounts).isEqualTo(b.winnerCounts)
        assertThat(a.meanTrainScore).isEqualByComparingTo(b.meanTrainScore)
        assertThat(a.meanTestScore).isEqualByComparingTo(b.meanTestScore)
        assertThat(a.concatenatedTestCurve).isEqualTo(b.concatenatedTestCurve)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests '*WalkForwardDeterminismTest*'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/walkforward/WalkForwardDeterminismTest.kt
git commit -m "test(backtest): add determinism check for WalkForwardHarness"
```

---

### Task 9: `WalkForwardReportWriter`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/report/WalkForwardReportWriter.kt`
- Create: `src/test/kotlin/com/qkt/backtest/report/WalkForwardReportWriterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.walkforward.WalkForwardHarness
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WalkForwardReportWriterTest {
    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticksForRange(range: TimeRange): List<Tick> {
        val startMs = range.from.toEpochMilli()
        val endMs = range.to.toEpochMilli()
        return generateSequence(startMs) { it + 60_000L }
            .takeWhile { it < endMs }
            .map { ms -> Tick("X", Money.of("100"), ms) }
            .toList()
    }

    private fun newHarness(): WalkForwardHarness<String> {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        return WalkForwardHarness(
            configs = listOf("ema_9_21" to "fast=9_slow=21", "ema_12_26" to "fast=12_slow=26"),
            backtestFactory = { label, _, range ->
                Backtest(
                    strategies = listOf(label to noopStrategy),
                    ticks = ticksForRange(range),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    initialTimestamp = range.from.toEpochMilli(),
                    cadence = SampleCadence.CANDLE_CLOSE,
                )
            },
            totalRange = total,
            trainSize = Duration.ofDays(20),
            testSize = Duration.ofDays(10),
            stepSize = Duration.ofDays(10),
            scoreOf = { it.global.totalPnL },
        )
    }

    @Test
    fun `writer produces summary plus per-fold dirs`(
        @TempDir dir: Path,
    ) {
        val result = newHarness().run()

        WalkForwardReportWriter(dir).write(result)

        assertThat(dir.resolve("walkforward_summary.json")).exists()
        assertThat(dir.resolve("walkforward_summary.csv")).exists()
        assertThat(dir.resolve("concatenated_equity.csv")).exists()
        assertThat(dir.resolve("winner_counts.csv")).exists()
        for (i in 1..result.folds.size) {
            val padded = "fold_%03d".format(i)
            assertThat(dir.resolve("folds/$padded/result.json")).exists()
            assertThat(dir.resolve("folds/$padded/equity_global.csv")).exists()
        }

        val csv = Files.readString(dir.resolve("walkforward_summary.csv"))
        val lines = csv.trim().lines()
        assertThat(lines.size).isEqualTo(result.folds.size + 1)
        assertThat(lines[0]).contains("foldIndex,trainStart,trainEnd,testStart,testEnd")

        val eq = Files.readString(dir.resolve("concatenated_equity.csv"))
        assertThat(eq.lines().first()).isEqualTo("timestamp,equity")
    }

    @Test
    fun `unsafe winner label is rejected before any file is written`(
        @TempDir dir: Path,
    ) {
        val total = TimeRange(t0, t0.plus(Duration.ofDays(40)))
        val harness =
            WalkForwardHarness(
                configs = listOf("../danger" to "ok"),
                backtestFactory = { label, _, range ->
                    Backtest(
                        strategies = listOf(label to noopStrategy),
                        ticks = ticksForRange(range),
                        candleWindow = TimeWindow.ONE_MINUTE,
                        initialTimestamp = range.from.toEpochMilli(),
                    )
                },
                totalRange = total,
                trainSize = Duration.ofDays(20),
                testSize = Duration.ofDays(10),
                stepSize = Duration.ofDays(10),
                scoreOf = { BigDecimal.ZERO },
            )
        val result = harness.run()

        assertThatThrownBy { WalkForwardReportWriter(dir).write(result) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*WalkForwardReportWriterTest*'`
Expected: FAIL — `WalkForwardReportWriter` not defined.

- [ ] **Step 3: Implement the writer**

Create `src/main/kotlin/com/qkt/backtest/report/WalkForwardReportWriter.kt`:

```kotlin
package com.qkt.backtest.report

import com.qkt.backtest.EquitySample
import com.qkt.backtest.walkforward.WalkForwardFold
import com.qkt.backtest.walkforward.WalkForwardResult
import java.nio.file.Files
import java.nio.file.Path

class WalkForwardReportWriter(
    private val dir: Path,
) {
    private val safeLabel = Regex("[A-Za-z0-9_-]+")

    fun <C> write(result: WalkForwardResult<C>) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (fold in result.folds) {
            require(safeLabel.matches(fold.winnerLabel)) {
                "Unsafe winner label for filesystem write: ${fold.winnerLabel}"
            }
            val cs = fold.winnerConfig.toString()
            require(!cs.contains(',') && !cs.contains('"') && !cs.contains('\n')) {
                "winnerConfig string must not contain comma, double-quote, or newline: $cs"
            }
        }

        Files.writeString(dir.resolve("walkforward_summary.csv"), renderSummaryCsv(result))
        Files.writeString(dir.resolve("walkforward_summary.json"), renderJson(result))
        Files.writeString(dir.resolve("concatenated_equity.csv"), renderEquityCsv(result.concatenatedTestCurve))
        Files.writeString(dir.resolve("winner_counts.csv"), renderWinnerCountsCsv(result.winnerCounts))

        val foldsDir = dir.resolve("folds")
        Files.createDirectories(foldsDir)
        for ((i, fold) in result.folds.withIndex()) {
            val padded = "fold_%03d".format(i + 1)
            val perFold = foldsDir.resolve(padded)
            Files.createDirectories(perFold)
            BacktestReportWriter(perFold).write(fold.testResult)
        }
    }

    private fun <C> renderSummaryCsv(result: WalkForwardResult<C>): String {
        val sb =
            StringBuilder(
                "foldIndex,trainStart,trainEnd,testStart,testEnd,winnerLabel,winnerConfig,trainScore,testTotalPnL,testMaxDrawdown\n",
            )
        for ((i, fold) in result.folds.withIndex()) {
            val r = fold.testResult.global
            sb
                .append(i + 1).append(',')
                .append(fold.trainRange.from).append(',')
                .append(fold.trainRange.to).append(',')
                .append(fold.testRange.from).append(',')
                .append(fold.testRange.to).append(',')
                .append(fold.winnerLabel).append(',')
                .append(fold.winnerConfig.toString()).append(',')
                .append(fold.trainScore.toPlainString()).append(',')
                .append(r.totalPnL.toPlainString()).append(',')
                .append(r.maxDrawdown.toPlainString()).append('\n')
        }
        return sb.toString()
    }

    private fun renderEquityCsv(curve: List<EquitySample>): String {
        val sb = StringBuilder("timestamp,equity\n")
        for (s in curve) {
            sb.append(s.timestamp).append(',').append(s.equity.toPlainString()).append('\n')
        }
        return sb.toString()
    }

    private fun renderWinnerCountsCsv(counts: Map<String, Int>): String {
        val sb = StringBuilder("configLabel,winCount\n")
        for ((label, count) in counts.entries.sortedByDescending { it.value }) {
            sb.append(label).append(',').append(count).append('\n')
        }
        return sb.toString()
    }

    private fun <C> renderJson(result: WalkForwardResult<C>): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"folds\": [")
        if (result.folds.isNotEmpty()) {
            sb.append('\n')
            for ((i, fold) in result.folds.withIndex()) {
                sb.append("    ").append(renderFoldJson(i + 1, fold))
                if (i != result.folds.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("  ]")
        } else {
            sb.append("]")
        }
        sb.append(",\n  \"winnerCounts\": {")
        val entries = result.winnerCounts.entries.toList()
        for ((i, e) in entries.withIndex()) {
            sb.append("\n    ").append(ReportSerializer.jsonString(e.key)).append(": ").append(e.value)
            if (i != entries.size - 1) sb.append(",")
        }
        if (entries.isNotEmpty()) sb.append("\n  ")
        sb.append("},\n")
        sb.append("  \"meanTrainScore\": ").append(ReportSerializer.jsonBigDecimal(result.meanTrainScore)).append(",\n")
        sb.append("  \"meanTestScore\": ").append(ReportSerializer.jsonBigDecimal(result.meanTestScore)).append("\n")
        sb.append("}")
        return sb.toString()
    }

    private fun <C> renderFoldJson(
        index: Int,
        fold: WalkForwardFold<C>,
    ): String {
        val r = fold.testResult.global
        val sb = StringBuilder("{")
        sb.append("\n      \"foldIndex\": ").append(index).append(",")
        sb.append("\n      \"trainRange\": {\"from\": ")
            .append(ReportSerializer.jsonString(fold.trainRange.from.toString()))
            .append(", \"to\": ")
            .append(ReportSerializer.jsonString(fold.trainRange.to.toString()))
            .append("},")
        sb.append("\n      \"testRange\": {\"from\": ")
            .append(ReportSerializer.jsonString(fold.testRange.from.toString()))
            .append(", \"to\": ")
            .append(ReportSerializer.jsonString(fold.testRange.to.toString()))
            .append("},")
        sb.append("\n      \"winnerLabel\": ").append(ReportSerializer.jsonString(fold.winnerLabel)).append(",")
        sb.append("\n      \"winnerConfig\": ").append(ReportSerializer.jsonString(fold.winnerConfig.toString())).append(",")
        sb.append("\n      \"trainScore\": ").append(ReportSerializer.jsonBigDecimal(fold.trainScore)).append(",")
        sb.append("\n      \"testTotalPnL\": ").append(ReportSerializer.jsonBigDecimal(r.totalPnL)).append(",")
        sb.append("\n      \"testMaxDrawdown\": ").append(ReportSerializer.jsonBigDecimal(r.maxDrawdown)).append(",")
        sb.append("\n      \"topConfigs\": [")
        if (fold.topConfigs.isNotEmpty()) {
            sb.append('\n')
            for ((i, p) in fold.topConfigs.withIndex()) {
                sb.append("        {\"label\": ")
                    .append(ReportSerializer.jsonString(p.first))
                    .append(", \"score\": ")
                    .append(ReportSerializer.jsonBigDecimal(p.second))
                    .append("}")
                if (i != fold.topConfigs.size - 1) sb.append(",")
                sb.append('\n')
            }
            sb.append("      ]")
        } else {
            sb.append("]")
        }
        sb.append("\n    }")
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*WalkForwardReportWriterTest*'`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/WalkForwardReportWriter.kt \
        src/test/kotlin/com/qkt/backtest/report/WalkForwardReportWriterTest.kt
git commit -m "feat(backtest): add WalkForwardReportWriter with summary and per-fold dirs"
```

---

### Task 10: Full build + ktlint pass

**Files:** none changed (or pure ktlint reformat).

- [ ] **Step 1: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

If ktlint fails, run `./gradlew ktlintFormat` and re-run build.

- [ ] **Step 2: Run precheck**

Run: `bash scripts/precheck.sh`
Expected: all green.

- [ ] **Step 3: If ktlint reformatted any files, commit**

```bash
git status -s
git add -u src/main/kotlin/com/qkt/backtest/walkforward/ \
           src/main/kotlin/com/qkt/backtest/report/ \
           src/test/kotlin/com/qkt/backtest/walkforward/ \
           src/test/kotlin/com/qkt/backtest/report/
git commit -m "style: ktlint format for new walk-forward sources"
```

If `git status` is clean, skip.

- [ ] **Step 4: Read commit log**

Run: `git log --oneline main..HEAD`
Expected: ~9-10 commits, each with a clean conventional-commit subject.

---

### Task 11: Phase 10c changelog

**Files:**
- Create: `docs/phases/phase-10c-walk-forward.md`

- [ ] **Step 1: Write the changelog**

Create `docs/phases/phase-10c-walk-forward.md`:

````markdown
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
- Merge commit: filled in at merge time.
````

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-10c-walk-forward.md
git commit -m "docs: phase 10c walk-forward changelog"
```

---

### Task 12: Final verification + finishing-a-development-branch

**Files:** none changed.

- [ ] **Step 1: Run full build one more time**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run precheck**

Run: `bash scripts/precheck.sh`
Expected: all green.

- [ ] **Step 3: Read commit log**

Run: `git log --oneline main..HEAD`
Expected: ~10-12 commits, each with a clean conventional-commit subject. No emoji, no AI footer.

- [ ] **Step 4: Hand off to finishing-a-development-branch**

Announce: "I'm using the finishing-a-development-branch skill to complete this work."

Invoke: `superpowers:finishing-a-development-branch`. Standard 4 options. qkt convention is `git merge --no-ff` with `merge: phase 10c walk-forward` on the merge commit.

- [ ] **Step 5: Push to origin**

After merge:

```bash
git push origin main
```

The pre-push hook runs precheck.

- [ ] **Step 6: Tag the release**

Per `docs/release-process.md`, Phase 10c is a sub-phase of Phase 10 → version `v0.10.2`.

```bash
MERGE_SHA=$(git log --merges --oneline | grep "phase 10c walk-forward" | awk '{print $1}')
git tag -a v0.10.2 "$MERGE_SHA" -m "phase 10c — walk-forward analysis"
git push origin v0.10.2
gh release create v0.10.2 \
  --title "v0.10.2 — phase 10c walk-forward analysis" \
  --notes-file docs/phases/phase-10c-walk-forward.md \
  --latest
```

- [ ] **Step 7: Backfill merge SHA into changelog**

```bash
sed -i "s|^- Merge commit: filled in at merge time.|- Merge commit: \`$MERGE_SHA\`|" \
  docs/phases/phase-10c-walk-forward.md
git add docs/phases/phase-10c-walk-forward.md
git commit -m "docs: link phase 10c changelog to merge commit"
git push origin main
```

---

## Self-Review Notes

Spec coverage check:

| Spec section | Plan task |
|---|---|
| §2 Goals — `WalkForwardHarness` | Tasks 5, 6 |
| §2 Goals — rolling windows | Task 1 |
| §2 Goals — concatenation | Task 2 |
| §2 Goals — `WalkForwardFold` | Task 3 |
| §2 Goals — `WalkForwardResult` | Task 4 |
| §2 Goals — `scoreOf` + `parallelism` + `topN` | Tasks 5, 6 |
| §2 Goals — fail-fast | Task 7 |
| §2 Goals — `WalkForwardReportWriter` | Task 9 |
| §5 Data model | Tasks 3, 4 |
| §6 Harness API | Tasks 5, 6 |
| §7 Windowing | Task 1 |
| §8 Per-fold loop | Task 6 |
| §9 Concatenation | Task 2 |
| §10 Writer | Task 9 |
| §11 Testing | Tasks 1, 2, 5, 6, 7, 8, 9 |
| §13 Success criteria | Task 11 (changelog) |

Type-consistency check:
- `(label, config, range) -> Backtest` factory signature consistent across spec §6 and Tasks 5, 6, 7, 8, 9.
- `WalkForwardFold<C>` field order matches between Task 3 and Tasks 6, 9.
- `WalkForwardResult<C>` aggregates match between Task 4 and Tasks 6, 9.
- `BacktestSweep` reuse from Phase 10b: `(label, config) -> Backtest` factory consistent with the partial application used in Task 6.

No placeholders. Every step has runnable code or shell commands.
