# Phase 9 — Risk Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-symbol-only risk with a real risk engine: equity tracking, drawdown online, daily P&L windowed by UTC days, halt rules evaluated continuously on tick/fill, halt blocks new submissions but leaves positions open, per-strategy attribution throughout.

**Architecture:** `RiskState` central component owns `EquityTracker` + `DrawdownTracker` + `DailyPnLTracker` + halt flags. Two rule interfaces: `RiskRule` (submission, existing) + `HaltRule` (continuous, new). `StrategyContext.risk: RiskView` exposes filtered state to strategies. `RiskEvent.Halted`/`Resumed` for observability.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ.

**Spec:** [`../specs/2026-05-06-trading-engine-phase9-design.md`](../specs/2026-05-06-trading-engine-phase9-design.md)

---

## Pre-flight

Branch already exists: `phase9-risk-engine`. Spec already committed.

```bash
git status
git branch --show-current        # phase9-risk-engine
./gradlew check                  # green from main
```

---

## Task 1: `EquityTracker`

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/EquityTracker.kt`
- Create: `src/test/kotlin/com/qkt/risk/EquityTrackerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// src/test/kotlin/com/qkt/risk/EquityTrackerTest.kt
package com.qkt.risk

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquityTrackerTest {
    private fun fill(strategyId: String, symbol: String, side: Side, qty: String, price: String) =
        BrokerEvent.OrderFilled(
            clientOrderId = "c-${System.nanoTime()}",
            brokerOrderId = "b",
            symbol = symbol,
            side = side,
            price = Money.of(price),
            quantity = Money.of(qty),
            strategyId = strategyId,
            timestamp = 0L,
        )

    @Test
    fun `currentEquity tracks realized plus unrealized`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        positions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))
        tracker.update()

        assertThat(tracker.currentEquity()).isEqualByComparingTo(BigDecimal("2000"))
    }

    @Test
    fun `peakEquity is monotonically non-decreasing`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        positions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))
        tracker.update()

        prices.update("BTCUSDT", Money.of("78000"))
        tracker.update()

        assertThat(tracker.peakEquity()).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(tracker.currentEquity()).isEqualByComparingTo(BigDecimal("-2000"))
    }

    @Test
    fun `per-strategy equity is tracked independently`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        strategyPositions.applyFill(fill("B", "ETHUSDT", Side.BUY, "10", "3000"))
        prices.update("BTCUSDT", Money.of("82000"))
        prices.update("ETHUSDT", Money.of("2900"))
        tracker.updateStrategy("A")
        tracker.updateStrategy("B")

        assertThat(tracker.currentEquityFor("A")).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(tracker.currentEquityFor("B")).isEqualByComparingTo(BigDecimal("-1000"))
        assertThat(tracker.peakEquityFor("A")).isEqualByComparingTo(BigDecimal("2000"))
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// src/main/kotlin/com/qkt/risk/EquityTracker.kt
package com.qkt.risk

import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class EquityTracker(
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
) {
    @Volatile
    private var currentTotalEquity: BigDecimal = Money.ZERO

    @Volatile
    private var peakTotalEquity: BigDecimal = Money.ZERO

    private val perStrategyCurrent: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private val perStrategyPeak: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun update() {
        val total = pnl.realizedTotal().add(pnl.unrealizedTotal())
        currentTotalEquity = total
        if (total > peakTotalEquity) peakTotalEquity = total
    }

    fun updateStrategy(strategyId: String) {
        if (strategyId.isBlank()) return
        val total = strategyPnL.totalFor(strategyId)
        perStrategyCurrent[strategyId] = total
        val peak = perStrategyPeak[strategyId] ?: Money.ZERO
        if (total > peak) perStrategyPeak[strategyId] = total
    }

    fun currentEquity(): BigDecimal = currentTotalEquity

    fun peakEquity(): BigDecimal = peakTotalEquity

    fun currentEquityFor(strategyId: String): BigDecimal =
        perStrategyCurrent[strategyId] ?: strategyPnL.totalFor(strategyId)

    fun peakEquityFor(strategyId: String): BigDecimal = perStrategyPeak[strategyId] ?: Money.ZERO
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests com.qkt.risk.EquityTrackerTest`
Expected: PASS, 3 tests green.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/risk/EquityTracker.kt \
        src/test/kotlin/com/qkt/risk/EquityTrackerTest.kt
git commit -m "feat(risk): add EquityTracker for total and per-strategy equity over time"
```

---

## Task 2: `DrawdownTracker`

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/DrawdownTracker.kt`
- Create: `src/test/kotlin/com/qkt/risk/DrawdownTrackerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
// src/test/kotlin/com/qkt/risk/DrawdownTrackerTest.kt
package com.qkt.risk

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownTrackerTest {
    private fun fill(strategyId: String, symbol: String, side: Side, qty: String, price: String) =
        BrokerEvent.OrderFilled(
            clientOrderId = "c-${System.nanoTime()}",
            brokerOrderId = "b",
            symbol = symbol,
            side = side,
            price = Money.of(price),
            quantity = Money.of(qty),
            strategyId = strategyId,
            timestamp = 0L,
        )

    @Test
    fun `drawdown is zero when no positive peak yet`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val equity = EquityTracker(pnl, strategyPnL)
        val drawdown = DrawdownTracker(equity)

        equity.update()

        assertThat(drawdown.globalDrawdown()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `globalDrawdown is fractional peak-to-current`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val equity = EquityTracker(pnl, strategyPnL)
        val drawdown = DrawdownTracker(equity)

        positions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))
        equity.update()
        prices.update("BTCUSDT", Money.of("80000"))    // back to entry
        equity.update()

        // Peak = 2000 (unrealized at +2000), current = 0
        assertThat(drawdown.globalDrawdown()).isEqualByComparingTo(BigDecimal("1.0"))
    }

    @Test
    fun `per-strategy drawdown is independent`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val equity = EquityTracker(pnl, strategyPnL)
        val drawdown = DrawdownTracker(equity)

        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        strategyPositions.applyFill(fill("B", "ETHUSDT", Side.BUY, "10", "3000"))
        prices.update("BTCUSDT", Money.of("82000"))
        prices.update("ETHUSDT", Money.of("3300"))
        equity.updateStrategy("A")
        equity.updateStrategy("B")

        // A's peak = 2000, drop to 1000
        prices.update("BTCUSDT", Money.of("81000"))
        equity.updateStrategy("A")

        // B unchanged at peak
        assertThat(drawdown.strategyDrawdown("A")).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(drawdown.strategyDrawdown("B")).isEqualByComparingTo(Money.ZERO)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// src/main/kotlin/com/qkt/risk/DrawdownTracker.kt
