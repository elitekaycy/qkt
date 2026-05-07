# Phase 10b — Parameter Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `BacktestSweep<C>` harness that runs N strategy configurations through Phase 10's Backtest engine and produces a ranked, persistable result, with sequential default and opt-in fixed-pool parallelism.

**Architecture:** New package `com.qkt.backtest.sweep` holds `BacktestSweep`, `SweepRun`, `SweepResult`. `SweepReportWriter` extends Phase 10's `BacktestReportWriter` with a top-level summary CSV/JSON plus per-run subdirectories. Caller passes a `(label, config) -> Backtest` factory invoked once per config — the factory closes over `config` to vary strategy/warmup/rules per run. Pure additive; no changes to existing Backtest types.

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 17 (`Executors.newFixedThreadPool`), no new external dependencies.

**Spec:** `docs/superpowers/specs/2026-05-07-trading-engine-phase10b-design.md`

**Branch:** `phase10b-parameter-sweep` (already created and active).

---

## File Structure

### New files

```
src/main/kotlin/com/qkt/backtest/sweep/
├── SweepRun.kt
├── SweepResult.kt
└── BacktestSweep.kt

src/main/kotlin/com/qkt/backtest/report/
└── SweepReportWriter.kt

src/test/kotlin/com/qkt/backtest/sweep/
├── BacktestSweepSequentialTest.kt
├── BacktestSweepParallelTest.kt
├── BacktestSweepFailFastTest.kt
├── BacktestSweepValidationTest.kt
└── SweepResultTest.kt

src/test/kotlin/com/qkt/backtest/report/
└── SweepReportWriterTest.kt
```

### Modified files

None. Phase 10b is purely additive.

---

## Tasks

### Task 1: Add `SweepRun` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/sweep/SweepRun.kt`

Trivial data class. No tests (covered by sweep tests later).

- [ ] **Step 1: Create file**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult

data class SweepRun<C>(
    val label: String,
    val config: C,
    val result: BacktestResult,
)
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/sweep/SweepRun.kt
git commit -m "feat(backtest): add SweepRun data class"
```

---

### Task 2: Add `SweepResult` data class with ranking helper

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/sweep/SweepResult.kt`
- Create: `src/test/kotlin/com/qkt/backtest/sweep/SweepResultTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/backtest/sweep/SweepResultTest.kt`:

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SweepResultTest {
    private fun report(totalPnL: String): PerformanceReport =
        PerformanceReport(
            realizedTotal = Money.of(totalPnL),
            unrealizedTotal = Money.ZERO,
            totalPnL = Money.of(totalPnL),
            tradeCount = 0,
            winRate = Money.ZERO,
            maxDrawdown = Money.ZERO,
            profitFactor = null,
            avgWin = Money.ZERO,
            avgLoss = Money.ZERO,
            largestWin = Money.ZERO,
            largestLoss = Money.ZERO,
            maxConsecutiveLosses = 0,
            sharpeRatio = null,
            calmarRatio = null,
            equityCurve = emptyList(),
        )

    private fun result(totalPnL: String): BacktestResult =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report(totalPnL),
            perStrategy = emptyMap(),
            cadence = SampleCadence.CANDLE_CLOSE,
        )

    @Test
    fun `byLabel returns matching run`() {
        val sr =
            SweepResult(
                runs =
                    listOf(
                        SweepRun("a", 1, result("10")),
                        SweepRun("b", 2, result("20")),
                    ),
            )
        assertThat(sr.byLabel("a")?.config).isEqualTo(1)
        assertThat(sr.byLabel("b")?.config).isEqualTo(2)
    }

    @Test
    fun `byLabel returns null for unknown label`() {
        val sr = SweepResult(runs = listOf(SweepRun("a", 1, result("10"))))
        assertThat(sr.byLabel("missing")).isNull()
    }

    @Test
    fun `rankedBy sorts descending by score`() {
        val sr =
            SweepResult(
                runs =
                    listOf(
                        SweepRun("low", 1, result("5")),
                        SweepRun("high", 2, result("100")),
                        SweepRun("mid", 3, result("50")),
                    ),
            )
        val ranked = sr.rankedBy { it.global.totalPnL }
        assertThat(ranked.map { it.label }).containsExactly("high", "mid", "low")
    }

    @Test
    fun `rankedBy treats null score consumer as caller responsibility`() {
        val sr =
            SweepResult(
                runs =
                    listOf(
                        SweepRun("a", 1, result("10")),
                        SweepRun("b", 2, result("20")),
                    ),
            )
        // Caller maps null sharpe to ZERO in their score function.
        val ranked = sr.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }
        assertThat(ranked.map { it.label }).containsExactlyInAnyOrder("a", "b")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SweepResultTest*'`
