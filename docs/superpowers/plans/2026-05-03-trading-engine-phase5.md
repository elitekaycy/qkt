# Trading Engine Phase 5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stateful, incremental indicator catalog (8 indicators) and a `Rule` framework with infix operators that strategies can compose to drive signal emission. Ship a sample `EmaCrossoverStrategy` that demonstrates indicator + rule composition end-to-end.

**Architecture:** New `com.qkt.indicators` package with `IndicatorOutput` (read SPI) + `Indicator<TIn> : IndicatorOutput` (write SPI), mirroring the `PositionProvider` / `PositionTracker` read/write split from Phase 3. Eight concrete indicators live in `com.qkt.indicators.catalog`. A sealed `Rule` class with infix operators (`gt`, `lt`, `eq`, `and`, `or`, `!`) reads `IndicatorOutput.value()` and returns `Boolean`; rules return `false` when any underlying indicator is not ready. Strategies own and update their indicators directly — no central registry. The sample strategy lives in `com.qkt.strategy` alongside `EveryNthTickBuyStrategy`.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase5-design.md`](../specs/2026-05-03-trading-engine-phase5-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase5-indicators`. Phase 4 is on `main` with 116 tests; Phase 5 spec is committed.

---

## Task ordering

```
1.  Indicator SPI (Indicator.kt: IndicatorOutput + Indicator<TIn>)              [foundation, no test]
2.  Rule + RuleTest                                                             [TDD, 9 tests]
3.  SMA + SMATest                                                               [TDD, 4 tests]
4.  EMA + EMATest                                                               [TDD, 4 tests]
5.  WMA + WMATest                                                               [TDD, 3 tests]
6.  RSI + RSITest                                                               [TDD, 4 tests]
7.  ATR + ATRTest                                                               [TDD, 3 tests]
8.  BollingerBands + BollingerBandsTest                                         [TDD, 4 tests]
9.  VWAP + VWAPTest                                                             [TDD, 4 tests]
10. MACD + MACDTest                                                             [TDD, 3 tests, depends on EMA]
11. EmaCrossoverStrategy + EmaCrossoverStrategyTest                             [TDD, 3 tests, depends on EMA]
12. Final verification                                                          [no new files]
```

12 tasks. Cumulative test counts after each:

| After task | Tests | Cumulative |
|---|---|---|
| 1  | 0  | 116 |
| 2  | +9 | 125 |
| 3  | +4 | 129 |
| 4  | +4 | 133 |
| 5  | +3 | 136 |
| 6  | +4 | 140 |
| 7  | +3 | 143 |
| 8  | +4 | 147 |
| 9  | +4 | 151 |
| 10 | +3 | 154 |
| 11 | +3 | 157 |
| 12 | 0  | 157 |

Final: 157 tests.

---

## Task 1: Indicator SPI

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/Indicator.kt`

This task ships the `IndicatorOutput` (read) and `Indicator<TIn>` (write) interfaces. No test in this task — the SPI is exercised by every downstream task.

- [ ] **Step 1: Create `Indicator.kt`**

`src/main/kotlin/com/qkt/indicators/Indicator.kt`:

```kotlin
package com.qkt.indicators

import java.math.BigDecimal

interface IndicatorOutput {
    fun value(): BigDecimal?

    val isReady: Boolean

    val warmupBars: Int
}