package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

class DrawdownTracker(private val equityTracker: EquityTracker) {
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
}
```

- [ ] **Step 3: Run tests + commit**

```bash
./gradlew test --tests com.qkt.risk.DrawdownTrackerTest
git add src/main/kotlin/com/qkt/risk/DrawdownTracker.kt \
        src/test/kotlin/com/qkt/risk/DrawdownTrackerTest.kt
git commit -m "feat(risk): add DrawdownTracker for online drawdown computation"
```

---

## Task 3: `DailyPnLTracker`

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/DailyPnLTracker.kt`
- Create: `src/test/kotlin/com/qkt/risk/DailyPnLTrackerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
// src/test/kotlin/com/qkt/risk/DailyPnLTrackerTest.kt
package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.common.Money
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyPnLTrackerTest {
    private val day1 = Instant.parse("2024-01-15T10:00:00Z").toEpochMilli()
    private val day2 = Instant.parse("2024-01-16T10:00:00Z").toEpochMilli()

    @Test
    fun `realizedToday accumulates within the same UTC day`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("A", BigDecimal("100"))
        tracker.recordRealized("A", BigDecimal("-30"))

        assertThat(tracker.realizedToday("A")).isEqualByComparingTo(BigDecimal("70"))
    }

    @Test
    fun `realizedToday resets when UTC day changes`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("A", BigDecimal("100"))

        clock.time = day2
        assertThat(tracker.realizedToday("A")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `globalRealizedToday sums across strategies`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("A", BigDecimal("100"))
        tracker.recordRealized("B", BigDecimal("-50"))

        assertThat(tracker.globalRealizedToday()).isEqualByComparingTo(BigDecimal("50"))
    }

    @Test
    fun `blank strategyId still increments global but not per-strategy`() {
        val clock = FixedClock(time = day1)
        val tracker = DailyPnLTracker(clock)

        tracker.recordRealized("", BigDecimal("100"))

        assertThat(tracker.globalRealizedToday()).isEqualByComparingTo(BigDecimal("100"))
        assertThat(tracker.realizedToday("")).isEqualByComparingTo(Money.ZERO)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// src/main/kotlin/com/qkt/risk/DailyPnLTracker.kt
package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class DailyPnLTracker(private val clock: Clock) {
    private val byStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    @Volatile
    private var globalToday: BigDecimal = Money.ZERO

    @Volatile
    private var lastResetEpochDay: Long = epochDay()

    @Synchronized
    fun recordRealized(
        strategyId: String,
        realized: BigDecimal,
    ) {
        rolloverIfNeeded()
        if (strategyId.isNotBlank()) {
            val current = byStrategy[strategyId] ?: Money.ZERO
            byStrategy[strategyId] = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
        }
        globalToday = globalToday.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun globalRealizedToday(): BigDecimal {
        rolloverIfNeeded()
        return globalToday
    }

    fun realizedToday(strategyId: String): BigDecimal {
        rolloverIfNeeded()
        return byStrategy[strategyId] ?: Money.ZERO
    }

    @Synchronized
    private fun rolloverIfNeeded() {
        val today = epochDay()
        if (today != lastResetEpochDay) {
            byStrategy.clear()
            globalToday = Money.ZERO
            lastResetEpochDay = today
        }
    }

    private fun epochDay(): Long =
        Instant.ofEpochMilli(clock.now())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()
}
```

- [ ] **Step 3: Run tests + commit**

```bash
./gradlew test --tests com.qkt.risk.DailyPnLTrackerTest
git add src/main/kotlin/com/qkt/risk/DailyPnLTracker.kt \
        src/test/kotlin/com/qkt/risk/DailyPnLTrackerTest.kt
git commit -m "feat(risk): add DailyPnLTracker with UTC midnight rollover"
```

