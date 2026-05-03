# Trading Engine Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pluggable risk-rule engine that gates between SignalEvent and OrderEvent, plus a position tracker that consumes TradeEvent. Two built-in rules ship: `MaxPositionSize` and `MaxOpenPositions`. Existing `EveryNthTickBuyStrategy` keeps working unchanged; the demo run shows risk approving the first 3 buys then rejecting the rest.

**Architecture:** New `com.qkt.positions` package with `Position` data class + `PositionProvider` read interface + `PositionTracker` impl (updated by an explicit `bus.subscribe<TradeEvent>` in `main()`). New `com.qkt.risk` package with `RiskRule` interface + `Decision` sealed class + `RiskEngine` (evaluates rules in order, first reject wins). New `com.qkt.risk.rules` subpackage with `MaxPositionSize` and `MaxOpenPositions`. New `RiskRejectedEvent` variant of the `Event` sealed class. Risk gate in `main()` runs as the SignalEvent subscriber, producing either `OrderEvent` or `RiskRejectedEvent`.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase3-design.md`](../specs/2026-05-03-trading-engine-phase3-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase3-risk-positions`. Phase 2b is on `main`; Phase 3 spec committed to this branch (commit `4af8658`).

---

## Task ordering

```
1. Position + PositionTracker                                              [TDD]
2. RiskRule + Decision + RiskEngine                                        [TDD]
3. MaxPositionSize rule                                                    [TDD]
4. MaxOpenPositions rule                                                   [TDD]
5. RiskRejectedEvent + EventBus.stamp branch + EventBusTest +1
6. Main.kt + EndToEndTest +3 + wirePipeline extension
7. Final verification                                                       [no new files]
```

7 tasks. Each ends in a commit. Tasks 1–4 are full TDD (red → green). Task 5 extends sealed-class enum + dispatch table. Task 6 wires `main()` and adds integration tests. Task 7 is verification.

---

## Task 1: Position + PositionTracker [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt`
- Create: `src/main/kotlin/com/qkt/positions/Position.kt`
- Create: `src/main/kotlin/com/qkt/positions/PositionProvider.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt`:

```kotlin
package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionTrackerTest {
    private val tracker = PositionTracker()

    private fun trade(
        symbol: String,
        qty: Double,
        side: Side,
        ts: Long = 1000L,
    ) = Trade(orderId = "ORD-X", symbol = symbol, price = 100.0, quantity = qty, side = side, timestamp = ts)

    @Test
    fun `positionFor returns null for unknown symbol`() {
        assertThat(tracker.positionFor("XAUUSD")).isNull()
    }

    @Test
    fun `apply Buy creates position with positive quantity`() {
        tracker.apply(trade("XAUUSD", qty = 1.5, side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")).isEqualTo(Position("XAUUSD", 1.5))
    }

    @Test
    fun `apply Sell from zero creates short position with negative quantity`() {
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.SELL))
        assertThat(tracker.positionFor("XAUUSD")).isEqualTo(Position("XAUUSD", -2.0))
    }

    @Test
    fun `consecutive Buys accumulate quantity`() {
        tracker.apply(trade("XAUUSD", qty = 1.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 0.5, side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualTo(3.5)
    }

    @Test
    fun `Sell that brings quantity to zero removes the position from the map`() {
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.SELL))
        assertThat(tracker.positionFor("XAUUSD")).isNull()
        assertThat(tracker.allPositions()).isEmpty()
    }

    @Test
    fun `tracks positions for multiple symbols independently`() {
        tracker.apply(trade("XAUUSD", qty = 1.0, side = Side.BUY))
        tracker.apply(trade("EURUSD", qty = 5.0, side = Side.BUY))
        tracker.apply(trade("XAUUSD", qty = 2.0, side = Side.BUY))
        assertThat(tracker.positionFor("XAUUSD")?.quantity).isEqualTo(3.0)
        assertThat(tracker.positionFor("EURUSD")?.quantity).isEqualTo(5.0)
        assertThat(tracker.allPositions()).hasSize(2)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.positions.PositionTrackerTest"`
Expected: compile failure — `Unresolved reference 'PositionTracker'` and `Unresolved reference 'Position'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Create `Position.kt`**

`src/main/kotlin/com/qkt/positions/Position.kt`:

```kotlin
package com.qkt.positions

data class Position(
    val symbol: String,
    val quantity: Double,
)
```

- [ ] **Step 4: Create `PositionProvider.kt`**

`src/main/kotlin/com/qkt/positions/PositionProvider.kt`:

```kotlin
package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.Trade