interface Indicator<TIn> : IndicatorOutput {
    fun update(input: TIn)
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full check (no behavior change to existing tests)**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 116 tests pass. ktlint clean.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/Indicator.kt
git commit -m "feat(indicators): add Indicator and IndicatorOutput SPI"
```

---

## Task 2: Rule + RuleTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/RuleTest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/Rule.kt`

The `Rule` framework is testable in isolation via anonymous `IndicatorOutput` stubs. No real indicator needed.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/RuleTest.kt`:

```kotlin
package com.qkt.indicators

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleTest {
    private fun stub(
        value: BigDecimal?,
        ready: Boolean = value != null,
    ) = object : IndicatorOutput {
            override fun value(): BigDecimal? = value
            override val isReady: Boolean = ready
            override val warmupBars: Int = 0
        }

    @Test
    fun `gt is true when left greater than right`() {
        val rule = stub(Money.of("10")) gt stub(Money.of("5"))
        assertThat(rule.evaluate()).isTrue()
    }

    @Test
    fun `gt is false when left less or equal to right`() {
        assertThat((stub(Money.of("5")) gt stub(Money.of("10"))).evaluate()).isFalse()
        assertThat((stub(Money.of("5")) gt stub(Money.of("5"))).evaluate()).isFalse()
    }

    @Test
    fun `lt is true when left less than right`() {
        val rule = stub(Money.of("3")) lt stub(Money.of("4"))
        assertThat(rule.evaluate()).isTrue()
    }

    @Test
    fun `eq is true when values compare equal regardless of scale`() {
        val rule = stub(BigDecimal("5.00")) eq stub(BigDecimal("5"))
        assertThat(rule.evaluate()).isTrue()
    }

    @Test
    fun `threshold gt overload compares against BigDecimal`() {
        assertThat((stub(Money.of("10")) gt Money.of("5")).evaluate()).isTrue()
        assertThat((stub(Money.of("3")) gt Money.of("5")).evaluate()).isFalse()
    }

    @Test
    fun `and combines rules`() {
        val tt = stub(Money.of("10")) gt stub(Money.of("5"))
        val ff = stub(Money.of("1")) gt stub(Money.of("5"))
        assertThat((tt and tt).evaluate()).isTrue()
        assertThat((tt and ff).evaluate()).isFalse()
    }

    @Test
    fun `or combines rules`() {
        val tt = stub(Money.of("10")) gt stub(Money.of("5"))
        val ff = stub(Money.of("1")) gt stub(Money.of("5"))
        assertThat((tt or ff).evaluate()).isTrue()
        assertThat((ff or ff).evaluate()).isFalse()
    }

    @Test
    fun `not inverts a rule`() {
        val tt = stub(Money.of("10")) gt stub(Money.of("5"))
        assertThat((!tt).evaluate()).isFalse()
    }

    @Test
    fun `evaluates false when any underlying indicator is not ready`() {
        val notReady = stub(value = null, ready = false)
        val ready = stub(Money.of("5"))
        assertThat((notReady gt ready).evaluate()).isFalse()
        assertThat((ready gt notReady).evaluate()).isFalse()
        assertThat((notReady gt Money.of("5")).evaluate()).isFalse()
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.RuleTest"`
Expected: compile failure — `Unresolved reference 'gt' / 'lt' / 'eq' / 'and' / 'or' / Rule`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement `Rule.kt`**

`src/main/kotlin/com/qkt/indicators/Rule.kt`:

```kotlin
package com.qkt.indicators

import java.math.BigDecimal

sealed class Rule {
    abstract fun evaluate(): Boolean

    infix fun and(other: Rule): Rule = And(this, other)

    infix fun or(other: Rule): Rule = Or(this, other)

    operator fun not(): Rule = Not(this)

    data class Over(
        val left: IndicatorOutput,
        val right: IndicatorOutput,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l > r
        }
    }

    data class Under(
        val left: IndicatorOutput,
        val right: IndicatorOutput,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l < r
        }
    }

    data class Eq(
        val left: IndicatorOutput,
        val right: IndicatorOutput,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            val r = right.value() ?: return false
            return l.compareTo(r) == 0
        }
    }

    data class OverThreshold(
        val left: IndicatorOutput,
        val threshold: BigDecimal,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            return l > threshold
        }
    }

    data class UnderThreshold(
        val left: IndicatorOutput,
        val threshold: BigDecimal,
    ) : Rule() {
        override fun evaluate(): Boolean {
            val l = left.value() ?: return false
            return l < threshold
        }
    }

    data class And(
        val a: Rule,
        val b: Rule,
    ) : Rule() {
        override fun evaluate(): Boolean = a.evaluate() && b.evaluate()
    }

    data class Or(
        val a: Rule,
        val b: Rule,
    ) : Rule() {
        override fun evaluate(): Boolean = a.evaluate() || b.evaluate()
    }

    data class Not(
        val r: Rule,
    ) : Rule() {
        override fun evaluate(): Boolean = !r.evaluate()
    }
}

infix fun IndicatorOutput.gt(other: IndicatorOutput): Rule = Rule.Over(this, other)

infix fun IndicatorOutput.lt(other: IndicatorOutput): Rule = Rule.Under(this, other)

infix fun IndicatorOutput.eq(other: IndicatorOutput): Rule = Rule.Eq(this, other)

infix fun IndicatorOutput.gt(threshold: BigDecimal): Rule = Rule.OverThreshold(this, threshold)

infix fun IndicatorOutput.lt(threshold: BigDecimal): Rule = Rule.UnderThreshold(this, threshold)
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.RuleTest"`
Expected: 9 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 125 tests total. ktlint clean.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/Rule.kt src/test/kotlin/com/qkt/indicators/RuleTest.kt
git commit -m "feat(indicators): add Rule sealed class with infix operators"
```

---

## Task 3: SMA + SMATest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/SMATest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/SMA.kt`

Hand-computed values:
- `SMA(3)` on `[10, 20, 30]` → 20
- `SMA(3)` on `[10, 20, 30, 40]` → 30 (window roll: last 3 are 20, 30, 40)

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/SMATest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SMATest {
    @Test
    fun `not ready before warmup`() {
        val sma = SMA(3)
        sma.update(Money.of("10"))
        sma.update(Money.of("20"))
        assertThat(sma.isReady).isFalse()
        assertThat(sma.value()).isNull()
        assertThat(sma.warmupBars).isEqualTo(3)
    }

    @Test
    fun `mean of last N values`() {
        val sma = SMA(3)
        listOf("10", "20", "30").forEach { sma.update(Money.of(it)) }
        assertThat(sma.isReady).isTrue()
        assertThat(sma.value()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `rolls window after N values`() {
        val sma = SMA(3)
        listOf("10", "20", "30", "40").forEach { sma.update(Money.of(it)) }
        assertThat(sma.value()).isEqualByComparingTo(Money.of("30"))
    }

    @Test
    fun `equals constant on a constant series`() {
        val sma = SMA(5)
        repeat(5) { sma.update(Money.of("7")) }
        assertThat(sma.value()).isEqualByComparingTo(Money.of("7"))
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.SMATest"`
Expected: compile failure — `Unresolved reference 'SMA'`.

- [ ] **Step 3: Implement `SMA.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/SMA.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

class SMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private var sum: BigDecimal = BigDecimal.ZERO

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        sum = sum.add(input, Money.CONTEXT)
        if (window.size > period) {
            sum = sum.subtract(window.removeFirst(), Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        return sum.divide(BigDecimal(period), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.SMATest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 129 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/SMA.kt src/test/kotlin/com/qkt/indicators/catalog/SMATest.kt
git commit -m "feat(indicators): add SMA indicator"
```

---

## Task 4: EMA + EMATest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/EMATest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/EMA.kt`

EMA algorithm:
- α = 2 / (period + 1)
- Seed at bar `period`: EMA = SMA of first `period` closes.
- Bar `period + k` (k ≥ 1): EMA = α · close + (1 − α) · prev_EMA.

Hand-computed: `EMA(3)` on `[10, 20, 30, 40, 50]` with α = 0.5:
- Bar 3: EMA = (10+20+30)/3 = 20.
- Bar 4: EMA = 0.5·40 + 0.5·20 = 30.
- Bar 5: EMA = 0.5·50 + 0.5·30 = 40.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/EMATest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EMATest {
    @Test
    fun `not ready before bar N`() {
        val ema = EMA(3)
        ema.update(Money.of("10"))
        ema.update(Money.of("20"))
        assertThat(ema.isReady).isFalse()
        assertThat(ema.value()).isNull()
        assertThat(ema.warmupBars).isEqualTo(3)
    }

    @Test
    fun `seeds with SMA of first N closes`() {
        val ema = EMA(3)
        listOf("10", "20", "30").forEach { ema.update(Money.of(it)) }
        assertThat(ema.isReady).isTrue()
        assertThat(ema.value()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `applies alpha smoothing after seed`() {
        val ema = EMA(3)
        listOf("10", "20", "30", "40", "50").forEach { ema.update(Money.of(it)) }
        // bar 3 EMA = 20, bar 4 = 0.5*40 + 0.5*20 = 30, bar 5 = 0.5*50 + 0.5*30 = 40
        assertThat(ema.value()).isEqualByComparingTo(Money.of("40"))
    }

    @Test
    fun `converges to constant on a constant series`() {
        val ema = EMA(5)
        repeat(20) { ema.update(Money.of("7")) }
        assertThat(ema.value()).isEqualByComparingTo(Money.of("7"))
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.EMATest"`
Expected: compile failure — `Unresolved reference 'EMA'`.

- [ ] **Step 3: Implement `EMA.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/EMA.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

class EMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val alpha: BigDecimal =
        BigDecimal(2).divide(BigDecimal(period + 1), Money.CONTEXT)
    private val oneMinusAlpha: BigDecimal =
        BigDecimal.ONE.subtract(alpha, Money.CONTEXT)

    private val seedBuffer: MutableList<BigDecimal> = ArrayList(period)
    private var ema: BigDecimal? = null

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = ema != null

    override fun update(input: BigDecimal) {
        val prev = ema
        if (prev == null) {
            seedBuffer.add(input)
            if (seedBuffer.size == period) {
                var sum = BigDecimal.ZERO
                for (v in seedBuffer) sum = sum.add(v, Money.CONTEXT)
                ema = sum.divide(BigDecimal(period), Money.CONTEXT)
                seedBuffer.clear()
            }
        } else {
            ema = alpha.multiply(input, Money.CONTEXT)
                .add(oneMinusAlpha.multiply(prev, Money.CONTEXT), Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? = ema?.setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.EMATest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 133 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/EMA.kt src/test/kotlin/com/qkt/indicators/catalog/EMATest.kt
git commit -m "feat(indicators): add EMA indicator with SMA-of-first-N seed"
```

---

## Task 5: WMA + WMATest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/WMATest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/WMA.kt`

WMA(N): weights 1, 2, …, N (most recent gets N). Denominator = N(N+1)/2. Hand-computed: `WMA(3)` on `[3, 6, 9]` → (1·3 + 2·6 + 3·9) / 6 = 42 / 6 = 7.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/WMATest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WMATest {
    @Test
    fun `not ready before warmup`() {
        val wma = WMA(3)
        wma.update(Money.of("3"))
        wma.update(Money.of("6"))
        assertThat(wma.isReady).isFalse()
        assertThat(wma.value()).isNull()
        assertThat(wma.warmupBars).isEqualTo(3)
    }

    @Test
    fun `linearly weighted mean`() {
        val wma = WMA(3)
        listOf("3", "6", "9").forEach { wma.update(Money.of(it)) }
        // (1*3 + 2*6 + 3*9) / 6 = 42/6 = 7
        assertThat(wma.value()).isEqualByComparingTo(Money.of("7"))
    }

    @Test
    fun `equals constant on a constant series`() {
        val wma = WMA(4)
        repeat(4) { wma.update(Money.of("11")) }
        assertThat(wma.value()).isEqualByComparingTo(Money.of("11"))
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.WMATest"`
Expected: compile failure — `Unresolved reference 'WMA'`.

- [ ] **Step 3: Implement `WMA.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/WMA.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

class WMA(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private val denominator: BigDecimal =
        BigDecimal(period.toLong() * (period + 1) / 2)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        var weighted = BigDecimal.ZERO
        var weight = 1
        for (v in window) {
            weighted = weighted.add(BigDecimal(weight).multiply(v, Money.CONTEXT), Money.CONTEXT)
            weight++
        }
        return weighted.divide(denominator, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.WMATest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 136 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/WMA.kt src/test/kotlin/com/qkt/indicators/catalog/WMATest.kt
git commit -m "feat(indicators): add WMA indicator"
```

---

## Task 6: RSI + RSITest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/RSITest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/RSI.kt`

RSI(N) — Wilder smoothing:
- For each pair of consecutive closes, compute gain (positive diff) and loss (positive of negative diff).
- Need `N + 1` closes (to produce `N` differences) for warmup.
- Seed `avgGain` and `avgLoss` as simple averages of the first N gains/losses.
- Thereafter (Wilder): `avgGain = (avgGain·(N−1) + gain) / N`, same for `avgLoss`.
- `RS = avgGain / avgLoss`, `RSI = 100 − 100 / (1 + RS)`.
- Edge: `avgLoss = 0` → `RSI = 100`. `avgGain = 0` and `avgLoss > 0` → `RSI = 0`.

Hand-computed: `RSI(2)` on `[10, 12, 11]`:
- diff 1: +2 (gain 2, loss 0). diff 2: −1 (gain 0, loss 1).
- avgGain = (2 + 0) / 2 = 1. avgLoss = (0 + 1) / 2 = 0.5.
- RS = 2. RSI = 100 − 100/3 = 66.66666667.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/RSITest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RSITest {
    @Test
    fun `not ready before N plus 1 closes`() {
        val rsi = RSI(2)
        rsi.update(Money.of("10"))
        rsi.update(Money.of("12"))
        assertThat(rsi.isReady).isFalse()
        assertThat(rsi.value()).isNull()
        assertThat(rsi.warmupBars).isEqualTo(3)
    }

    @Test
    fun `mixed gains and losses produce expected RSI`() {
        val rsi = RSI(2)
        listOf("10", "12", "11").forEach { rsi.update(Money.of(it)) }
        // avgGain=1, avgLoss=0.5, RS=2, RSI = 100 - 100/3 = 66.66666667
        assertThat(rsi.value()).isEqualByComparingTo(BigDecimal("66.66666667"))
    }

    @Test
    fun `monotonic up series yields RSI 100`() {
        val rsi = RSI(2)
        listOf("10", "11", "12").forEach { rsi.update(Money.of(it)) }
        assertThat(rsi.value()).isEqualByComparingTo(Money.of("100"))
    }

    @Test
    fun `monotonic down series yields RSI 0`() {
        val rsi = RSI(2)
        listOf("10", "9", "8").forEach { rsi.update(Money.of(it)) }
        assertThat(rsi.value()).isEqualByComparingTo(Money.of("0"))
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.RSITest"`
Expected: compile failure — `Unresolved reference 'RSI'`.

- [ ] **Step 3: Implement `RSI.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/RSI.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

class RSI(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private var prevClose: BigDecimal? = null
    private val seedGains: MutableList<BigDecimal> = ArrayList(period)
    private val seedLosses: MutableList<BigDecimal> = ArrayList(period)
    private var avgGain: BigDecimal? = null
    private var avgLoss: BigDecimal? = null

    private val periodBd: BigDecimal = BigDecimal(period)
    private val periodMinusOne: BigDecimal = BigDecimal(period - 1)
    private val hundred: BigDecimal = BigDecimal(100)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = avgGain != null && avgLoss != null

    override fun update(input: BigDecimal) {
        val prev = prevClose
        prevClose = input
        if (prev == null) return

        val diff = input.subtract(prev, Money.CONTEXT)
        val gain = if (diff.signum() > 0) diff else BigDecimal.ZERO
        val loss = if (diff.signum() < 0) diff.negate() else BigDecimal.ZERO

        val seededGain = avgGain
        val seededLoss = avgLoss
        if (seededGain == null || seededLoss == null) {
            seedGains.add(gain)
            seedLosses.add(loss)
            if (seedGains.size == period) {
                var gSum = BigDecimal.ZERO
                var lSum = BigDecimal.ZERO
                for (g in seedGains) gSum = gSum.add(g, Money.CONTEXT)
                for (l in seedLosses) lSum = lSum.add(l, Money.CONTEXT)
                avgGain = gSum.divide(periodBd, Money.CONTEXT)
                avgLoss = lSum.divide(periodBd, Money.CONTEXT)
                seedGains.clear()
                seedLosses.clear()
            }
        } else {
            avgGain = seededGain.multiply(periodMinusOne, Money.CONTEXT)
                .add(gain, Money.CONTEXT)
                .divide(periodBd, Money.CONTEXT)
            avgLoss = seededLoss.multiply(periodMinusOne, Money.CONTEXT)
                .add(loss, Money.CONTEXT)
                .divide(periodBd, Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? {
        val g = avgGain ?: return null
        val l = avgLoss ?: return null
        if (l.signum() == 0) {
            return if (g.signum() == 0) Money.of("50") else Money.of("100")
        }
        val rs = g.divide(l, Money.CONTEXT)
        val rsi = hundred.subtract(
            hundred.divide(BigDecimal.ONE.add(rs, Money.CONTEXT), Money.CONTEXT),
            Money.CONTEXT,
        )
        return rsi.setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.RSITest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 140 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/RSI.kt src/test/kotlin/com/qkt/indicators/catalog/RSITest.kt
git commit -m "feat(indicators): add RSI indicator with Wilder smoothing"
```

---

## Task 7: ATR + ATRTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/ATRTest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/ATR.kt`

ATR(N) — Wilder smoothing on True Range:
- TR for the first candle has no previous close — TR[1] is undefined; we skip it.
- TR[t > 1] = max(high − low, |high − prevClose|, |low − prevClose|).
- Need `N + 1` candles (to produce `N` true ranges) for warmup.
- Seed: ATR = simple mean of the first N TRs.
- Thereafter (Wilder): ATR = (ATR_prev · (N−1) + TR) / N.

Hand-computed: `ATR(2)` on candles
- C1: H=12, L=10, C=11
- C2: H=15, L=11, C=14 → TR = max(15−11, |15−11|, |11−11|) = 4
- C3: H=14, L=10, C=12 → TR = max(14−10, |14−14|, |10−14|) = 4
- ATR(2) seed = (4 + 4) / 2 = 4 (ready after C3).
- C4: H=18, L=13, C=17 → TR = max(18−13, |18−12|, |13−12|) = 6
- ATR(2) = (4·(2−1) + 6) / 2 = 5.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/ATRTest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ATRTest {
    private fun candle(
        h: String,
        l: String,
        c: String,
        ts: Long = 0L,
    ) = Candle(
            symbol = "X",
            open = Money.of(c),
            high = Money.of(h),
            low = Money.of(l),
            close = Money.of(c),
            volume = Money.of("1"),
            startTime = ts,
            endTime = ts + 1,
        )

    @Test
    fun `not ready before N plus 1 candles`() {
        val atr = ATR(2)
        atr.update(candle("12", "10", "11"))
        atr.update(candle("15", "11", "14"))
        assertThat(atr.isReady).isFalse()
        assertThat(atr.value()).isNull()
        assertThat(atr.warmupBars).isEqualTo(3)
    }

    @Test
    fun `Wilder smoothing on a known sequence`() {
        val atr = ATR(2)
        atr.update(candle("12", "10", "11"))
        atr.update(candle("15", "11", "14")) // TR = 4
        atr.update(candle("14", "10", "12")) // TR = 4 -> seed ATR = 4
        assertThat(atr.value()).isEqualByComparingTo(Money.of("4"))
        atr.update(candle("18", "13", "17")) // TR = 6 -> ATR = (4*1 + 6)/2 = 5
        assertThat(atr.value()).isEqualByComparingTo(Money.of("5"))
    }

    @Test
    fun `flat candles yield ATR zero`() {
        val atr = ATR(2)
        repeat(5) { atr.update(candle("10", "10", "10", ts = it.toLong())) }
        assertThat(atr.value()).isEqualByComparingTo(Money.ZERO)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.ATRTest"`
Expected: compile failure — `Unresolved reference 'ATR'`.

- [ ] **Step 3: Implement `ATR.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/ATR.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import java.math.BigDecimal

class ATR(
    private val period: Int,
) : Indicator<Candle> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private var prevClose: BigDecimal? = null
    private val seedTrs: MutableList<BigDecimal> = ArrayList(period)
    private var atr: BigDecimal? = null

    private val periodBd: BigDecimal = BigDecimal(period)
    private val periodMinusOne: BigDecimal = BigDecimal(period - 1)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = atr != null

    override fun update(input: Candle) {
        val prev = prevClose
        prevClose = input.close
        if (prev == null) return

        val hl = input.high.subtract(input.low, Money.CONTEXT)
        val hc = input.high.subtract(prev, Money.CONTEXT).abs()
        val lc = input.low.subtract(prev, Money.CONTEXT).abs()
        val tr = hl.max(hc).max(lc)

        val seeded = atr
        if (seeded == null) {
            seedTrs.add(tr)
            if (seedTrs.size == period) {
                var sum = BigDecimal.ZERO
                for (v in seedTrs) sum = sum.add(v, Money.CONTEXT)
                atr = sum.divide(periodBd, Money.CONTEXT)
                seedTrs.clear()
            }
        } else {
            atr = seeded.multiply(periodMinusOne, Money.CONTEXT)
                .add(tr, Money.CONTEXT)
                .divide(periodBd, Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? = atr?.setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.ATRTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 143 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/ATR.kt src/test/kotlin/com/qkt/indicators/catalog/ATRTest.kt
git commit -m "feat(indicators): add ATR indicator with Wilder smoothing"
```

---

## Task 8: BollingerBands + BollingerBandsTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/BollingerBandsTest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/BollingerBands.kt`

BollingerBands(N, k):
- middle = SMA(N).
- variance = Σ(x − middle)² / N (population variance, divisor N — *not* N − 1).
- stddev = sqrt(variance).
- upper = middle + k · stddev. lower = middle − k · stddev.
- Internal accumulators use `MathContext.DECIMAL128`. Output truncates to scale = 8.
- `value()` returns middle. `bands()` returns `(upper, middle, lower)`.

Hand-computed: `BollingerBands(2, 1.0)` on `[10, 20]`:
- middle = 15.
- variance = ((10 − 15)² + (20 − 15)²) / 2 = (25 + 25) / 2 = 25.
- stddev = 5. upper = 20. lower = 10.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/BollingerBandsTest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BollingerBandsTest {
    @Test
    fun `not ready before warmup`() {
        val bb = BollingerBands(period = 2, stddevK = 1.0)
        bb.update(Money.of("10"))
        assertThat(bb.isReady).isFalse()
        assertThat(bb.value()).isNull()
        assertThat(bb.bands()).isNull()
        assertThat(bb.warmupBars).isEqualTo(2)
    }

    @Test
    fun `produces middle upper lower for known series`() {
        val bb = BollingerBands(period = 2, stddevK = 1.0)
        bb.update(Money.of("10"))
        bb.update(Money.of("20"))
        assertThat(bb.isReady).isTrue()
        assertThat(bb.value()).isEqualByComparingTo(Money.of("15"))
        val bands = bb.bands()!!
        assertThat(bands.middle).isEqualByComparingTo(Money.of("15"))
        assertThat(bands.upper).isEqualByComparingTo(Money.of("20"))
        assertThat(bands.lower).isEqualByComparingTo(Money.of("10"))
    }

    @Test
    fun `width is zero on a constant series`() {
        val bb = BollingerBands(period = 3, stddevK = 2.0)
        repeat(3) { bb.update(Money.of("7")) }
        val bands = bb.bands()!!
        assertThat(bands.upper).isEqualByComparingTo(Money.of("7"))
        assertThat(bands.middle).isEqualByComparingTo(Money.of("7"))
        assertThat(bands.lower).isEqualByComparingTo(Money.of("7"))
    }

    @Test
    fun `upper is at least middle is at least lower`() {
        val bb = BollingerBands(period = 4, stddevK = 2.0)
        listOf("1", "5", "2", "8").forEach { bb.update(Money.of(it)) }
        val bands = bb.bands()!!
        assertThat(bands.upper).isGreaterThanOrEqualTo(bands.middle)
        assertThat(bands.middle).isGreaterThanOrEqualTo(bands.lower)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.BollingerBandsTest"`
Expected: compile failure — `Unresolved reference 'BollingerBands'`.

- [ ] **Step 3: Implement `BollingerBands.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/BollingerBands.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal
import java.math.MathContext

data class BollingerBandValues(
    val upper: BigDecimal,
    val middle: BigDecimal,
    val lower: BigDecimal,
)

class BollingerBands(
    private val period: Int,
    private val stddevK: Double = 2.0,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
        require(stddevK >= 0.0) { "stddevK must be >= 0: $stddevK" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)
    private val k: BigDecimal = BigDecimal(stddevK.toString())
    private val periodBd: BigDecimal = BigDecimal(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: BigDecimal) {
        window.addLast(input)
        if (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? = bands()?.middle

    fun bands(): BollingerBandValues? {
        if (!isReady) return null
        var sum = BigDecimal.ZERO
        for (v in window) sum = sum.add(v, MathContext.DECIMAL128)
        val mean = sum.divide(periodBd, MathContext.DECIMAL128)
        var variance = BigDecimal.ZERO
        for (v in window) {
            val d = v.subtract(mean, MathContext.DECIMAL128)
            variance = variance.add(d.multiply(d, MathContext.DECIMAL128), MathContext.DECIMAL128)
        }
        variance = variance.divide(periodBd, MathContext.DECIMAL128)
        val stddev = variance.sqrt(Money.CONTEXT)
        val offset = k.multiply(stddev, Money.CONTEXT)
        return BollingerBandValues(
            upper = mean.add(offset, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
            middle = mean.setScale(Money.SCALE, Money.ROUNDING),
            lower = mean.subtract(offset, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING),
        )
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.BollingerBandsTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 147 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/BollingerBands.kt src/test/kotlin/com/qkt/indicators/catalog/BollingerBandsTest.kt
git commit -m "feat(indicators): add BollingerBands indicator"
```

---

## Task 9: VWAP + VWAPTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/VWAPTest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/VWAP.kt`

VWAP(N) — rolling N-tick window:
- numerator = Σ(price · volume) over the last N ticks.
- denominator = Σ volume over the last N ticks.
- value = numerator / denominator.
- Internal accumulators use `MathContext.DECIMAL128`.
- `Tick.volume` is nullable. VWAP requires non-null volume — throws `IllegalStateException` on null. The strategy is responsible for filtering.
- Edge: if denominator is zero (all volumes zero), `value()` returns null.

Hand-computed: `VWAP(2)` on `(price=10, vol=2), (price=20, vol=3)`:
- numerator = 10·2 + 20·3 = 80. denominator = 5. VWAP = 16.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/VWAPTest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VWAPTest {
    private fun tick(
        price: String,
        volume: String?,
        ts: Long = 0L,
    ) = Tick(
            symbol = "X",
            price = Money.of(price),
            timestamp = ts,
            volume = volume?.let { Money.of(it) },
        )

    @Test
    fun `not ready before warmup`() {
        val vwap = VWAP(2)
        vwap.update(tick("10", "1"))
        assertThat(vwap.isReady).isFalse()
        assertThat(vwap.value()).isNull()
        assertThat(vwap.warmupBars).isEqualTo(2)
    }

    @Test
    fun `volume weighted mean over the rolling window`() {
        val vwap = VWAP(2)
        vwap.update(tick("10", "2"))
        vwap.update(tick("20", "3"))
        assertThat(vwap.isReady).isTrue()
        assertThat(vwap.value()).isEqualByComparingTo(Money.of("16"))
    }

    @Test
    fun `equals arithmetic mean when all volumes equal`() {
        val vwap = VWAP(2)
        vwap.update(tick("10", "1"))
        vwap.update(tick("20", "1"))
        assertThat(vwap.value()).isEqualByComparingTo(Money.of("15"))
    }

    @Test
    fun `throws on null volume`() {
        val vwap = VWAP(2)
        assertThatThrownBy { vwap.update(tick("10", volume = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("volume")
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.VWAPTest"`
Expected: compile failure — `Unresolved reference 'VWAP'`.

- [ ] **Step 3: Implement `VWAP.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/VWAP.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.MathContext

class VWAP(
    private val period: Int,
) : Indicator<Tick> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private data class Sample(
        val priceVol: BigDecimal,
        val volume: BigDecimal,
    )

    private val window: ArrayDeque<Sample> = ArrayDeque(period)
    private var numerator: BigDecimal = BigDecimal.ZERO
    private var denominator: BigDecimal = BigDecimal.ZERO

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: Tick) {
        val volume = input.volume
            ?: error("VWAP requires non-null Tick.volume; got null for ${input.symbol} @ ${input.timestamp}")
        val pv = input.price.multiply(volume, MathContext.DECIMAL128)
        window.addLast(Sample(pv, volume))
        numerator = numerator.add(pv, MathContext.DECIMAL128)
        denominator = denominator.add(volume, MathContext.DECIMAL128)
        if (window.size > period) {
            val out = window.removeFirst()
            numerator = numerator.subtract(out.priceVol, MathContext.DECIMAL128)
            denominator = denominator.subtract(out.volume, MathContext.DECIMAL128)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        if (denominator.signum() == 0) return null
        return numerator.divide(denominator, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.VWAPTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 151 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/VWAP.kt src/test/kotlin/com/qkt/indicators/catalog/VWAPTest.kt
git commit -m "feat(indicators): add VWAP indicator over rolling window"
```

---

## Task 10: MACD + MACDTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/indicators/catalog/MACDTest.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/MACD.kt`

MACD(fast, slow, signal):
- Internally hold `EMA(fast)`, `EMA(slow)`, and `EMA(signal)`.
- Each `update(close)` feeds the close to both fast and slow.
- Once both fast and slow are ready, compute `macd = fast.value() − slow.value()` and feed `macd` into the signal EMA.
- `value()` returns the macd line. `lines()` returns `(macd, signal, histogram)`.
- `histogram = macd − signalLine`.
- `warmupBars = slow + signal − 1` (slow EMA ready at bar `slow`; signal EMA needs `signal` macd values, available at bar `slow + signal − 1`).

Hand-computed: `MACD(fast=2, slow=3, signal=2)` on `[2, 4, 6, 8, 10]`:
- α_fast = 2/3, α_slow = 0.5, α_signal = 2/3.
- Bar 2: fast SMA seed = (2+4)/2 = 3. fast = 3.
- Bar 3: slow SMA seed = (2+4+6)/3 = 4. slow = 4. fast = (2/3)·6 + (1/3)·3 = 5. macd = 1.
- Bar 4: fast = (2/3)·8 + (1/3)·5 = 7. slow = 0.5·8 + 0.5·4 = 6. macd = 1. signal SMA seed = (1+1)/2 = 1. histogram = 0.
- Bar 5: fast = (2/3)·10 + (1/3)·7 = 9. slow = 0.5·10 + 0.5·6 = 8. macd = 1. signal = (2/3)·1 + (1/3)·1 = 1. histogram = 0.

So at bar 5: macd = 1, signal = 1, histogram = 0.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/indicators/catalog/MACDTest.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MACDTest {
    @Test
    fun `not ready until signal EMA seeds`() {
        val macd = MACD(fast = 2, slow = 3, signal = 2)
        listOf("2", "4", "6").forEach { macd.update(Money.of(it)) }
        // slow ready at bar 3 (macd line first computed) but signal needs 2 macd values
        assertThat(macd.isReady).isFalse()
        assertThat(macd.value()).isNull()
        assertThat(macd.warmupBars).isEqualTo(4) // slow + signal - 1 = 3 + 2 - 1
    }

    @Test
    fun `linear ramp produces stable macd line`() {
        val macd = MACD(fast = 2, slow = 3, signal = 2)
        listOf("2", "4", "6", "8", "10").forEach { macd.update(Money.of(it)) }
        assertThat(macd.isReady).isTrue()
        assertThat(macd.value()).isEqualByComparingTo(Money.of("1"))
        val lines = macd.lines()!!
        assertThat(lines.macd).isEqualByComparingTo(Money.of("1"))
        assertThat(lines.signal).isEqualByComparingTo(Money.of("1"))
        assertThat(lines.histogram).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `constant series yields zero macd and zero histogram`() {
        val macd = MACD(fast = 2, slow = 3, signal = 2)
        repeat(10) { macd.update(Money.of("7")) }
        val lines = macd.lines()!!
        assertThat(lines.macd).isEqualByComparingTo(Money.ZERO)
        assertThat(lines.signal).isEqualByComparingTo(Money.ZERO)
        assertThat(lines.histogram).isEqualByComparingTo(Money.ZERO)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.MACDTest"`
Expected: compile failure — `Unresolved reference 'MACD'`.

- [ ] **Step 3: Implement `MACD.kt`**

`src/main/kotlin/com/qkt/indicators/catalog/MACD.kt`:

```kotlin
package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

data class MACDLines(
    val macd: BigDecimal,
    val signal: BigDecimal,
    val histogram: BigDecimal,
)

class MACD(
    private val fast: Int = 12,
    private val slow: Int = 26,
    private val signal: Int = 9,
) : Indicator<BigDecimal> {
    init {
        require(fast > 0) { "fast must be > 0: $fast" }
        require(slow > fast) { "slow must be > fast: slow=$slow, fast=$fast" }
        require(signal > 0) { "signal must be > 0: $signal" }
    }

    private val fastEma = EMA(fast)
    private val slowEma = EMA(slow)
    private val signalEma = EMA(signal)

    override val warmupBars: Int = slow + signal - 1

    override val isReady: Boolean
        get() = signalEma.isReady

    override fun update(input: BigDecimal) {
        fastEma.update(input)
        slowEma.update(input)
        if (fastEma.isReady && slowEma.isReady) {
            val macdLine = fastEma.value()!!.subtract(slowEma.value()!!, Money.CONTEXT)
            signalEma.update(macdLine)
        }
    }

    override fun value(): BigDecimal? = lines()?.macd

    fun lines(): MACDLines? {
        if (!fastEma.isReady || !slowEma.isReady || !signalEma.isReady) return null
        val macdLine = fastEma.value()!!.subtract(slowEma.value()!!, Money.CONTEXT)
        val sig = signalEma.value()!!
        val hist = macdLine.subtract(sig, Money.CONTEXT)
        return MACDLines(
            macd = macdLine.setScale(Money.SCALE, Money.ROUNDING),
            signal = sig.setScale(Money.SCALE, Money.ROUNDING),
            histogram = hist.setScale(Money.SCALE, Money.ROUNDING),
        )
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.MACDTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 154 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/MACD.kt src/test/kotlin/com/qkt/indicators/catalog/MACDTest.kt
git commit -m "feat(indicators): add MACD indicator with signal line and histogram"
```

---

## Task 11: EmaCrossoverStrategy + EmaCrossoverStrategyTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/strategy/EmaCrossoverStrategyTest.kt`
- Create: `src/main/kotlin/com/qkt/strategy/EmaCrossoverStrategy.kt`

The sample strategy holds two `EMA` instances (fast, slow) and tracks `lastFastAboveSlow: Boolean?`. On each candle, after both EMAs are ready, compare; on transition false→true emit Buy; on transition true→false emit Sell.

Hand-computed crossover sequence with `EMA(2) / EMA(3)` on closes `[10, 10, 10, 10, 12, 14, 12, 10]`:
- α_fast = 2/3, α_slow = 0.5.
- Bar 2: fast seed = 10. slow not ready.
- Bar 3: slow seed = 10. fast = (2/3)·10 + (1/3)·10 = 10. fastAbove = (10 > 10) = false. prev = null → set prev = false; no signal.
- Bar 4: fast = 10, slow = 10. fastAbove = false. prev = false. No transition.
- Bar 5 (close = 12): fast = (2/3)·12 + (1/3)·10 = 11.333…; slow = 0.5·12 + 0.5·10 = 11. fastAbove = true. prev = false → Buy.
- Bar 6 (close = 14): both above. No transition.
- Bar 7 (close = 12): fast = (2/3)·12 + (1/3)·prev_fast(13.111) = 12.370…; slow = 0.5·12 + 0.5·12.5 = 12.25. fastAbove = true. No transition.
- Bar 8 (close = 10): fast = (2/3)·10 + (1/3)·12.370 = 10.790…; slow = 0.5·10 + 0.5·12.25 = 11.125. fastAbove = false. prev = true → Sell.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/strategy/EmaCrossoverStrategyTest.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmaCrossoverStrategyTest {
    private fun candle(
        close: String,
        ts: Long = 0L,
        symbol: String = "X",
    ) = Candle(
            symbol = symbol,
            open = Money.of(close),
            high = Money.of(close),
            low = Money.of(close),
            close = Money.of(close),
            volume = Money.of("1"),
            startTime = ts,
            endTime = ts + 1,
        )

    private fun feed(
        strategy: EmaCrossoverStrategy,
        closes: List<String>,
    ): List<Signal> {
        val signals = mutableListOf<Signal>()
        closes.forEachIndexed { i, c ->
            strategy.onCandle(candle(c, ts = i.toLong())) { signals.add(it) }
        }
        return signals
    }

    @Test
    fun `no signal during warmup`() {
        val strategy = EmaCrossoverStrategy("X", fastPeriod = 2, slowPeriod = 3)
        val signals = feed(strategy, listOf("10", "10")) // slow not ready
        assertThat(signals).isEmpty()
    }

    @Test
    fun `golden cross emits Buy`() {
        val strategy = EmaCrossoverStrategy(
            symbol = "X",
            fastPeriod = 2,
            slowPeriod = 3,
            size = Money.of("1"),
        )
        val signals = feed(strategy, listOf("10", "10", "10", "10", "12"))
        assertThat(signals).containsExactly(Signal.Buy("X", Money.of("1")))
    }

    @Test
    fun `death cross emits Sell after a prior golden cross`() {
        val strategy = EmaCrossoverStrategy(
            symbol = "X",
            fastPeriod = 2,
            slowPeriod = 3,
            size = Money.of("1"),
        )
        val signals = feed(strategy, listOf("10", "10", "10", "10", "12", "14", "12", "10"))
        assertThat(signals).containsExactly(
            Signal.Buy("X", Money.of("1")),
            Signal.Sell("X", Money.of("1")),
        )
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.strategy.EmaCrossoverStrategyTest"`
Expected: compile failure — `Unresolved reference 'EmaCrossoverStrategy'`.

- [ ] **Step 3: Implement `EmaCrossoverStrategy.kt`**

`src/main/kotlin/com/qkt/strategy/EmaCrossoverStrategy.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.common.Money
import com.qkt.indicators.catalog.EMA
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

class EmaCrossoverStrategy(
    private val symbol: String,
    private val fastPeriod: Int = 9,
    private val slowPeriod: Int = 21,
    private val size: BigDecimal = Money.of("1"),
) : Strategy {
    init {
        require(fastPeriod > 0) { "fastPeriod must be > 0: $fastPeriod" }
        require(slowPeriod > fastPeriod) {
            "slowPeriod must be > fastPeriod: slowPeriod=$slowPeriod, fastPeriod=$fastPeriod"
        }
        require(size.signum() > 0) { "size must be > 0: $size" }
    }

    private val fast = EMA(fastPeriod)
    private val slow = EMA(slowPeriod)
    private var lastFastAboveSlow: Boolean? = null

    override fun onTick(
        tick: Tick,
        emit: (Signal) -> Unit,
    ) {
        // bar-driven; no-op on raw ticks
    }

    override fun onCandle(
        candle: Candle,
        emit: (Signal) -> Unit,
    ) {
        if (candle.symbol != symbol) return
        fast.update(candle.close)
        slow.update(candle.close)
        if (!fast.isReady || !slow.isReady) return

        val fastAbove = fast.value()!! > slow.value()!!
        val prev = lastFastAboveSlow
        lastFastAboveSlow = fastAbove
        if (prev == null) return

        if (!prev && fastAbove) emit(Signal.Buy(symbol, size))
        if (prev && !fastAbove) emit(Signal.Sell(symbol, size))
    }
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.strategy.EmaCrossoverStrategyTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 157 tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/strategy/EmaCrossoverStrategy.kt src/test/kotlin/com/qkt/strategy/EmaCrossoverStrategyTest.kt
git commit -m "feat(strategy): add EmaCrossoverStrategy sample"
```

---

## Task 12: Final verification

No new files. Verify the phase end-to-end. No commit.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 157 tests pass. ktlint clean.

- [ ] **Step 2: Run the demo unchanged**

Run: `./gradlew run`
Expected: same output as Phase 4 (3 FILLED + 7 REJECTED + Done.). The demo strategy is still `EveryNthTickBuyStrategy`; Phase 5 must not change `Main.kt`.

Run: `./gradlew run 2>&1 | grep -cE "^\[engine] (FILLED|REJECTED|CANDLE)" || true`
Expected: 10 (3 FILLED + 7 REJECTED). If different, `Main.kt` was changed unintentionally — revert.

- [ ] **Step 3: Verify file count**

Run: `find src -name "*.kt" -type f | wc -l`
Expected: **75**. Breakdown:
- Phase 4 baseline: 54.
- Phase 5 production (+11): `Indicator.kt`, `Rule.kt`, 8 catalog files (`SMA`, `EMA`, `WMA`, `RSI`, `ATR`, `BollingerBands`, `VWAP`, `MACD`), `EmaCrossoverStrategy.kt`.
- Phase 5 tests (+10): `RuleTest.kt`, 8 catalog tests, `EmaCrossoverStrategyTest.kt`.
- Total: 54 + 11 + 10 = 75.

- [ ] **Step 4: Verify line counts (file size cap)**

Run: `wc -l src/main/kotlin/com/qkt/indicators/Indicator.kt src/main/kotlin/com/qkt/indicators/Rule.kt src/main/kotlin/com/qkt/indicators/catalog/*.kt src/main/kotlin/com/qkt/strategy/EmaCrossoverStrategy.kt | sort -n`
Expected: every file under 150 lines. Largest is likely `Rule.kt` (~85) or `MACD.kt` (~55). All comfortably under the 200-line cap from the project skill.

Run: `wc -l src/test/kotlin/com/qkt/indicators/RuleTest.kt src/test/kotlin/com/qkt/indicators/catalog/*.kt src/test/kotlin/com/qkt/strategy/EmaCrossoverStrategyTest.kt | sort -n`
Expected: every file under 120 lines.

- [ ] **Step 5: Verify ktlint cleanly**

Run: `./gradlew ktlintCheck`
Expected: BUILD SUCCESSFUL. No formatting violations.

If it fails on auto-fixable rules, run `./gradlew ktlintFormat`, stage the result, and amend the most recent commit only if it's the trailing commit; otherwise create a new commit `style: apply ktlint formatting`.

- [ ] **Step 6: Determinism smoke check (optional, ad-hoc)**

Run: `./gradlew test --tests "com.qkt.indicators.catalog.SMATest" --rerun-tasks`
Expected: same 4 tests PASS. No flakes.

- [ ] **Step 7: Run pre-push**

Run: `./scripts/precheck.sh`
Expected: all steps PASS.

- [ ] **Step 8: Verify acceptance criteria from spec §9**

- [x] 8 indicators implemented, each with hand-computed + invariant tests passing.
- [x] `Rule` framework with infix operators (`gt`, `lt`, `eq`, `and`, `or`, `!`) and threshold overloads.
- [x] `Rule.evaluate()` returns `false` when any underlying indicator is not ready.
- [x] `EmaCrossoverStrategy` ships in `com.qkt.strategy`; not wired into `Main.kt`.
- [x] Total test count: 116 → 157, all green.
- [x] `./gradlew run` output unchanged (Main.kt demo unchanged).
- [x] `./gradlew build` clean (ktlint passes; no warnings).
- [x] All Phase 1-4 tests still pass.

When all checked, Phase 5 is done.

- [ ] **Step 9: No commit**

Verification only.

---

## Notes for the implementing engineer

- **Run tests after every TDD task.** Don't batch.
- **Don't use mocks.** Anonymous `object : IndicatorOutput { ... }` impls + capture lists. Real `EMA`/`SMA` instances inside MACD, BollingerBands, EmaCrossoverStrategy.
- **Follow ktlint output.** Run `./gradlew ktlintFormat` if `check` fails on it; stage the formatting with the changes.
- **Match Kotlin style exactly.** No semicolons. No `public`. No emojis. No useless comments. Single-expression functions where possible (`override fun value(): BigDecimal? = ...`).
- **Prefer `MathContext.DECIMAL64` (= `Money.CONTEXT`) for everything except VWAP and BollingerBands variance**, which use `MathContext.DECIMAL128` for accumulator stability. Always set scale to `Money.SCALE` at the boundary (`.setScale(Money.SCALE, Money.ROUNDING)`).
- **`isEqualByComparingTo` for BigDecimal asserts.** `isEqualTo` compares scale; `isEqualByComparingTo` compares value. Use the latter throughout.
- **`require(...)` for argument validation.** `error(...)` for invariant violations (e.g., null tick volume in VWAP).
- **One commit per task.** Subject only. No body. No AI footer. Use the conventional types from the project skill (`feat`, `test`, `refactor`, etc.).
- **Edge cases in the algorithms** are pinned in the spec (e.g., RSI `avgLoss = 0` → 100; VWAP zero denominator → null; ATR no TR on first candle). Re-read §6 of the spec if uncertain.
- **Determinism is automatic.** No randomness, no clocks, no external state. If a test is flaky, it's a bug in the indicator (most likely a mutable accumulator that wasn't reset on the rolling boundary).
- **File size discipline.** Project skill caps files at ~150 lines, hard cap 200. Every file in this phase fits comfortably under 100. If something's growing, you misread the spec.
- **`Strategy.onTick` default is `{}`** in the base interface, but `Strategy.onCandle` also has a `{}` default. `EmaCrossoverStrategy` overrides `onCandle` (bar-driven) and explicitly defines an empty `onTick` to make the no-op intent obvious. Don't omit `onTick` — it's part of the strategy's surface and an empty body documents the design choice.