---

## Task 4: `RiskEvent` family + EventBus exhaustive when

**Files:**
- Create: `src/main/kotlin/com/qkt/events/RiskEvent.kt`
- Modify: `src/main/kotlin/com/qkt/bus/EventBus.kt`

- [ ] **Step 1: Define `RiskEvent`**

```kotlin
// src/main/kotlin/com/qkt/events/RiskEvent.kt
package com.qkt.events

sealed interface RiskEvent : Event {
    data class Halted(
        val reason: String,
        val strategyId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent

    data class Resumed(
        val strategyId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent
}
```

- [ ] **Step 2: Add to `EventBus.publish` exhaustive when**

In `EventBus.kt`, find the existing `when (event)` block. Add two cases:

```kotlin
is RiskEvent.Halted -> event.copy(timestamp = ts, sequenceId = seq)
is RiskEvent.Resumed -> event.copy(timestamp = ts, sequenceId = seq)
```

Position alongside the other event variants.

- [ ] **Step 3: Run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/events/RiskEvent.kt src/main/kotlin/com/qkt/bus/EventBus.kt
git commit -m "feat(events): add RiskEvent Halted and Resumed for risk transitions"
```

---

## Task 5: `RiskState`

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/RiskState.kt`
- Create: `src/test/kotlin/com/qkt/risk/RiskStateTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
// src/test/kotlin/com/qkt/risk/RiskStateTest.kt
package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.RiskEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskStateTest {
    private fun newRiskState(): Pair<RiskState, MutableList<RiskEvent>> {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val state = RiskState(pnl, strategyPnL, clock, bus)

        val events = mutableListOf<RiskEvent>()
        bus.subscribe<RiskEvent.Halted> { events.add(it) }
        bus.subscribe<RiskEvent.Resumed> { events.add(it) }

        return state to events
    }

    @Test
    fun `halt sets halted flag and publishes Halted event`() {
        val (state, events) = newRiskState()

        state.halt("test reason")

        assertThat(state.halted).isTrue
        assertThat(state.haltReason).isEqualTo("test reason")
        assertThat(events).hasSize(1)
        assertThat((events[0] as RiskEvent.Halted).strategyId).isNull()
    }

    @Test
    fun `halt is idempotent`() {
        val (state, events) = newRiskState()

        state.halt("first")
        state.halt("second")

        assertThat(events).hasSize(1)
        assertThat(state.haltReason).isEqualTo("first")
    }

    @Test
    fun `resume clears halted flag and publishes Resumed event`() {
        val (state, events) = newRiskState()

        state.halt("test")
        events.clear()
        state.resume()

        assertThat(state.halted).isFalse
        assertThat(state.haltReason).isNull()
        assertThat(events).hasSize(1)
        assertThat(events[0]).isInstanceOf(RiskEvent.Resumed::class.java)
    }

    @Test
    fun `haltStrategy halts only that strategy`() {
        val (state, events) = newRiskState()

        state.haltStrategy("A", "test")

        assertThat(state.halted).isFalse
        assertThat(state.isStrategyHalted("A")).isTrue
        assertThat(state.isStrategyHalted("B")).isFalse
        assertThat((events[0] as RiskEvent.Halted).strategyId).isEqualTo("A")
    }

    @Test
    fun `global halt makes isStrategyHalted true for all strategies`() {
        val (state, _) = newRiskState()

        state.halt("global")

        assertThat(state.isStrategyHalted("A")).isTrue
        assertThat(state.isStrategyHalted("Z")).isTrue
    }

    @Test
    fun `resumeStrategy is idempotent for non-halted strategy`() {
        val (state, events) = newRiskState()

        state.resumeStrategy("A")

        assertThat(events).isEmpty()
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// src/main/kotlin/com/qkt/risk/RiskState.kt
package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.RiskEvent
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class RiskState(
    pnl: PnLProvider,
    strategyPnL: StrategyPnL,
    private val clock: Clock,
    private val bus: EventBus,
) {
    val equityTracker: EquityTracker = EquityTracker(pnl, strategyPnL)
    val drawdownTracker: DrawdownTracker = DrawdownTracker(equityTracker)
    val dailyPnLTracker: DailyPnLTracker = DailyPnLTracker(clock)

    @Volatile
    var halted: Boolean = false
        private set

    @Volatile
    var haltReason: String? = null
        private set

    @Volatile
    var warmupComplete: Boolean = false

    private val haltedStrategies: MutableMap<String, String> = ConcurrentHashMap()

    fun isStrategyHalted(strategyId: String): Boolean = halted || strategyId in haltedStrategies

    fun haltReasonFor(strategyId: String): String? =
        if (halted) haltReason else haltedStrategies[strategyId]

    fun onTick() {
        equityTracker.update()
    }

    fun onFill(
        strategyId: String,
        realized: BigDecimal,
    ) {
        equityTracker.update()
        equityTracker.updateStrategy(strategyId)
        dailyPnLTracker.recordRealized(strategyId, realized)
    }

    @Synchronized
    fun halt(reason: String) {
        if (halted) return
        halted = true
        haltReason = reason
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = null, timestamp = clock.now()))
    }

    fun haltStrategy(
        strategyId: String,
        reason: String,
    ) {
        if (haltedStrategies.putIfAbsent(strategyId, reason) != null) return
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = strategyId, timestamp = clock.now()))
    }

    @Synchronized
    fun resume() {
        if (!halted) return
        halted = false
        haltReason = null
        bus.publish(RiskEvent.Resumed(strategyId = null, timestamp = clock.now()))
    }

    fun resumeStrategy(strategyId: String) {
        if (haltedStrategies.remove(strategyId) == null) return
        bus.publish(RiskEvent.Resumed(strategyId = strategyId, timestamp = clock.now()))
    }

    companion object {
        fun noOp(clock: Clock = com.qkt.common.SystemClock()): RiskState {
            val sequencer = com.qkt.common.MonotonicSequenceGenerator()
            val bus = EventBus(clock, sequencer)
            val prices = com.qkt.marketdata.MarketPriceTracker()
            val positions = com.qkt.positions.PositionTracker()
            val pnl = com.qkt.pnl.PnLCalculator(positions, prices)
            val strategyPnL = StrategyPnL(com.qkt.positions.StrategyPositionTracker(), prices)
            return RiskState(pnl, strategyPnL, clock, bus)
        }
    }
}
```