Expected: FAIL — `SweepResult` not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/sweep/SweepResult.kt`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SweepResultTest*'`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/sweep/SweepResult.kt \
        src/test/kotlin/com/qkt/backtest/sweep/SweepResultTest.kt
git commit -m "feat(backtest): add SweepResult with byLabel and rankedBy helpers"
```

---

### Task 3: Add `BacktestSweep` constructor + validation

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt` (validation only at this stage)
- Create: `src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepValidationTest.kt`

`run()` body lands in Tasks 4 + 5. This task locks in the constructor surface + validation.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestSweepValidationTest {
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
            ticks = listOf(Tick("X", Money.of("100"), 1_000L)),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `parallelism less than 1 fails`() {
        assertThatThrownBy {
            BacktestSweep(
                configs = listOf("a" to 1),
                backtestFactory = { _, _ -> bt() },
                parallelism = 0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("parallelism must be >= 1")
    }

    @Test
    fun `empty configs fails`() {
        assertThatThrownBy {
            BacktestSweep<Int>(
                configs = emptyList(),
                backtestFactory = { _, _ -> bt() },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("configs must not be empty")
    }

    @Test
    fun `duplicate labels fail`() {
        assertThatThrownBy {
            BacktestSweep(
                configs = listOf("a" to 1, "a" to 2),
                backtestFactory = { _, _ -> bt() },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("config labels must be unique")
    }

    @Test
    fun `blank label fails`() {
        assertThatThrownBy {
            BacktestSweep(
                configs = listOf("" to 1),
                backtestFactory = { _, _ -> bt() },
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("config labels must be non-blank")
    }

    @Test
    fun `valid construction succeeds`() {
        val sweep =
            BacktestSweep(
                configs = listOf("a" to 1, "b" to 2),
                backtestFactory = { _, _ -> bt() },
                parallelism = 1,
            )
        assertThat(sweep).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BacktestSweepValidationTest*'`
Expected: FAIL — `BacktestSweep` not defined.

- [ ] **Step 3: Implement constructor + validation only**

Create `src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt`:

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest

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

    fun run(): SweepResult<C> = error("not yet implemented; see Tasks 4 + 5")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BacktestSweepValidationTest*'`
Expected: PASS, all 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt \
        src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepValidationTest.kt
git commit -m "feat(backtest): add BacktestSweep constructor with input validation"
```

---

### Task 4: Implement `runSequential`

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt`
- Create: `src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepSequentialTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestSweepSequentialTest {
    private fun ticks(): List<Tick> =
        (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `runs each config once and preserves input order`() {
        val invocations = AtomicInteger(0)
        val sweep =
            BacktestSweep(
                configs = listOf("c1" to 1, "c2" to 2, "c3" to 3),
                backtestFactory = { label, config ->
                    invocations.incrementAndGet()
                    assertThat(label).startsWith("c")
                    Backtest(
                        strategies = listOf("s$config" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
                parallelism = 1,
            )

        val result = sweep.run()

        assertThat(invocations.get()).isEqualTo(3)
        assertThat(result.runs.map { it.label }).containsExactly("c1", "c2", "c3")
        assertThat(result.runs.map { it.config }).containsExactly(1, 2, 3)
        assertThat(result.runs.all { it.result.global.tradeCount == 0 }).isTrue()
    }

    @Test
    fun `result objects carry label config and BacktestResult`() {
        val sweep =
            BacktestSweep(
                configs = listOf("only" to "value"),
                backtestFactory = { _, _ ->
                    Backtest(
                        strategies = listOf("s" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
            )

        val result = sweep.run()
        val run = result.runs.single()
        assertThat(run.label).isEqualTo("only")
        assertThat(run.config).isEqualTo("value")
        assertThat(run.result.cadence).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BacktestSweepSequentialTest*'`
Expected: FAIL — `run()` throws `not yet implemented`.

- [ ] **Step 3: Implement `runSequential`**

Edit `src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt` — replace the body:

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest

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

    private fun runParallel(): SweepResult<C> = error("parallel mode lands in Task 5")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BacktestSweepSequentialTest*'`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt \
        src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepSequentialTest.kt
git commit -m "feat(backtest): implement BacktestSweep sequential mode"
```

---

### Task 5: Implement `runParallel`

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt`
- Create: `src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepParallelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestSweepParallelTest {
    private fun ticks(): List<Tick> =
        (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `parallel runs preserve input order`() {
        val sweep =
            BacktestSweep(
                configs = listOf("c1" to 1, "c2" to 2, "c3" to 3, "c4" to 4),
                backtestFactory = { _, config ->
                    Backtest(
                        strategies = listOf("s$config" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
                parallelism = 2,
            )

        val result = sweep.run()

        assertThat(result.runs.map { it.label }).containsExactly("c1", "c2", "c3", "c4")
        assertThat(result.runs.map { it.config }).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun `parallel and sequential produce equivalent results`() {
        val configs = listOf("a" to 1, "b" to 2, "c" to 3)
        fun build(p: Int) =
            BacktestSweep(
                configs = configs,
                backtestFactory = { _, config ->
                    Backtest(
                        strategies = listOf("s$config" to noopStrategy),
                        ticks = ticks(),
                        candleWindow = TimeWindow.ONE_MINUTE,
                    )
                },
                parallelism = p,
            )

        val seq = build(1).run()
        val par = build(4).run()

        assertThat(par.runs.map { it.label }).isEqualTo(seq.runs.map { it.label })
        for (i in seq.runs.indices) {
            assertThat(par.runs[i].result.global.totalPnL)
                .isEqualByComparingTo(seq.runs[i].result.global.totalPnL)
        }
    }

    @Test
    fun `parallel calls backtestFactory exactly once per config`() {
        val invocations = AtomicInteger(0)
        BacktestSweep(
            configs = listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4),
            backtestFactory = { _, _ ->
                invocations.incrementAndGet()
                Backtest(
                    strategies = listOf("s" to noopStrategy),
                    ticks = ticks(),
                    candleWindow = TimeWindow.ONE_MINUTE,
                )
            },
            parallelism = 4,
        ).run()

        assertThat(invocations.get()).isEqualTo(4)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*BacktestSweepParallelTest*'`
Expected: FAIL — `runParallel` throws `parallel mode lands in Task 5`.

- [ ] **Step 3: Implement `runParallel`**

Edit `src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*BacktestSweepParallelTest*'`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/sweep/BacktestSweep.kt \
        src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepParallelTest.kt
git commit -m "feat(backtest): implement BacktestSweep parallel mode with fixed thread pool"
```

---

### Task 6: Fail-fast tests for sequential and parallel

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepFailFastTest.kt`

The implementation already supports fail-fast (sequential propagates naturally; parallel uses `executor.shutdownNow()` + re-throw). This task just locks in the behavior with tests.

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BacktestSweepFailFastTest {
    private fun ticks(): List<Tick> =
        (1..3).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

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
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
        )

    @Test
    fun `sequential propagates first exception immediately`() {
        val sweep =
            BacktestSweep(
                configs = listOf("ok" to 1, "boom" to 2, "never" to 3),
                backtestFactory = { _, config ->
                    if (config == 2) error("boom from config 2")
                    bt()
                },
                parallelism = 1,
            )

        assertThatThrownBy { sweep.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom from config 2")
    }

    @Test
    fun `parallel propagates the first exception in input order`() {
        val sweep =
            BacktestSweep(
                configs = listOf("ok" to 1, "boom" to 2, "ok2" to 3),
                backtestFactory = { _, config ->
                    if (config == 2) error("boom from config 2")
                    bt()
                },
                parallelism = 3,
            )

        assertThatThrownBy { sweep.run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("boom from config 2")
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests '*BacktestSweepFailFastTest*'`
Expected: PASS — both modes throw with the original cause's message.

If the parallel test fails because `Future.get()` wraps the cause differently, inspect the actual exception. The implementation re-throws `e.cause ?: e`, so the cause is propagated. If the test sees `ExecutionException` instead, the implementation needs to be re-checked.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/sweep/BacktestSweepFailFastTest.kt
git commit -m "test(backtest): cover fail-fast for BacktestSweep sequential and parallel"
```

---

### Task 7: Add `SweepReportWriter`

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/report/SweepReportWriter.kt`
- Create: `src/test/kotlin/com/qkt/backtest/report/SweepReportWriterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.report

import com.qkt.backtest.Backtest
import com.qkt.backtest.SampleCadence
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepReportWriterTest {
    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun ticks(): List<Tick> =
        (1..5).map { i -> Tick("X", Money.of((100 + i).toString()), i * 60_000L) }

    private fun bt(label: String): Backtest =
        Backtest(
            strategies = listOf(label to noopStrategy),
            ticks = ticks(),
            candleWindow = TimeWindow.ONE_MINUTE,
            cadence = SampleCadence.CANDLE_CLOSE,
        )

    @Test
    fun `writer produces summary csv and json plus per-run dirs`(
        @TempDir dir: Path,
    ) {
        val sweep =
            BacktestSweep(
                configs = listOf("ema_9_21" to "fast=9,slow=21", "ema_12_26" to "fast=12,slow=26"),
                backtestFactory = { label, _ -> bt(label) },
            )
        val result = sweep.run()

        SweepReportWriter(dir).write(result)

        assertThat(dir.resolve("sweep_summary.csv")).exists()
        assertThat(dir.resolve("sweep_summary.json")).exists()
        assertThat(dir.resolve("runs/ema_9_21/result.json")).exists()
        assertThat(dir.resolve("runs/ema_12_26/result.json")).exists()
        assertThat(dir.resolve("runs/ema_9_21/equity_global.csv")).exists()
        assertThat(dir.resolve("runs/ema_9_21/trades.csv")).exists()

        val csv = Files.readString(dir.resolve("sweep_summary.csv"))
        val lines = csv.trim().lines()
        assertThat(lines.size).isEqualTo(3)  // header + 2 runs
        assertThat(lines[0])
            .isEqualTo("label,config,totalPnL,sharpeRatio,maxDrawdown,winRate,tradeCount,profitFactor,calmarRatio")
        assertThat(lines[1]).startsWith("ema_9_21,")
        assertThat(lines[2]).startsWith("ema_12_26,")

        val json = Files.readString(dir.resolve("sweep_summary.json"))
        assertThat(json).startsWith("[")
        assertThat(json).contains("\"label\": \"ema_9_21\"")
        assertThat(json).contains("\"config\": \"fast=9,slow=21\"")
    }

    @Test
    fun `unsafe label rejected before any file written`(
        @TempDir dir: Path,
    ) {
        val sweep =
            BacktestSweep(
                configs = listOf("../danger" to "ok"),
                backtestFactory = { label, _ -> bt(label) },
            )
        // The Backtest pipeline accepts the label (non-blank), but the writer rejects it.
        val result = sweep.run()

        assertThatThrownBy { SweepReportWriter(dir).write(result) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }

    @Test
    fun `config containing a comma is rejected`(
        @TempDir dir: Path,
    ) {
        val sweep =
            BacktestSweep(
                configs = listOf("ok" to "embeds,comma,unsafe"),
                backtestFactory = { label, _ -> bt(label) },
            )
        val result = sweep.run()

        assertThatThrownBy { SweepReportWriter(dir).write(result) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("comma")
        assertThat(Files.list(dir).count()).isEqualTo(0L)
    }
}
```

Note on the comma-rejection test: it asserts `config.toString()` containing a comma is rejected. The first test uses `fast=9,slow=21` which DOES contain commas — that test should NOT trigger the comma-reject. We need the writer to be smart: reject ONLY if the config will collide with the CSV column delimiter unsafely. The cleanest approach is to **validate stringified config at write time and reject any that contains `,`, `"`, or `\n`**. The first test will need configs without commas. Adjust the first test now:

Replace `"fast=9,slow=21"` with `"fast=9_slow=21"` and `"fast=12,slow=26"` with `"fast=12_slow=26"`. (Update the assertion `assertThat(json).contains("\"config\": \"fast=9_slow=21\"")` accordingly.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SweepReportWriterTest*'`
Expected: FAIL — `SweepReportWriter` not defined.

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/qkt/backtest/report/SweepReportWriter.kt`:

```kotlin
package com.qkt.backtest.report

import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.sweep.SweepResult
import com.qkt.backtest.sweep.SweepRun
import java.nio.file.Files
import java.nio.file.Path

class SweepReportWriter(
    private val dir: Path,
) {
    private val safeLabel = Regex("[A-Za-z0-9_-]+")

    fun <C> write(result: SweepResult<C>) {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }
        require(Files.isWritable(dir)) { "Directory not writable: $dir" }
        for (run in result.runs) {
            require(safeLabel.matches(run.label)) {
                "Unsafe label for filesystem write: ${run.label}"
            }
            val cs = run.config.toString()
            require(!cs.contains(',') && !cs.contains('"') && !cs.contains('\n')) {
                "Config string for label='${run.label}' must not contain comma, double-quote, or newline: $cs"
            }
        }

        Files.writeString(dir.resolve("sweep_summary.csv"), renderCsv(result))
        Files.writeString(dir.resolve("sweep_summary.json"), renderJson(result))

        val runsDir = dir.resolve("runs")
        Files.createDirectories(runsDir)
        for (run in result.runs) {
            val perRun = runsDir.resolve(run.label)
            Files.createDirectories(perRun)
            BacktestReportWriter(perRun).write(run.result)
        }
    }

    private fun <C> renderCsv(result: SweepResult<C>): String {
        val sb =
            StringBuilder(
                "label,config,totalPnL,sharpeRatio,maxDrawdown,winRate,tradeCount,profitFactor,calmarRatio\n",
            )
        for (run in result.runs) {
            val r: PerformanceReport = run.result.global
            sb
                .append(run.label).append(',')
                .append(run.config.toString()).append(',')
                .append(r.totalPnL.toPlainString()).append(',')
                .append(r.sharpeRatio?.toPlainString() ?: "").append(',')
                .append(r.maxDrawdown.toPlainString()).append(',')
                .append(r.winRate.toPlainString()).append(',')
                .append(r.tradeCount).append(',')
                .append(r.profitFactor?.toPlainString() ?: "").append(',')
                .append(r.calmarRatio?.toPlainString() ?: "").append('\n')
        }
        return sb.toString()
    }

    private fun <C> renderJson(result: SweepResult<C>): String {
        val sb = StringBuilder("[")
        if (result.runs.isNotEmpty()) {
            sb.append('\n')
            for ((i, run) in result.runs.withIndex()) {
                sb.append("  ").append(renderRunJson(run))
                if (i != result.runs.size - 1) sb.append(",")
                sb.append('\n')
            }
        }
        sb.append("]")
        return sb.toString()
    }

    private fun <C> renderRunJson(run: SweepRun<C>): String {
        val r = run.result.global
        val sb = StringBuilder("{")
        sb.append("\n    \"label\": ").append(ReportSerializer.jsonString(run.label)).append(",")
        sb.append("\n    \"config\": ").append(ReportSerializer.jsonString(run.config.toString())).append(",")
        sb.append("\n    \"totalPnL\": ").append(ReportSerializer.jsonBigDecimal(r.totalPnL)).append(",")
        sb.append("\n    \"sharpeRatio\": ").append(ReportSerializer.jsonNullableBigDecimal(r.sharpeRatio)).append(",")
        sb.append("\n    \"maxDrawdown\": ").append(ReportSerializer.jsonBigDecimal(r.maxDrawdown)).append(",")
        sb.append("\n    \"winRate\": ").append(ReportSerializer.jsonBigDecimal(r.winRate)).append(",")
        sb.append("\n    \"tradeCount\": ").append(r.tradeCount).append(",")
        sb.append("\n    \"profitFactor\": ").append(ReportSerializer.jsonNullableBigDecimal(r.profitFactor)).append(",")
        sb.append("\n    \"calmarRatio\": ").append(ReportSerializer.jsonNullableBigDecimal(r.calmarRatio))
        sb.append("\n  }")
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*SweepReportWriterTest*'`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/report/SweepReportWriter.kt \
        src/test/kotlin/com/qkt/backtest/report/SweepReportWriterTest.kt
git commit -m "feat(backtest): add SweepReportWriter with summary csv and json plus per-run dirs"
```

---

### Task 8: Full build + ktlint pass

**Files:** none changed (or pure ktlint reformat).

- [ ] **Step 1: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

If ktlint fails: run `./gradlew ktlintFormat`, inspect changes, then re-run build.

- [ ] **Step 2: Run precheck**

Run: `bash scripts/precheck.sh`
Expected: all green (build, test, clean tree, no unlinked TODO, no AI references, no emojis).

- [ ] **Step 3: Read commit log**

Run: `git log --oneline main..HEAD`
Expected: each subject ≤70 chars, conventional types, no emoji, no AI footer.

- [ ] **Step 4: If ktlint reformatted any files, commit**

```bash
git status -s
git add -u src/main/kotlin/com/qkt/backtest/sweep/ src/main/kotlin/com/qkt/backtest/report/ src/test/kotlin/com/qkt/backtest/sweep/ src/test/kotlin/com/qkt/backtest/report/
git commit -m "style: ktlint format for new sweep sources"
```

If `git status` is clean, skip.

---

### Task 9: Write Phase 10b changelog

**Files:**
- Create: `docs/phases/phase-10b-parameter-sweep.md`

- [ ] **Step 1: Create the changelog**

```markdown
# Phase 10b — Parameter Sweep

## Summary

Phase 10b adds the iteration loop on top of Phase 10. After this phase, a quant hands the engine a list of strategy configurations, kicks off a sweep, and gets back a ranked result — with a writer that produces a summary CSV plus the full per-run reports from Phase 10. Sequential by default for determinism; opt-in fixed-pool parallelism for callers who want speed on multi-core hardware.

## What's new

- `com.qkt.backtest.sweep` package — `BacktestSweep<C>`, `SweepRun<C>`, `SweepResult<C>`.
- `BacktestSweep<C>(configs, backtestFactory, parallelism = 1)` — runs N configs through the existing `Backtest` engine.
- `SweepResult.byLabel(label)` — lookup by label.
- `SweepResult.rankedBy { it.global.sharpeRatio ?: BigDecimal.ZERO }` — sort descending by an arbitrary metric extractor.
- `BacktestSweep` validation — non-empty configs, unique non-blank labels, parallelism ≥ 1.
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
        parallelism = 4,  // fixed thread pool, min(4, configs.size)
    )
```

### Writing reports to disk

```kotlin
import com.qkt.backtest.report.SweepReportWriter
import java.nio.file.Files
import java.nio.file.Paths

val dir = Paths.get("./reports/sweep-2026-05-07")
Files.createDirectories(dir)
SweepReportWriter(dir).write(result)
// Files: sweep_summary.csv, sweep_summary.json, runs/<label>/...
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
    val sweep = BacktestSweep(
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
- **Pool sanity timeout = 60s.** After `executor.shutdown()`, we wait at most 1 minute for tasks to finish. Beyond that, threads are abandoned to JVM cleanup. Acceptable for deliberate sweep invocations.
- **No progress callbacks, cancellation tokens, or async APIs.** `sweep.run()` is blocking. `Thread.interrupt()` is the only escape hatch.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase10b-design.md`
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase10b.md`
- Merge commit: filled in at merge time.
```

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-10b-parameter-sweep.md
git commit -m "docs: phase 10b changelog"
```

---

### Task 10: Final verification + finishing the branch

**Files:** none changed.

- [ ] **Step 1: Run full build one more time**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run precheck**

Run: `bash scripts/precheck.sh`
Expected: all green.

- [ ] **Step 3: Read commit log**

Run: `git log --oneline main..HEAD`
Expected: ~9-10 commits, each with a clean conventional-commit subject. No body, no AI footer, no emoji.

- [ ] **Step 4: Hand off to finishing-a-development-branch**

Announce: "I'm using the finishing-a-development-branch skill to complete this work."

Invoke: `superpowers:finishing-a-development-branch`. Standard 4 options (merge locally, PR, keep, discard). qkt convention is `git merge --no-ff` with `merge: phase 10b parameter sweep` on the merge commit.

After merge, fill in the merge commit SHA in `docs/phases/phase-10b-parameter-sweep.md` (References section) — that requires a follow-up commit on `main` with `docs: link phase 10b changelog to merge commit`.

---

## Self-Review Notes

Spec coverage check:

| Spec section | Plan task |
|---|---|
| §2 Goals — `SweepRun` | Task 1 |
| §2 Goals — `SweepResult` + `rankedBy` | Task 2 |
| §2 Goals — validation | Task 3 |
| §2 Goals — sequential | Task 4 |
| §2 Goals — parallel + fail-fast | Tasks 5, 6 |
| §2 Goals — `SweepReportWriter` | Task 7 |
| §6 BacktestSweep API | Tasks 3-5 |
| §7 SweepReportWriter format | Task 7 |
| §9 Testing | Tasks 2-7 |
| §11 Success criteria | Task 9 (changelog) |

Type-consistency check:
- `BacktestSweep<C>` parameter `backtestFactory: (label: String, config: C) -> Backtest` consistent across all tasks.
- `SweepResult.runs: List<SweepRun<C>>` — same shape in tests and implementations.
- `SweepReportWriter.write<C>(result: SweepResult<C>)` — uses generic correctly.
- Writer reuses `BacktestReportWriter` (Phase 10) and `ReportSerializer` (Phase 10) — both already exist on `main`.

No placeholders. Every step has runnable code or shell commands.