interface PositionProvider {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

class PositionTracker : PositionProvider {
    private val positions = mutableMapOf<String, Position>()

    fun apply(trade: Trade) {
        val current = positions[trade.symbol]?.quantity ?: 0.0
        val delta = if (trade.side == Side.BUY) trade.quantity else -trade.quantity
        val next = current + delta
        if (next == 0.0) {
            positions.remove(trade.symbol)
        } else {
            positions[trade.symbol] = Position(trade.symbol, next)
        }
    }

    override fun positionFor(symbol: String): Position? = positions[symbol]

    override fun allPositions(): Map<String, Position> = positions.toMap()
}
```

Notes:
- Two types in one file (`PositionProvider` interface + `PositionTracker` class) — same pattern as `marketdata/MarketPriceProvider.kt`.
- `allPositions()` returns a `.toMap()` defensive copy — consumers can't mutate the tracker through the returned map.
- `apply` removes zero-quantity entries on close.

- [ ] **Step 5: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.positions.PositionTrackerTest"`
Expected: 6 tests PASS.

- [ ] **Step 6: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean. 71 tests total (65 prior + 6 new).

If ktlint fails, run `./gradlew ktlintFormat` and re-run `./gradlew check`. Accept its formatting.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/positions/Position.kt src/main/kotlin/com/qkt/positions/PositionProvider.kt src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt
git commit -m "feat(positions): add Position and PositionTracker"
```

---

## Task 2: RiskRule + Decision + RiskEngine [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/risk/RiskEngineTest.kt`
- Create: `src/main/kotlin/com/qkt/risk/RiskRule.kt`
- Create: `src/main/kotlin/com/qkt/risk/RiskEngine.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/risk/RiskEngineTest.kt`:

```kotlin
package com.qkt.risk

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.positions.PositionProvider
import com.qkt.positions.PositionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskEngineTest {
    private val positions = PositionTracker()

    private fun order(
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: Double = 1.0,
    ) = Order("ORD-0", symbol, side, qty, OrderType.MARKET, null, 1000L)

    private fun approveAlways() =
        object : RiskRule {
            override fun evaluate(
                order: Order,
                positions: PositionProvider,
            ): Decision = Decision.Approve
        }

    private fun rejectAlways(reason: String) =
        object : RiskRule {
            override fun evaluate(
                order: Order,
                positions: PositionProvider,
            ): Decision = Decision.Reject(reason)
        }

    @Test
    fun `empty rules list always approves`() {
        val engine = RiskEngine(rules = emptyList(), positions = positions)
        assertThat(engine.approve(order())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `single approving rule returns Approve`() {
        val engine = RiskEngine(rules = listOf(approveAlways()), positions = positions)
        assertThat(engine.approve(order())).isEqualTo(Decision.Approve)
    }

    @Test
    fun `single rejecting rule returns Reject with that reason`() {
        val engine = RiskEngine(rules = listOf(rejectAlways("nope")), positions = positions)
        val decision = engine.approve(order())
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).isEqualTo("nope")
    }

    @Test
    fun `evaluates rules in order, first reject wins`() {
        val engine =
            RiskEngine(
                rules = listOf(approveAlways(), rejectAlways("first reject"), rejectAlways("second reject")),
                positions = positions,
            )
        val decision = engine.approve(order())
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).isEqualTo("first reject")
    }

    @Test
    fun `all rules approving returns Approve`() {
        val engine =
            RiskEngine(
                rules = listOf(approveAlways(), approveAlways(), approveAlways()),
                positions = positions,
            )
        assertThat(engine.approve(order())).isEqualTo(Decision.Approve)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.risk.RiskEngineTest"`
Expected: compile failure — `Unresolved reference 'RiskEngine'`, `Unresolved reference 'RiskRule'`, `Unresolved reference 'Decision'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Create `RiskRule.kt`**

`src/main/kotlin/com/qkt/risk/RiskRule.kt`:

```kotlin
package com.qkt.risk

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider

interface RiskRule {
    fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision
}

sealed class Decision {
    data object Approve : Decision()