- [ ] **Step 3: Run tests + commit**

```bash
./gradlew test --tests com.qkt.risk.RiskStateTest
git add src/main/kotlin/com/qkt/risk/RiskState.kt src/test/kotlin/com/qkt/risk/RiskStateTest.kt
git commit -m "feat(risk): add RiskState with halt mutators and trackers"
```

---

## Task 6: `HaltRule` interface + `HaltDecision`

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/HaltRule.kt`

- [ ] **Step 1: Define interface**

```kotlin
// src/main/kotlin/com/qkt/risk/HaltRule.kt
package com.qkt.risk

interface HaltRule {
    fun evaluate(riskState: RiskState): HaltDecision
}

sealed class HaltDecision {
    object Continue : HaltDecision()

    data class Halt(
        val reason: String,
        val strategyId: String? = null,
    ) : HaltDecision()
}
```

- [ ] **Step 2: Commit (no tests; covered by rule tests in T7)**

```bash
git add src/main/kotlin/com/qkt/risk/HaltRule.kt
git commit -m "feat(risk): add HaltRule interface and HaltDecision sealed class"
```

---

## Task 7: Halt rules (`MaxDrawdown`, `MaxDailyLoss`, per-strategy variants)

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxDrawdown.kt`
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxDailyLoss.kt`
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxStrategyDrawdown.kt`
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxStrategyDailyLoss.kt`
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxDrawdownTest.kt`
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxDailyLossTest.kt`
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxStrategyDrawdownTest.kt`
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxStrategyDailyLossTest.kt`

- [ ] **Step 1: Implement `MaxDrawdown`**

```kotlin
// src/main/kotlin/com/qkt/risk/rules/MaxDrawdown.kt
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal

class MaxDrawdown(
    private val maxFraction: BigDecimal,
) : HaltRule {
    init {
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.drawdownTracker.globalDrawdown()
        return if (dd > maxFraction) {
            HaltDecision.Halt("global drawdown ${dd.setScale(4, java.math.RoundingMode.HALF_UP)} exceeds max $maxFraction")
        } else {
            HaltDecision.Continue
        }
    }
}
```

- [ ] **Step 2: Implement `MaxDailyLoss`**

```kotlin
// src/main/kotlin/com/qkt/risk/rules/MaxDailyLoss.kt
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal

class MaxDailyLoss(
    private val maxLoss: BigDecimal,
) : HaltRule {
    init {
        require(maxLoss.signum() > 0) { "maxLoss must be > 0: $maxLoss" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val realized = riskState.dailyPnLTracker.globalRealizedToday()
        return if (realized.negate() > maxLoss) {
            HaltDecision.Halt("daily loss ${realized.negate()} exceeds max $maxLoss")
        } else {
            HaltDecision.Continue
        }
    }
}
```

- [ ] **Step 3: Implement `MaxStrategyDrawdown`**

```kotlin
// src/main/kotlin/com/qkt/risk/rules/MaxStrategyDrawdown.kt
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal

class MaxStrategyDrawdown(
    private val strategyId: String,
    private val maxFraction: BigDecimal,
) : HaltRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.drawdownTracker.strategyDrawdown(strategyId)
        return if (dd > maxFraction) {
            HaltDecision.Halt(
                reason = "strategy drawdown ${dd.setScale(4, java.math.RoundingMode.HALF_UP)} exceeds max $maxFraction",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
```

- [ ] **Step 4: Implement `MaxStrategyDailyLoss`**

```kotlin
// src/main/kotlin/com/qkt/risk/rules/MaxStrategyDailyLoss.kt
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal

class MaxStrategyDailyLoss(
    private val strategyId: String,
    private val maxLoss: BigDecimal,
) : HaltRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxLoss.signum() > 0) { "maxLoss must be > 0: $maxLoss" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val realized = riskState.dailyPnLTracker.realizedToday(strategyId)
        return if (realized.negate() > maxLoss) {
            HaltDecision.Halt(
                reason = "strategy daily loss ${realized.negate()} exceeds max $maxLoss",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
```

- [ ] **Step 5: Write tests for each rule**

Each test follows the same pattern: construct a `RiskState`, drive it to a state that breaches the threshold, evaluate the rule, assert the decision.

```kotlin
// src/test/kotlin/com/qkt/risk/rules/MaxDrawdownTest.kt
package com.qkt.risk.rules

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.HaltDecision
import com.qkt.risk.RiskState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxDrawdownTest {
    @Test
    fun `Continue when drawdown is below threshold`() {
        val state = setupState(peak = "1000", current = "950")
        val rule = MaxDrawdown(BigDecimal("0.10"))

        assertThat(rule.evaluate(state)).isEqualTo(HaltDecision.Continue)
    }

    @Test
    fun `Halt when drawdown exceeds threshold`() {
        val state = setupState(peak = "1000", current = "700")
        val rule = MaxDrawdown(BigDecimal("0.20"))

        val decision = rule.evaluate(state)
        assertThat(decision).isInstanceOf(HaltDecision.Halt::class.java)
        assertThat((decision as HaltDecision.Halt).strategyId).isNull()
    }

    private fun setupState(peak: String, current: String): RiskState {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val state = RiskState(pnl, strategyPnL, clock, bus)

        positions.applyFill(
            BrokerEvent.OrderFilled(
                clientOrderId = "c", brokerOrderId = "b",
                symbol = "X", side = Side.BUY,
                price = Money.of("100"), quantity = Money.of("10"),
                strategyId = "A", timestamp = 0L,
            ),
        )
        prices.update("X", Money.of((peak.toBigDecimal() / BigDecimal("10") + BigDecimal("100")).toPlainString()))
        state.equityTracker.update()
        prices.update("X", Money.of((current.toBigDecimal() / BigDecimal("10") + BigDecimal("100")).toPlainString()))
        state.equityTracker.update()
        return state
    }
}
```

(Same pattern for other rule tests; equivalent setup with daily-PnL recording for `MaxDailyLoss`/`MaxStrategyDailyLoss`, and per-strategy equity for strategy variants. Mechanical.)

- [ ] **Step 6: Run tests + commit**

```bash
./gradlew test --tests 'com.qkt.risk.rules.*'
git add src/main/kotlin/com/qkt/risk/rules src/test/kotlin/com/qkt/risk/rules
git commit -m "feat(risk): add halt rules MaxDrawdown MaxDailyLoss and per-strategy variants"
```

---

## Task 8: Submission rules (`MaxOpenPositions`, `KillSwitch`)

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt`
- Create: `src/main/kotlin/com/qkt/risk/rules/KillSwitch.kt`
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt`
- Create: `src/test/kotlin/com/qkt/risk/rules/KillSwitchTest.kt`

- [ ] **Step 1: Implement `MaxOpenPositions`**

```kotlin
// src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt
package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

class MaxOpenPositions(
    private val maxCount: Int,
) : RiskRule {
    init {
        require(maxCount > 0) { "maxCount must be > 0: $maxCount" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val openCount = positions.allPositions().count { it.value.quantity.signum() != 0 }
        val existing = positions.positionFor(request.symbol)
        val isNewSymbol = existing == null || existing.quantity.signum() == 0
        return if (isNewSymbol && openCount >= maxCount) {
            Decision.Reject("max open positions reached: $openCount")
        } else {
            Decision.Approve
        }
    }
}
```

- [ ] **Step 2: Implement `KillSwitch`**

```kotlin
// src/main/kotlin/com/qkt/risk/rules/KillSwitch.kt
package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState

class KillSwitch(
    private val riskState: RiskState,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision =
        if (riskState.isStrategyHalted(request.strategyId)) {
            val reason = riskState.haltReasonFor(request.strategyId) ?: "halted"
            Decision.Reject("kill switch: $reason")
        } else {
            Decision.Approve
        }
}
```

- [ ] **Step 3: Write tests**

```kotlin
// src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt
package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxOpenPositionsTest {
    private fun req(symbol: String) =
        OrderRequest.Market(
            id = "c1", symbol = symbol,
            side = Side.BUY, quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
            strategyId = "A",
        )

    private fun provider(positions: Map<String, Position>) =
        object : PositionProvider {
            override fun positionFor(symbol: String): Position? = positions[symbol]

            override fun allPositions(): Map<String, Position> = positions
        }

    @Test
    fun `Approve when below cap`() {
        val rule = MaxOpenPositions(2)
        val provider = provider(mapOf("X" to Position("X", Money.of("1"), Money.of("100"))))

        assertThat(rule.evaluate(req("Y"), provider)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `Reject new symbol when at cap`() {
        val rule = MaxOpenPositions(2)
        val provider =
            provider(
                mapOf(
                    "X" to Position("X", Money.of("1"), Money.of("100")),
                    "Y" to Position("Y", Money.of("1"), Money.of("100")),
                ),
            )

        val decision = rule.evaluate(req("Z"), provider)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `Approve existing symbol even at cap`() {
        val rule = MaxOpenPositions(2)
        val provider =
            provider(
                mapOf(
                    "X" to Position("X", Money.of("1"), Money.of("100")),
                    "Y" to Position("Y", Money.of("1"), Money.of("100")),
                ),
            )

        assertThat(rule.evaluate(req("X"), provider)).isEqualTo(Decision.Approve)
    }
}
```

```kotlin
// src/test/kotlin/com/qkt/risk/rules/KillSwitchTest.kt
package com.qkt.risk.rules

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KillSwitchTest {
    private fun newState(): RiskState {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        return RiskState(pnl, strategyPnL, clock, bus)
    }

    private val anyRequest =
        OrderRequest.Market(
            id = "c1", symbol = "X",
            side = Side.BUY, quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
            strategyId = "A",
        )

    @Test
    fun `Approve when not halted`() {
        val state = newState()
        val rule = KillSwitch(state)

        assertThat(rule.evaluate(anyRequest, PositionTracker())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `Reject when globally halted`() {
        val state = newState()
        state.halt("test")
        val rule = KillSwitch(state)

        val decision = rule.evaluate(anyRequest, PositionTracker())
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("test")
    }

    @Test
    fun `Reject when strategy halted`() {
        val state = newState()
        state.haltStrategy("A", "strategy halt")
        val rule = KillSwitch(state)

        assertThat(rule.evaluate(anyRequest, PositionTracker())).isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `Approve when other strategy halted`() {
        val state = newState()
        state.haltStrategy("B", "B halt")
        val rule = KillSwitch(state)

        assertThat(rule.evaluate(anyRequest, PositionTracker())).isEqualTo(Decision.Approve)
    }
}
```

- [ ] **Step 4: Run tests + commit**

```bash
./gradlew test --tests 'com.qkt.risk.rules.MaxOpenPositionsTest' --tests 'com.qkt.risk.rules.KillSwitchTest'
git add src/main/kotlin/com/qkt/risk/rules src/test/kotlin/com/qkt/risk/rules
git commit -m "feat(risk): add submission rules MaxOpenPositions and KillSwitch"
```

---

## Task 9: `RiskView` interface + impl

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/RiskView.kt`

- [ ] **Step 1: Define interface + impl**

```kotlin
// src/main/kotlin/com/qkt/risk/RiskView.kt
package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

interface RiskView {
    val halted: Boolean

    val haltReason: String?

    val currentEquity: BigDecimal

    val drawdown: BigDecimal

    val realizedToday: BigDecimal

    val globalHalted: Boolean

    val globalDrawdown: BigDecimal
}

internal class RiskViewImpl(
    private val riskState: RiskState,
    private val strategyId: String,
) : RiskView {
    override val halted: Boolean
        get() = riskState.isStrategyHalted(strategyId)

    override val haltReason: String?
        get() = riskState.haltReasonFor(strategyId)

    override val currentEquity: BigDecimal
        get() = riskState.equityTracker.currentEquityFor(strategyId)

    override val drawdown: BigDecimal
        get() = riskState.drawdownTracker.strategyDrawdown(strategyId)

    override val realizedToday: BigDecimal
        get() = riskState.dailyPnLTracker.realizedToday(strategyId)

    override val globalHalted: Boolean
        get() = riskState.halted

    override val globalDrawdown: BigDecimal
        get() = riskState.drawdownTracker.globalDrawdown()
}

internal class NoOpRiskView : RiskView {
    override val halted: Boolean = false
    override val haltReason: String? = null
    override val currentEquity: BigDecimal = Money.ZERO
    override val drawdown: BigDecimal = Money.ZERO
    override val realizedToday: BigDecimal = Money.ZERO
    override val globalHalted: Boolean = false
    override val globalDrawdown: BigDecimal = Money.ZERO
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/com/qkt/risk/RiskView.kt
git commit -m "feat(risk): add RiskView read-only filter for strategies"
```

---

## Task 10: `StrategyContext.risk` field + test helper

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/StrategyContext.kt`
- Modify: `src/test/kotlin/com/qkt/strategy/TestStrategyContext.kt`

- [ ] **Step 1: Add `risk` field to `StrategyContext`**

```kotlin
data class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val positions: StrategyPositionView,
    val pnl: StrategyPnLView,
    val risk: RiskView,
)
```

Add `import com.qkt.risk.RiskView`.

- [ ] **Step 2: Update `testStrategyContext()` helper**

In `TestStrategyContext.kt`, add a no-op default:

```kotlin
import com.qkt.risk.NoOpRiskView
import com.qkt.risk.RiskView

fun testStrategyContext(
    strategyId: String = "test",
    // ... existing params ...
    risk: RiskView = NoOpRiskView(),
): StrategyContext = StrategyContext(
    strategyId = strategyId,
    // ... existing ...
    risk = risk,
)
```

`NoOpRiskView` is `internal` — make it accessible to the test helper. Either move to a shared location or change access modifier. Cleanest: change `internal class NoOpRiskView` to `class NoOpRiskView` (it's a no-op stub, no leakage).

- [ ] **Step 3: Run tests; fix any remaining `StrategyContext(...)` constructions**

```bash
./gradlew test 2>&1 | grep -E 'error:|e: ' | head -10
```

Any test that constructs `StrategyContext(...)` directly without `risk` needs the field. Strategy/sample tests use `testStrategyContext()` which has the default — those are fine.

- [ ] **Step 4: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add -A
git commit -m "feat(strategy): add risk field to StrategyContext with NoOpRiskView default"
```

---

## Task 11: `RiskEngine` extension

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/RiskEngine.kt`
- Create: `src/test/kotlin/com/qkt/risk/RiskEngineHaltRulesTest.kt`

- [ ] **Step 1: Inspect existing `RiskEngine`**

```bash
cat src/main/kotlin/com/qkt/risk/RiskEngine.kt
```

- [ ] **Step 2: Extend the class**

```kotlin
// src/main/kotlin/com/qkt/risk/RiskEngine.kt
package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import org.slf4j.LoggerFactory

class RiskEngine(
    private val rules: List<RiskRule>,
    private val haltRules: List<HaltRule>,
    private val positions: PositionProvider,
    private val riskState: RiskState,
) {
    private val log = LoggerFactory.getLogger(RiskEngine::class.java)

    constructor(
        rules: List<RiskRule>,
        positions: PositionProvider,
    ) : this(rules, emptyList(), positions, RiskState.noOp())

    fun approve(request: OrderRequest): Decision {
        if (riskState.isStrategyHalted(request.strategyId)) {
            val reason = riskState.haltReasonFor(request.strategyId) ?: "halted"
            return Decision.Reject("halted: $reason")
        }
        for (rule in rules) {
            val decision = rule.evaluate(request, positions)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }

    fun evaluateHaltRules() {
        if (!riskState.warmupComplete) return
        for (rule in haltRules) {
            runCatching {
                when (val decision = rule.evaluate(riskState)) {
                    HaltDecision.Continue -> Unit
                    is HaltDecision.Halt ->
                        if (decision.strategyId != null) {
                            riskState.haltStrategy(decision.strategyId, decision.reason)
                        } else {
                            riskState.halt(decision.reason)
                        }
                }
            }.onFailure { log.warn("HaltRule {} threw: {}", rule::class.simpleName, it.message) }
        }
    }
}
```

- [ ] **Step 3: Test halt rule cascade**

```kotlin
// src/test/kotlin/com/qkt/risk/RiskEngineHaltRulesTest.kt
package com.qkt.risk

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskEngineHaltRulesTest {
    @Test
    fun `evaluateHaltRules halts when a rule returns Halt`() {
        val state = newRiskState()
        state.warmupComplete = true
        val haltingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = HaltDecision.Halt("forced")
            }
        val engine = RiskEngine(emptyList(), listOf(haltingRule), PositionTracker(), state)

        engine.evaluateHaltRules()

        assertThat(state.halted).isTrue
        assertThat(state.haltReason).isEqualTo("forced")
    }

    @Test
    fun `evaluateHaltRules skips during warmup`() {
        val state = newRiskState()
        // warmupComplete = false (default)
        val haltingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = HaltDecision.Halt("forced")
            }
        val engine = RiskEngine(emptyList(), listOf(haltingRule), PositionTracker(), state)

        engine.evaluateHaltRules()

        assertThat(state.halted).isFalse
    }

    @Test
    fun `evaluateHaltRules tolerates rule that throws`() {
        val state = newRiskState()
        state.warmupComplete = true
        val throwingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = error("boom")
            }
        val haltingRule =
            object : HaltRule {
                override fun evaluate(riskState: RiskState): HaltDecision = HaltDecision.Halt("after the throw")
            }
        val engine = RiskEngine(emptyList(), listOf(throwingRule, haltingRule), PositionTracker(), state)

        engine.evaluateHaltRules()

        assertThat(state.halted).isTrue
        assertThat(state.haltReason).isEqualTo("after the throw")
    }

    private fun newRiskState(): RiskState {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        return RiskState(pnl, strategyPnL, clock, bus)
    }
}
```

- [ ] **Step 4: Run tests + commit**

```bash
./gradlew test --tests 'com.qkt.risk.RiskEngineHaltRulesTest'
git add src/main/kotlin/com/qkt/risk/RiskEngine.kt src/test/kotlin/com/qkt/risk/RiskEngineHaltRulesTest.kt
git commit -m "feat(risk): RiskEngine evaluateHaltRules with warmup-skip and rule isolation"
```

---

## Task 12: `TradingPipeline` integration

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`

- [ ] **Step 1: Add `riskState` parameter**

In `TradingPipeline.kt`, add `riskState: RiskState` as a constructor parameter. Wire the strategy context to use `RiskViewImpl`:

```kotlin
class TradingPipeline(
    // ... existing ...
    val riskState: RiskState,
    // ... existing ...
) {
    init {
        // ... existing strategy iteration ...
        strategies.forEach { (strategyId, strategy) ->
            val ctx =
                StrategyContext(
                    strategyId = strategyId,
                    mode = mode,
                    clock = clock,
                    calendar = calendar,
                    source = source,
                    positions = StrategyPositionViewImpl(strategyPositions, strategyId),
                    pnl = StrategyPnLViewImpl(strategyPnL, strategyId),
                    risk = com.qkt.risk.RiskViewImpl(riskState, strategyId),     // NEW
                )
            // ... existing subscriptions ...
        }

        bus.subscribe<TickEvent> { e ->
            riskState.onTick()                                                   // NEW
            riskEngine.evaluateHaltRules()                                       // NEW
        }

        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            // ... existing applyFill / pnl.recordRealized / strategyPnL.recordRealized ...
            riskState.onFill(e.strategyId, stratRealized)                        // NEW
            riskEngine.evaluateHaltRules()                                       // NEW
            // ... existing TradeEvent publishing ...
        }
    }
}
```

`RiskViewImpl` was made `internal` in T9 — change to `public class` so the pipeline can construct it across packages, OR make TradingPipeline use a factory. Cleanest: drop `internal` keyword.

- [ ] **Step 2: Run tests**

`./gradlew test` will fail on every `TradingPipeline(...)` call site — they need `riskState`. Fix in T13.

- [ ] **Step 3: Skip commit until T13 lands too — these tasks are coupled**

Move directly to T13.

---

## Task 13: Application updates (Backtest, LiveSession, Main, tests)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Modify: `src/main/kotlin/com/qkt/app/Main.kt`
- Modify: tests that construct `TradingPipeline` directly

- [ ] **Step 1: `Backtest.run()` constructs `RiskState`**

```kotlin
val riskState = RiskState(pnl, strategyPnL, clock, bus)
val riskEngine = RiskEngine(rules, emptyList(), positions, riskState)
riskState.warmupComplete = true

val pipeline = TradingPipeline(
    // ... existing args ...
    riskState = riskState,
)
```

- [ ] **Step 2: `LiveSession.start()` constructs `RiskState`**

Same pattern. Set `warmupComplete = true` after the IndicatorWarmer phase completes.

- [ ] **Step 3: `Main.kt` constructs `RiskState`**

Same pattern.

- [ ] **Step 4: Update test pipelines (`TradingPipelineWarmupSplitTest`, `IndicatorWarmerTest`, `EndToEndTest`)**

Each of these constructs `TradingPipeline` directly. Add `RiskState` construction and pass to pipeline.

```bash
grep -rln 'TradingPipeline(' src/test/kotlin/com/qkt/app
```

For each, add:
```kotlin
val riskState = RiskState(pnl, strategyPnL, clock, bus)
// ... pipeline construction with riskState = riskState ...
```

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add -A
git commit -m "feat(app): wire RiskState through TradingPipeline and entry points"
```

---

## Task 14: Full check + demo invariant

- [ ] **Step 1: Clean build + tests**

```bash
./gradlew clean check
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Demo invariant**

```bash
./gradlew run 2>&1 | grep -cE 'FILLED|REJECTED'
```

Expected: `10`.

- [ ] **Step 3: Commit ktlint reformats if any**

```bash
git status --short
git add -A
git commit -m "style: ktlintFormat after 9 additions" || true
```

---

## Task 15: Phase 9 changelog

**Files:**
- Create: `docs/phases/phase-9-risk-engine.md`

Use 8's changelog as a template. Required sections per qkt skill §6:

1. **Summary** — 2-4 sentences.
2. **What's new** — bullet list covering: trackers, RiskState, HaltRule + HaltDecision, halt rules, submission rules, RiskView, StrategyContext.risk, RiskEvent.
3. **Migration from previous phase** — RiskEngine constructor change, StrategyContext extension, pipeline new param.
4. **Usage cookbook** — at least 5 worked examples:
   - Configure halt rules at startup
   - Strategy reads its own drawdown via `ctx.risk`
   - Subscribe to `RiskEvent.Halted` for alerting
   - Operator manually halts and resumes
   - Per-strategy halt without global halt
5. **Testing patterns** — `RiskState.noOp()` for tests, `setupState` helpers.
6. **Known limitations** — no auto-flatten, no auto-resume, no notional caps, no leverage rules, etc.
7. **References** — spec, plan, prior phases.

Aim 200-500 lines.

- [ ] **Step 1: Write changelog**

(Free-form following template.)

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-9-risk-engine.md
git commit -m "docs: add phase 9 changelog"
```

---

## Task 16: Final verification + finishing-a-development-branch

- [ ] **Step 1: Verify branch state**

```bash
git log --oneline main..HEAD
git status --short
./gradlew check
```

- [ ] **Step 2: Use finishing-a-development-branch skill**

Default for this project: option 1 (`--no-ff` merge to main, delete branch).

Merge commit: `merge: phase 9 risk engine`.

Post-merge:

```bash
git checkout main
git log --oneline -5
./gradlew check
```

Expected: BUILD SUCCESSFUL.

---

## Self-Review Checklist

- [ ] Every type / method referenced in a later task is defined in an earlier task.
- [ ] No "TBD", "TODO", "fill in", "similar to above" text.
- [ ] Every code step shows actual code.
- [ ] Every test step has both code and verification command.
- [ ] Spec coverage:
  - Spec §5 EquityTracker → T1
  - Spec §6 DrawdownTracker → T2
  - Spec §7 DailyPnLTracker → T3
  - Spec §8 RiskState → T5
  - Spec §9 RiskEvent → T4
  - Spec §10 HaltRule + halt rules → T6, T7
  - Spec §11 Submission rules → T8
  - Spec §12 RiskView + StrategyContext.risk → T9, T10
  - Spec §13 RiskEngine extensions → T11
  - Spec §14 testing → all tasks
  - Spec §15 race conditions covered in tests where relevant
  - Spec §19 migration → T13