    data class Reject(val reason: String) : Decision()
}
```

Notes:
- Two types in one file (interface + sealed class) — they're tightly coupled (the interface returns the sealed type).
- `data object Approve` (Kotlin 1.9+) gives a sensible `toString()` and equality semantics.
- The interface signature takes `(order, positions)` only — no `prices` or `clock`. Per spec D8.

- [ ] **Step 4: Create `RiskEngine.kt`**

`src/main/kotlin/com/qkt/risk/RiskEngine.kt`:

```kotlin
package com.qkt.risk

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider

class RiskEngine(
    private val rules: List<RiskRule>,
    private val positions: PositionProvider,
) {
    fun approve(order: Order): Decision {
        for (rule in rules) {
            val decision = rule.evaluate(order, positions)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }
}
```

Notes:
- `for` loop with early-return on first reject.
- `RiskEngine` doesn't implement an interface — it's the concrete glue.
- Tests use anonymous `object : RiskRule { ... }` impls, no mocks needed.

- [ ] **Step 5: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.risk.RiskEngineTest"`
Expected: 5 tests PASS.

- [ ] **Step 6: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean. 76 tests total (71 prior + 5 new).

If ktlint fails, run `./gradlew ktlintFormat` and re-run.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/risk/RiskRule.kt src/main/kotlin/com/qkt/risk/RiskEngine.kt src/test/kotlin/com/qkt/risk/RiskEngineTest.kt
git commit -m "feat(risk): add RiskRule and RiskEngine with Decision sealed class"
```

---

## Task 3: MaxPositionSize rule [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxPositionSizeTest.kt`
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/risk/rules/MaxPositionSizeTest.kt`:

```kotlin
package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxPositionSizeTest {
    private val positions = PositionTracker()
    private val rule = MaxPositionSize("XAUUSD", maxQty = 3.0)

    private fun order(
        symbol: String = "XAUUSD",
        side: Side = Side.BUY,
        qty: Double = 1.0,
    ) = Order("ORD-0", symbol, side, qty, OrderType.MARKET, null, 1000L)

    private fun fill(
        symbol: String,
        qty: Double,
        side: Side,
    ) {
        positions.apply(
            Trade(
                orderId = "ORD-X",
                symbol = symbol,
                price = 100.0,
                quantity = qty,
                side = side,
                timestamp = 1000L,
            ),
        )
    }

    @Test
    fun `approves order for non-target symbol`() {
        fill("XAUUSD", 5.0, Side.BUY) // would exceed cap, but rule is for XAUUSD only
        val decision = rule.evaluate(order(symbol = "EURUSD", qty = 100.0), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order under the cap`() {
        fill("XAUUSD", 1.0, Side.BUY)
        val decision = rule.evaluate(order(qty = 1.0), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order at the cap`() {
        fill("XAUUSD", 2.0, Side.BUY)
        val decision = rule.evaluate(order(qty = 1.0), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects order over the cap on the long side`() {
        fill("XAUUSD", 3.0, Side.BUY)
        val decision = rule.evaluate(order(qty = 1.0), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxPositionSize", "4.0", "3.0", "XAUUSD")
    }

    @Test
    fun `rejects order over the cap on the short side`() {
        fill("XAUUSD", 3.0, Side.SELL) // position becomes -3.0
        val decision = rule.evaluate(order(side = Side.SELL, qty = 1.0), positions) // would project to -4.0
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxPositionSize")
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.risk.rules.MaxPositionSizeTest"`
Expected: compile failure — `Unresolved reference 'MaxPositionSize'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement `MaxPositionSize`**

`src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt`:

```kotlin
package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

class MaxPositionSize(
    private val symbol: String,
    private val maxQty: Double,
) : RiskRule {
    init { require(maxQty > 0.0) { "maxQty must be > 0: $maxQty" } }

    override fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision {
        if (order.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: 0.0
        val projected = if (order.side == Side.BUY) current + order.quantity else current - order.quantity
        return if (kotlin.math.abs(projected) <= maxQty) {
            Decision.Approve
        } else {
            Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
        }
    }
}
```

Notes:
- Symbol-scoped — orders for other symbols pass through with `Approve`.
- "Projected" is the position after the order would be applied. `abs(projected)` caps both long and short.
- Rejection reason includes the values for log clarity.

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.risk.rules.MaxPositionSizeTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean. 81 tests total.

If ktlint fails, run `./gradlew ktlintFormat` and re-run.

- [ ] **Step 6: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt src/test/kotlin/com/qkt/risk/rules/MaxPositionSizeTest.kt
git commit -m "feat(risk): add MaxPositionSize rule"
```

---

## Task 4: MaxOpenPositions rule [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt`
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt`:

```kotlin
package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MaxOpenPositionsTest {
    private val positions = PositionTracker()

    private fun order(
        symbol: String,
        side: Side = Side.BUY,
        qty: Double = 1.0,
    ) = Order("ORD-0", symbol, side, qty, OrderType.MARKET, null, 1000L)

    private fun fill(
        symbol: String,
        qty: Double,
        side: Side,
    ) {
        positions.apply(
            Trade(
                orderId = "ORD-X",
                symbol = symbol,
                price = 100.0,
                quantity = qty,
                side = side,
                timestamp = 1000L,
            ),
        )
    }

    @Test
    fun `approves order when below the limit`() {
        val rule = MaxOpenPositions(maxCount = 2)
        fill("XAUUSD", 1.0, Side.BUY)
        val decision = rule.evaluate(order("EURUSD"), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `approves order for symbol already held even at the limit`() {
        val rule = MaxOpenPositions(maxCount = 2)
        fill("XAUUSD", 1.0, Side.BUY)
        fill("EURUSD", 5.0, Side.BUY)
        val decision = rule.evaluate(order("XAUUSD"), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `rejects new opening when at the limit`() {
        val rule = MaxOpenPositions(maxCount = 2)
        fill("XAUUSD", 1.0, Side.BUY)
        fill("EURUSD", 5.0, Side.BUY)
        val decision = rule.evaluate(order("GBPUSD"), positions)
        assertThat(decision).isInstanceOf(Decision.Reject::class.java)
        assertThat((decision as Decision.Reject).reason).contains("MaxOpenPositions", "2")
    }

    @Test
    fun `approves new opening when previous was closed`() {
        val rule = MaxOpenPositions(maxCount = 1)
        fill("XAUUSD", 1.0, Side.BUY)
        fill("XAUUSD", 1.0, Side.SELL) // closes XAUUSD; entry removed
        val decision = rule.evaluate(order("EURUSD"), positions)
        assertThat(decision).isEqualTo(Decision.Approve)
    }

    @Test
    fun `throws on non-positive maxCount`() {
        assertThatThrownBy { MaxOpenPositions(maxCount = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MaxOpenPositions(maxCount = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.risk.rules.MaxOpenPositionsTest"`
Expected: compile failure — `Unresolved reference 'MaxOpenPositions'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement `MaxOpenPositions`**

`src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt`:

```kotlin
package com.qkt.risk.rules

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

class MaxOpenPositions(
    private val maxCount: Int,
) : RiskRule {
    init { require(maxCount > 0) { "maxCount must be > 0: $maxCount" } }

    override fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision {
        val openingNew = positions.positionFor(order.symbol) == null
        if (!openingNew) return Decision.Approve
        val currentCount = positions.allPositions().size
        return if (currentCount < maxCount) {
            Decision.Approve
        } else {
            Decision.Reject("MaxOpenPositions: $currentCount already open, max $maxCount")
        }
    }
}
```

Notes:
- Only restricts orders that would *open a new position* (no existing entry for that symbol).
- A SELL with no current position counts as opening (creates a short).
- Adjustments to existing positions always pass.

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.risk.rules.MaxOpenPositionsTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean. 86 tests total.

If ktlint fails, run `./gradlew ktlintFormat` and re-run.

- [ ] **Step 6: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt
git commit -m "feat(risk): add MaxOpenPositions rule"
```

---

## Task 5: RiskRejectedEvent + EventBus.stamp branch + EventBusTest +1

**Files:**
- Modify: `src/main/kotlin/com/qkt/events/Event.kt` (add variant)
- Modify: `src/main/kotlin/com/qkt/bus/EventBus.kt` (add `when` branch)
- Modify: `src/test/kotlin/com/qkt/bus/EventBusTest.kt` (add 1 test)

- [ ] **Step 1: Add `RiskRejectedEvent` to `Event.kt`**

Read the current `src/main/kotlin/com/qkt/events/Event.kt`. It contains 5 variants (`TickEvent`, `CandleEvent`, `SignalEvent`, `OrderEvent`, `TradeEvent`). Replace the entire file with:

```kotlin
package com.qkt.events

import com.qkt.execution.Order
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal

sealed class Event {
    abstract val timestamp: Long
    abstract val sequenceId: Long
}

data class TickEvent(
    val tick: Tick,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class CandleEvent(
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class SignalEvent(
    val signal: Signal,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class OrderEvent(
    val order: Order,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class RiskRejectedEvent(
    val order: Order,
    val reason: String,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class TradeEvent(
    val trade: Trade,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()
```

`RiskRejectedEvent` is placed between `OrderEvent` and `TradeEvent` to match the lifecycle (signal → order → reject-or-trade).

- [ ] **Step 2: Compile, expect failure on EventBus.stamp**

Run: `./gradlew compileKotlin`
Expected: compile failure in `EventBus.kt` — the `when (event)` in `stamp` is non-exhaustive because `RiskRejectedEvent` is part of the sealed hierarchy. Error like:

```
src/main/kotlin/com/qkt/bus/EventBus.kt:NN:NN 'when' expression must be exhaustive. Add the 'is RiskRejectedEvent' branch or 'else' branch.
```

This is the desired sealed-class enforcement.

- [ ] **Step 3: Add `is RiskRejectedEvent ->` branch to `EventBus.stamp`**

Read the current `src/main/kotlin/com/qkt/bus/EventBus.kt`. Find the `stamp` method (it currently has 5 branches). Add the import:

```kotlin
import com.qkt.events.RiskRejectedEvent
```

(near the existing event imports, alphabetically).

Then update the `when` block to include the new branch (placed between `OrderEvent` and `TradeEvent` to match Event.kt's ordering):

```kotlin
private fun stamp(event: Event): Event {
    val ts = clock.now()
    val seq = sequencer.next()
    return when (event) {
        is TickEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is CandleEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is OrderEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is RiskRejectedEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is TradeEvent -> event.copy(timestamp = ts, sequenceId = seq)
    }
}
```

Do not change anything else in `EventBus.kt`.

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add the new test to `EventBusTest.kt`**

Read `src/test/kotlin/com/qkt/bus/EventBusTest.kt`. Current file has 11 tests. Add the import alongside existing imports:

```kotlin
import com.qkt.events.RiskRejectedEvent
```

And add this test inside the `EventBusTest` class body (anywhere; after the `bus stamps CandleEvent on publish` test reads naturally):

```kotlin
@Test
fun `bus stamps RiskRejectedEvent on publish`() {
    val bus = newBus()
    val received = mutableListOf<RiskRejectedEvent>()
    bus.subscribe<RiskRejectedEvent> { received.add(it) }

    clock.time = 8888L
    bus.publish(
        RiskRejectedEvent(
            order = Order("ORD-9", "XAUUSD", Side.BUY, 1.0, OrderType.MARKET, null, 1000L),
            reason = "test rejection",
        ),
    )

    assertThat(received).hasSize(1)
    assertThat(received[0].timestamp).isEqualTo(8888L)
    assertThat(received[0].sequenceId).isEqualTo(0L)
    assertThat(received[0].order.id).isEqualTo("ORD-9")
    assertThat(received[0].reason).isEqualTo("test rejection")
}
```

The `Order`, `OrderType`, `Side` imports are already in the file from existing tests.

- [ ] **Step 6: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. 87 tests pass total (86 prior + 1 new). ktlint clean.

If ktlint fails, run `./gradlew ktlintFormat` and re-run.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/events/Event.kt src/main/kotlin/com/qkt/bus/EventBus.kt src/test/kotlin/com/qkt/bus/EventBusTest.kt
git commit -m "feat(events): add RiskRejectedEvent variant and stamp branch"
```

---

## Task 6: Main.kt + EndToEndTest +3 + wirePipeline extension

**Files:**
- Modify (replace): `src/main/kotlin/com/qkt/app/Main.kt`
- Modify: `src/test/kotlin/com/qkt/app/EndToEndTest.kt` (extend `wirePipeline`, add `positions` field, add 3 tests, add imports)

- [ ] **Step 1: Replace `Main.kt`**

Read the current `src/main/kotlin/com/qkt/app/Main.kt` (Phase 2b version, no risk gate). Replace the entire file with:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()
    val positions = PositionTracker()

    val bus = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    CandleAggregator(bus, TimeWindow.ONE_MINUTE)

    val strategies: List<Strategy> =
        listOf(
            EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0),
        )

    val rules: List<RiskRule> =
        listOf(
            MaxPositionSize(symbol = "XAUUSD", maxQty = 3.0),
        )
    val riskEngine = RiskEngine(rules, positions)

    strategies.forEach { strategy ->
        bus.subscribe<TickEvent> { e ->
            strategy.onTick(e.tick) { signal -> bus.publish(SignalEvent(signal)) }
        }
        bus.subscribe<CandleEvent> { e ->
            strategy.onCandle(e.candle) { signal -> bus.publish(SignalEvent(signal)) }
        }
    }

    bus.subscribe<SignalEvent> { e ->
        val order = e.signal.toOrder(ids.next(), clock.now())
        when (val decision = riskEngine.approve(order)) {
            is Decision.Approve -> bus.publish(OrderEvent(order))
            is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
        }
    }

    bus.subscribe<OrderEvent> { e ->
        broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
    }

    bus.subscribe<TradeEvent> { e -> positions.apply(e.trade) }

    bus.subscribe<TradeEvent> { e ->
        val t = e.trade
        val pos = positions.positionFor(t.symbol)?.quantity ?: 0.0
        log.info("FILLED: {} {} {} @ {} (position: {})", t.side, t.quantity, t.symbol, t.price, pos)
    }

    bus.subscribe<RiskRejectedEvent> { e ->
        val o = e.order
        log.info("REJECTED: {} {} {} ({})", o.side, o.quantity, o.symbol, e.reason)
    }

    bus.subscribe<CandleEvent> { e ->
        val c = e.candle
        log.info(
            "CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
            c.symbol,
            c.open,
            c.high,
            c.low,
            c.close,
            c.volume,
            c.startTime,
            c.endTime,
        )
    }

    val feed =
        MockTickFeed(
            symbol = "XAUUSD",
            startPrice = 2400.0,
            count = 100,
            clock = clock,
            tickIntervalMs = 1_000L,
        )
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}

private fun Signal.toOrder(
    id: String,
    ts: Long,
): Order =
    when (this) {
        is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
```

Critical changes from Phase 2b:
- New imports: `RiskRejectedEvent`, `PositionTracker`, `Decision`, `RiskEngine`, `RiskRule`, `MaxPositionSize`.
- New construction: `positions = PositionTracker()`, `rules = listOf(MaxPositionSize("XAUUSD", maxQty = 3.0))`, `riskEngine = RiskEngine(rules, positions)`.
- Restructured `bus.subscribe<SignalEvent>` block — now branches on `riskEngine.approve(order)` returning `Decision.Approve` (publish OrderEvent) or `Decision.Reject` (publish RiskRejectedEvent).
- New `bus.subscribe<TradeEvent> { e -> positions.apply(e.trade) }` — registered FIRST among TradeEvent subscribers.
- Existing FILLED log line now reads `positions.positionFor(...)?.quantity` for the post-trade position.
- New `bus.subscribe<RiskRejectedEvent>` logger.

- [ ] **Step 2: Run main, confirm FILLED + REJECTED + Done**

Run: `./gradlew run`
Expected output: a mix of FILLED, REJECTED, and possibly CANDLE log lines, ending with `Done.`. Approximate shape:

```
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2382.18 (position: 1.0)
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2377.52 (position: 2.0)
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2392.73 (position: 3.0)
[main] INFO Main - REJECTED: BUY 1.0 XAUUSD (MaxPositionSize: |4.0| > 3.0 for XAUUSD)
[main] INFO Main - REJECTED: BUY 1.0 XAUUSD (MaxPositionSize: |4.0| > 3.0 for XAUUSD)
[main] INFO Main - CANDLE: XAUUSD O=... [<startTs>, <endTs>)
[main] INFO Main - REJECTED: BUY 1.0 XAUUSD (MaxPositionSize: |4.0| > 3.0 for XAUUSD)
... more REJECTED, total 7 REJECTED across 10 signals ...
[main] INFO Main - Done.
```

3 FILLED + 7 REJECTED + 1-3 CANDLE depending on clock alignment + Done.

If FILLED is not exactly 3 or REJECTED is not 7, **report DONE_WITH_CONCERNS** with the actual output.

- [ ] **Step 3: Modify `EndToEndTest.kt`: add imports, add `positions` field, extend `wirePipeline`, add 3 tests**

Read `src/test/kotlin/com/qkt/app/EndToEndTest.kt`. The current Phase 2b version has 9 tests, a `wirePipeline(strategies, captureOrders)` helper, and class-level fields including `bus`, `broker`, `engine`, `trades`, `orders`.

**3a. Add imports** (alphabetically with existing imports):

```kotlin
import com.qkt.events.RiskRejectedEvent
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
```

**3b. Add a `positions` field** to the class, alongside the existing private fields (after `tracker = MarketPriceTracker()`):

```kotlin
private val positions = PositionTracker()
```

**3c. Extend `wirePipeline` helper** to take an optional `rules` list and to install the risk gate + position tracker subscriber. Read the existing helper, then replace it with:

```kotlin
private fun wirePipeline(
    strategies: List<Strategy>,
    captureOrders: Boolean = false,
    rules: List<RiskRule> = emptyList(),
) {
    val riskEngine = RiskEngine(rules, positions)
    bus.subscribe<TradeEvent> { e -> positions.apply(e.trade) }
    strategies.forEach { s ->
        bus.subscribe<TickEvent> { e ->
            s.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
        }
        bus.subscribe<CandleEvent> { e ->
            s.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
        }
    }
    bus.subscribe<SignalEvent> { e ->
        val order = e.signal.toOrder(ids.next(), clock.now())
        if (captureOrders) orders.add(order)
        when (val decision = riskEngine.approve(order)) {
            is Decision.Approve -> bus.publish(OrderEvent(order))
            is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
        }
    }
    bus.subscribe<OrderEvent> { e ->
        broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
    }
    bus.subscribe<TradeEvent> { e -> trades.add(e.trade) }
}
```

Critical:
- The new `bus.subscribe<TradeEvent> { e -> positions.apply(e.trade) }` is registered FIRST among TradeEvent subscribers, BEFORE strategy subscriptions and BEFORE the `trades.add(...)` capture. This matches the registration-order invariant in Main.kt.
- The old SignalEvent subscriber (just `bus.publish(OrderEvent(order))`) is replaced with the risk-gated version. Existing tests that pass `rules = emptyList()` get a `RiskEngine` that always approves.
- The default `rules = emptyList()` keeps existing 9 tests' behavior unchanged.

**3d. Add 3 new tests** inside the `EndToEndTest` class body:

```kotlin
@Test
fun `risk approved order produces a fill and updates positions`() {
    val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = 5.0))
    val strategy = buyEveryTick("XAUUSD")
    wirePipeline(listOf(strategy), rules = rules)

    engine.onTick(Tick("XAUUSD", 2400.0, 999L))

    assertThat(trades).hasSize(1)
    assertThat(positions.positionFor("XAUUSD")?.quantity).isEqualTo(1.0)
}

@Test
fun `risk rejected order publishes RiskRejectedEvent and skips broker`() {
    val rejections = mutableListOf<RiskRejectedEvent>()
    bus.subscribe<RiskRejectedEvent> { rejections.add(it) }

    val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = 0.5))
    val strategy = buyEveryTick("XAUUSD")
    wirePipeline(listOf(strategy), rules = rules)

    engine.onTick(Tick("XAUUSD", 2400.0, 999L))

    assertThat(trades).isEmpty()
    assertThat(rejections).hasSize(1)
    assertThat(rejections[0].order.symbol).isEqualTo("XAUUSD")
    assertThat(rejections[0].reason).contains("MaxPositionSize")
}

@Test
fun `position tracker is updated before subsequent FILLED log read`() {
    val seenPositions = mutableListOf<Double?>()
    val strategy = buyEveryTick("XAUUSD")
    wirePipeline(listOf(strategy))
    bus.subscribe<TradeEvent> { e ->
        seenPositions.add(positions.positionFor(e.trade.symbol)?.quantity)
    }

    engine.onTick(Tick("XAUUSD", 2400.0, 999L))

    assertThat(trades).hasSize(1)
    assertThat(seenPositions).containsExactly(1.0)
}
```

The third test verifies the registration-order invariant: the late-registered `bus.subscribe<TradeEvent>` reads `positions.positionFor(...)` AFTER `positions.apply(trade)` has run. Asserts `quantity == 1.0`, confirming the trade has been applied to the tracker before this subscriber observes.

Note: `MaxPositionSize` is referenced but needs an import. Add it alongside the other risk imports:

```kotlin
import com.qkt.risk.rules.MaxPositionSize
```

- [ ] **Step 4: Run EndToEndTest, expect 12 PASS**

Run: `./gradlew test --tests "com.qkt.app.EndToEndTest"`
Expected: 12 tests PASS (9 existing + 3 new). Each prints `EndToEndTest > ...() PASSED`.

If a test fails, **report BLOCKED**. The third test failing would indicate the position tracker subscribe order is wrong.

- [ ] **Step 5: Run full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. 90 tests total (87 prior + 3 new). ktlint clean.

If ktlint fails, run `./gradlew ktlintFormat` and re-run `./gradlew build`.

- [ ] **Step 6: Run main one more time**

Run: `./gradlew run 2>&1 | tail -20`
Expected: 3 FILLED + 7 REJECTED + 1-3 CANDLE + Done.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/app/Main.kt src/test/kotlin/com/qkt/app/EndToEndTest.kt
git commit -m "feat(app): wire risk gate and position tracker"
```

---

## Task 7: Final verification

**Files:** No new files.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Compile + ktlintCheck + 90 tests + assemble.

- [ ] **Step 2: Force test re-run, count PASSED**

Run: `./gradlew test --rerun-tasks 2>&1 | grep -cE "PASSED$"`
Expected: `90`.

- [ ] **Step 3: Run main, confirm FILLED + REJECTED + Done**

Run: `./gradlew run 2>&1 | grep -E "FILLED|REJECTED|CANDLE|Done"`
Expected: 3 FILLED lines, 7 REJECTED lines, 1-3 CANDLE lines, then `Done.`.

If FILLED count is not 3 or REJECTED is not 7, **report DONE_WITH_CONCERNS**.

- [ ] **Step 4: Verify file count and structure**

Run: `find src -name "*.kt" -type f | sort`
Expected: 38 files (28 production + 10 test).

Counts by package:
- Main: app(1) + broker(2) + bus(1) + candles(2) + common(3) + engine(1) + events(1) + execution(3) + marketdata(5) + positions(2) + risk(2) + risk/rules(2) + strategy(3) = 28
- Test: app(1) + broker(1) + bus(1) + candles(2) + common(1) + engine(1) + marketdata(2) + positions(1) + risk(1) + risk/rules(2) + strategy(1) = 14

Total = 28 + 14 = 42 .kt files.

(Plan estimate of 38 was wrong; actual is 42 — 28 production + 14 test. The mismatch is because earlier counts didn't account for two test files in `risk/rules/` and the new `RiskEngineTest`. Actual file count from `find` is the source of truth.)

- [ ] **Step 5: Verify line counts**

Run: `wc -l src/main/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest production file under 120 lines. `Main.kt` is the biggest (~115 lines after Phase 3 additions).

Run: `wc -l src/test/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest test file is `EndToEndTest.kt` at ~340 lines (extends Phase 2b's 278 lines with the +3 risk tests, the `positions` field, and the extended `wirePipeline` helper). This is over the project skill's 200-line cap. **Report DONE_WITH_CONCERNS** noting this; per the project skill it's a refactor signal — Phase 4 (backtest) or a dedicated cleanup phase should split it into `EndToEndTest` (basic pipeline) + `EndToEndCandleTest` + `EndToEndRiskTest`.

Other test files should be under 200 lines.

- [ ] **Step 6: Run precheck**

Run: `./scripts/precheck.sh`
Expected: all 6 steps PASS.

- [ ] **Step 7: Verify acceptance criteria from spec §13**

Cross-check each box:

- [x] `Position`, `PositionProvider`, `PositionTracker` exist in `com.qkt.positions`.
- [x] `RiskRule`, `Decision`, `RiskEngine` exist in `com.qkt.risk`.
- [x] `MaxPositionSize`, `MaxOpenPositions` exist in `com.qkt.risk.rules`.
- [x] `RiskRejectedEvent` exists in `com.qkt.events`; `EventBus.stamp` handles it.
- [x] `Main.kt` wires `PositionTracker` (subscribed to TradeEvent FIRST), `RiskEngine` with `MaxPositionSize("XAUUSD", 3.0)`.
- [x] Risk gate sits between SignalEvent and OrderEvent.
- [x] All 90 tests pass.
- [x] `./gradlew build` green.
- [x] `./gradlew run` produces ~3 FILLED lines + ~7 REJECTED lines + Done.
- [x] Phase 2b acceptance criteria still hold (CANDLE log lines may still appear).
- [x] `EveryNthTickBuyStrategy` continues unchanged.
- [x] `git log` clean history with conventional commit messages.
- [~] `EndToEndTest.kt` exceeds 200 lines (~340) — flagged in DONE_WITH_CONCERNS for refactor in a follow-up.

When all boxes are checked or flagged, Phase 3 is done.

- [ ] **Step 8: No commit**

Task 7 is verification only.

---

## Notes for the implementing engineer

- **Run tests after every TDD task.** Don't batch.
- **Don't use mocks.** Anonymous objects (`object : RiskRule { ... }`) and capture lists are sufficient.
- **Follow ktlint output.** Run `./gradlew ktlintFormat` if `check` fails on it; re-stage the formatted files with the source changes in the same commit.
- **Match Kotlin style exactly.** No semicolons. No `public` modifier. No emojis. No useless comments.
- **Frequent small commits per task.** Each task ends in exactly one commit.
- **If a test fails unexpectedly:** read the error, fix the production code (or the test if it was wrong by construction), re-run.
- **No emoji in code or commit messages.** Per CLAUDE.md and the project skill.
- **Commit message types:** use `feat`, `refactor`, `test`, `build`, `chore`, `docs`, `style` per the project skill §3. Subject only, no body, no footer, no AI references.
- **Task 6's `EndToEndTest.kt` will exceed the 200-line cap.** Per the project skill, that's a refactor signal but not a hard blocker for Phase 3. The `wirePipeline` extension and the +3 tests cause this. A follow-up task can split the file (suggested in the Phase 3 backlog from the spec — to be carried into a Phase 4 backlog).
