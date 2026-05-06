# Phase 7d-a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace synchronous `Broker.execute(order): Trade?` with an async, event-based broker model. Ship the `OrderRequest` sealed type (Market, Limit, Stop, StopLimit, IfTouched), `BrokerEvent` sealed type, broker capabilities, `LogBroker` reference impl, and `PaperBroker` (refactor of `MockBroker` with in-process Tier 1+2 simulation). Wire `TradingPipeline` to subscribe to `OrderFilled` events for `PositionTracker`/`PnL` updates.

**Architecture:** New `Broker` interface (`submit`/`cancel` + `capabilities`). Brokers push `BrokerEvent`s onto the existing `EventBus`. `OrderRequest` replaces flat `Order` + `OrderType`. Pipeline calls `broker.submit(...)` and subscribes to events; no `OrderManager` yet (deferred to 7d-b). `Strategy.onTick(emit)` continues to accept `Signal.Buy` / `Signal.Sell` (sugar for Market) plus a new `Signal.Submit(OrderRequest)` for non-Market orders.

**Tech Stack:** Kotlin (data classes + sealed types), JUnit 5, AssertJ. Existing `EventBus` (Phase 2) and `MarketPriceProvider` (Phase 1) are unchanged.

---

## File structure overview

### New files

```
src/main/kotlin/com/qkt/execution/
  TimeInForce.kt
  OrderRequest.kt           # sealed; Market, Limit, Stop, StopLimit, IfTouched, TriggerType
  events/
    BrokerEvent.kt          # sealed; Accepted, Rejected, PartiallyFilled, Filled, Cancelled

src/main/kotlin/com/qkt/broker/
  OrderTypeCapability.kt    # enum
  SubmitAck.kt              # data class
  LogBroker.kt              # logs-only reference impl
  PaperBroker.kt            # in-process simulation (was MockBroker)

src/test/kotlin/com/qkt/execution/
  OrderRequestTest.kt
  events/BrokerEventTest.kt

src/test/kotlin/com/qkt/broker/
  LogBrokerTest.kt
  PaperBrokerTest.kt        # supersedes MockBrokerTest
```

### Modified files

```
src/main/kotlin/com/qkt/broker/Broker.kt              # new contract
src/main/kotlin/com/qkt/execution/OrderFactory.kt     # Signal → OrderRequest
src/main/kotlin/com/qkt/execution/Trade.kt            # constructed from OrderFilled, kept as record
src/main/kotlin/com/qkt/strategy/Signal.kt            # add Submit variant
src/main/kotlin/com/qkt/positions/PositionTracker.kt  # add applyFill(OrderFilled)
src/main/kotlin/com/qkt/risk/RiskRule.kt              # operates on OrderRequest
src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt # OrderRequest
src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt # OrderRequest (if it references Order)
src/main/kotlin/com/qkt/app/TradingPipeline.kt        # event-based wiring
src/main/kotlin/com/qkt/app/Backtest.kt               # follow-on edits
src/main/kotlin/com/qkt/app/LiveSession.kt            # follow-on edits
src/main/kotlin/com/qkt/app/LiveDemo.kt               # PaperBroker rename
src/main/kotlin/com/qkt/app/Main.kt                   # PaperBroker rename
src/main/kotlin/com/qkt/app/MaxAudit.kt               # PaperBroker rename if used; otherwise no change

src/test/kotlin/...                                    # ~10 test files migrate
```

### Deleted files

```
src/main/kotlin/com/qkt/execution/Order.kt            # replaced by OrderRequest
src/main/kotlin/com/qkt/execution/OrderType.kt        # folded into OrderRequest sealed type
src/main/kotlin/com/qkt/broker/MockBroker.kt          # renamed to PaperBroker
src/test/kotlin/com/qkt/broker/MockBrokerTest.kt      # renamed to PaperBrokerTest
```

---

## Task summary

| # | Group | Title |
|---|---|---|
| 1 | A | Add `TimeInForce` enum |
| 2 | A | Add `OrderTypeCapability` enum and `SubmitAck` data class |
| 3 | A | Add `OrderRequest` sealed type and `TriggerType` enum |
| 4 | A | Add `BrokerEvent` sealed type |
| 5 | B | Rewrite `Broker` interface (capabilities + submit/cancel) |
| 6 | B | Add `LogBroker` reference impl |
| 7 | B | Refactor `MockBroker` → `PaperBroker` (event-emitting) |
| 8 | C | Add `PositionTracker.applyFill(BrokerEvent.OrderFilled)` |
| 9 | C | Add `Signal.Submit(OrderRequest)` variant |
| 10 | C | Refactor `OrderFactory`: `Signal → OrderRequest` |
| 11 | C | Refactor `RiskRule`: operate on `OrderRequest` |
| 12 | C | Refactor `MaxPositionSize` for `OrderRequest` |
| 13 | D | Rewire `TradingPipeline` to event subscriptions |
| 14 | E | Delete obsolete `Order` and `OrderType` |
| 15 | E | Migrate `MockBrokerTest` → `PaperBrokerTest` |
| 16 | E | Migrate downstream tests (Risk*, EndToEnd, IndicatorWarmer, EventBus) |
| 17 | E | Update entry points (`Main`, `LiveDemo`, `LiveSession`, `Backtest`) |
| 18 | F | Full build + test |
| 19 | F | Verify `MaxAudit` audit runner |
| 20 | F | Final verification + branch cleanup |

Cumulative test counts (rough):

| After task | Δ tests | Cumulative |
|---|---|---|
| Pre-7d-a baseline | — | ~399 |
| 4  (BrokerEvent) | +5 | 404 |
| 6  (LogBroker)   | +6 | 410 |
| 7  (PaperBroker) | +12 | 422 |
| 12 (Risk migration) | 0 (test diffs) | 422 |
| 13 (Pipeline)    | 0 (test diffs) | 422 |
| 16 (Test migration) | 0 (rewrites) | 422 |

Final: **~422 tests** (existing tests migrate, ~23 new tests for the new types).

---

## Group A: type system foundation

### Task 1: Add `TimeInForce` enum

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/TimeInForce.kt`

- [ ] **Step 1: Create the enum**

`src/main/kotlin/com/qkt/execution/TimeInForce.kt`:

```kotlin
package com.qkt.execution

enum class TimeInForce {
    DAY,
    GTC,
    IOC,
    FOK,
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/TimeInForce.kt
git commit -m "feat(execution): add TimeInForce enum"
```

---

### Task 2: Add `OrderTypeCapability` enum and `SubmitAck` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt`
- Create: `src/main/kotlin/com/qkt/broker/SubmitAck.kt`

- [ ] **Step 1: Create the capability enum**

`src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt`:

```kotlin
package com.qkt.broker

enum class OrderTypeCapability {
    MARKET,
    LIMIT,
    STOP,
    STOP_LIMIT,
    BRACKET,
    IF_TOUCHED,
    MODIFY,
}
```

- [ ] **Step 2: Create the SubmitAck data class**

`src/main/kotlin/com/qkt/broker/SubmitAck.kt`:

```kotlin
package com.qkt.broker

data class SubmitAck(
    val clientOrderId: String,
    val brokerOrderId: String?,
    val accepted: Boolean,
    val rejectReason: String? = null,
)
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt src/main/kotlin/com/qkt/broker/SubmitAck.kt
git commit -m "feat(broker): add OrderTypeCapability and SubmitAck"
```

---

### Task 3: Add `OrderRequest` sealed type and `TriggerType` enum

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Create: `src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`

`OrderRequest` is the immutable representation of a strategy's order intent. Phase 7d-a ships Tier 1 (Market, Limit) and Tier 2 (Stop, StopLimit, IfTouched). Phase 7d-b adds composites (Bracket, OTO, OCO, ScaleOut, TimeExit) and engine-side types (TrailingStop, TrailingStopLimit).

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`:

```kotlin
package com.qkt.execution

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestTest {
    @Test
    fun `Market constructs with required fields`() {
        val m =
            OrderRequest.Market(
                id = "o1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(m.symbol).isEqualTo("EURUSD")
        assertThat(m.timeInForce).isEqualTo(TimeInForce.GTC)
    }

    @Test
    fun `Limit carries limitPrice`() {
        val l =
            OrderRequest.Limit(
                id = "o2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(l.limitPrice).isEqualByComparingTo(Money.of("1.10"))
    }

    @Test
    fun `Stop carries stopPrice`() {
        val s =
            OrderRequest.Stop(
                id = "o3",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(s.stopPrice).isEqualByComparingTo(Money.of("1.09"))
    }

    @Test
    fun `StopLimit carries both stopPrice and limitPrice`() {
        val sl =
            OrderRequest.StopLimit(
                id = "o4",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                limitPrice = Money.of("1.085"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(sl.stopPrice).isEqualByComparingTo(Money.of("1.09"))
        assertThat(sl.limitPrice).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `IfTouched MARKET trigger requires no limitPrice`() {
        val it =
            OrderRequest.IfTouched(
                id = "o5",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.MARKET,
                limitPrice = null,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        assertThat(it.onTrigger).isEqualTo(TriggerType.MARKET)
        assertThat(it.limitPrice).isNull()
    }

    @Test
    fun `IfTouched LIMIT trigger requires limitPrice`() {
        assertThatThrownBy {
            OrderRequest.IfTouched(
                id = "o6",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.LIMIT,
                limitPrice = null,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("limitPrice")
    }

    @Test
    fun `quantity must be positive`() {
        assertThatThrownBy {
            OrderRequest.Market(
                id = "o7",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("quantity")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.execution.OrderRequestTest"`
Expected: compile failure (`Unresolved reference: OrderRequest`).

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/execution/OrderRequest.kt`:

```kotlin
package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

enum class TriggerType { MARKET, LIMIT }

sealed interface OrderRequest {
    val id: String
    val symbol: String
    val side: Side
    val quantity: BigDecimal
    val timeInForce: TimeInForce
    val timestamp: Long

    data class Market(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }

    data class Limit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val limitPrice: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(limitPrice.signum() > 0) { "limitPrice must be > 0: $limitPrice" }
        }
    }

    data class Stop(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val stopPrice: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(stopPrice.signum() > 0) { "stopPrice must be > 0: $stopPrice" }
        }
    }

    data class StopLimit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val stopPrice: BigDecimal,
        val limitPrice: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(stopPrice.signum() > 0) { "stopPrice must be > 0: $stopPrice" }
            require(limitPrice.signum() > 0) { "limitPrice must be > 0: $limitPrice" }
        }
    }

    data class IfTouched(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val triggerPrice: BigDecimal,
        val onTrigger: TriggerType,
        val limitPrice: BigDecimal? = null,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(triggerPrice.signum() > 0) { "triggerPrice must be > 0: $triggerPrice" }
            if (onTrigger == TriggerType.LIMIT) {
                requireNotNull(limitPrice) { "IfTouched.LIMIT requires limitPrice" }
                require(limitPrice.signum() > 0) { "limitPrice must be > 0: $limitPrice" }
            }
        }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.execution.OrderRequestTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt src/test/kotlin/com/qkt/execution/OrderRequestTest.kt
git commit -m "feat(execution): add OrderRequest sealed type"
```

---

### Task 4: Add `BrokerEvent` sealed type

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/events/BrokerEvent.kt`
- Create: `src/test/kotlin/com/qkt/execution/events/BrokerEventTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/execution/events/BrokerEventTest.kt`:

```kotlin
package com.qkt.execution.events

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerEventTest {
    @Test
    fun `Accepted carries client and broker order ids`() {
        val e = BrokerEvent.OrderAccepted(
            clientOrderId = "c1",
            brokerOrderId = "b1",
            timestamp = 100L,
        )
        assertThat(e.clientOrderId).isEqualTo("c1")
        assertThat(e.brokerOrderId).isEqualTo("b1")
    }

    @Test
    fun `Rejected carries reason`() {
        val e = BrokerEvent.OrderRejected(
            clientOrderId = "c1",
            brokerOrderId = null,
            reason = "no price",
            timestamp = 100L,
        )
        assertThat(e.reason).isEqualTo("no price")
    }

    @Test
    fun `Filled carries price quantity and side`() {
        val e = BrokerEvent.OrderFilled(
            clientOrderId = "c1",
            brokerOrderId = "b1",
            symbol = "EURUSD",
            side = Side.BUY,
            price = Money.of("1.10"),
            quantity = Money.of("1"),
            timestamp = 100L,
        )
        assertThat(e.price).isEqualByComparingTo(Money.of("1.10"))
        assertThat(e.quantity).isEqualByComparingTo(Money.of("1"))
        assertThat(e.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `PartiallyFilled carries cumulative quantity`() {
        val e = BrokerEvent.OrderPartiallyFilled(
            clientOrderId = "c1",
            brokerOrderId = "b1",
            symbol = "EURUSD",
            side = Side.BUY,
            price = Money.of("1.10"),
            quantity = Money.of("0.3"),
            cumulativeFilled = Money.of("0.3"),
            timestamp = 100L,
        )
        assertThat(e.cumulativeFilled).isEqualByComparingTo(Money.of("0.3"))
    }

    @Test
    fun `Cancelled carries reason`() {
        val e = BrokerEvent.OrderCancelled(
            clientOrderId = "c1",
            brokerOrderId = "b1",
            reason = "user cancel",
            timestamp = 100L,
        )
        assertThat(e.reason).isEqualTo("user cancel")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.execution.events.BrokerEventTest"`
Expected: compile failure (`Unresolved reference: BrokerEvent`).

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/execution/events/BrokerEvent.kt`:

```kotlin
package com.qkt.execution.events

import com.qkt.bus.Event
import com.qkt.common.Side
import java.math.BigDecimal

sealed interface BrokerEvent : Event {
    val clientOrderId: String
    val brokerOrderId: String?
    val timestamp: Long

    data class OrderAccepted(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val timestamp: Long,
    ) : BrokerEvent

    data class OrderRejected(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val timestamp: Long,
    ) : BrokerEvent

    data class OrderFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        override val timestamp: Long,
    ) : BrokerEvent

    data class OrderPartiallyFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val cumulativeFilled: BigDecimal,
        override val timestamp: Long,
    ) : BrokerEvent

    data class OrderCancelled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val timestamp: Long,
    ) : BrokerEvent
}
```

Note: `com.qkt.bus.Event` is the marker interface from Phase 2. If it doesn't exist or has a different name, check `src/main/kotlin/com/qkt/bus/EventBus.kt` and `Event.kt` and align.

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.execution.events.BrokerEventTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/events/BrokerEvent.kt src/test/kotlin/com/qkt/execution/events/BrokerEventTest.kt
git commit -m "feat(execution): add BrokerEvent sealed type"
```

---

## Group B: broker contract refactor

### Task 5: Rewrite `Broker` interface

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/Broker.kt`

`MockBroker` (current concrete impl) will fail to compile after this change; that's expected — Task 7 rewrites it. The build will be red between Task 5 and Task 7.

- [ ] **Step 1: Replace the interface**

`src/main/kotlin/com/qkt/broker/Broker.kt` (full rewrite):

```kotlin
package com.qkt.broker

import com.qkt.execution.OrderRequest

interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>

    fun submit(request: OrderRequest): SubmitAck

    fun cancel(orderId: String)

    fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck = throw UnsupportedOperationException("$name does not support modify")
}

data class OrderModification(
    val newQuantity: java.math.BigDecimal? = null,
    val newLimitPrice: java.math.BigDecimal? = null,
    val newStopPrice: java.math.BigDecimal? = null,
)
```

- [ ] **Step 2: Confirm compile failure (expected)**

Run: `./gradlew compileKotlin`
Expected: BUILD FAILED. Errors should reference `MockBroker` (will be fixed in Task 7) and any other call site of the old `execute(...)` (TradingPipeline; will be fixed in Task 13).

This is intentional — the next tasks repair the breakage in dependency order.

- [ ] **Step 3: Stage but DO NOT commit yet**

Don't commit a non-compiling repository. We'll commit Tasks 5–7 together at the end of Task 7 once `LogBroker` and `PaperBroker` are in place.

```bash
# Verify staged state, do not commit:
git status
```

---

### Task 6: Add `LogBroker` reference impl

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/LogBroker.kt`
- Create: `src/test/kotlin/com/qkt/broker/LogBrokerTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/broker/LogBrokerTest.kt`:

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `name and capabilities`() {
        val bus = newBus()
        val b = LogBroker(bus, FixedClock(0L))
        assertThat(b.name).isEqualTo("Log")
        assertThat(b.capabilities).contains(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
        )
    }

    @Test
    fun `submit emits OrderAccepted and returns accepted=true`() {
        val bus = newBus()
        val received = mutableListOf<BrokerEvent>()
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> received.add(e) }
        val b = LogBroker(bus, FixedClock(time = 100L))

        val req = OrderRequest.Market(
            id = "c1",
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 100L,
        )
        val ack = b.submit(req)

        assertThat(ack.clientOrderId).isEqualTo("c1")
        assertThat(ack.accepted).isTrue()
        assertThat(received).hasSize(1)
        assertThat(received.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `submit never emits a fill`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = LogBroker(bus, FixedClock(0L))

        b.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty()
    }

    @Test
    fun `cancel emits OrderCancelled`() {
        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        val b = LogBroker(bus, FixedClock(time = 200L))

        b.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )
        b.cancel("c1")

        assertThat(cancels).hasSize(1)
        assertThat(cancels.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `submit Limit accepted`() {
        val bus = newBus()
        val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> accepts.add(e) }
        val b = LogBroker(bus, FixedClock(0L))

        val ack = b.submit(
            OrderRequest.Limit(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(ack.accepted).isTrue()
        assertThat(accepts).hasSize(1)
    }

    @Test
    fun `submit Stop accepted`() {
        val bus = newBus()
        val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> accepts.add(e) }
        val b = LogBroker(bus, FixedClock(0L))

        b.submit(
            OrderRequest.Stop(
                id = "c3",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(accepts).hasSize(1)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.broker.LogBrokerTest"`
Expected: still in the failed-build state from Task 5 plus `Unresolved reference: LogBroker`.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/broker/LogBroker.kt`:

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.execution.OrderRequest
import com.qkt.execution.events.BrokerEvent
import org.slf4j.LoggerFactory

class LogBroker(
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    private val log = LoggerFactory.getLogger(LogBroker::class.java)

    override val name: String = "Log"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
        )

    override fun submit(request: OrderRequest): SubmitAck {
        log.info("ORDER: {}", request)
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        log.info("CANCEL: {}", orderId)
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = orderId,
                brokerOrderId = orderId,
                reason = "user cancel",
                timestamp = clock.now(),
            ),
        )
    }
}
```

- [ ] **Step 4: Compile (still red — PaperBroker not yet refactored)**

Run: `./gradlew compileKotlin`
Expected: still red on `MockBroker` and `TradingPipeline`. Don't run tests yet.

(Tests will run after Task 7.)

---

### Task 7: Refactor `MockBroker` → `PaperBroker`

**Files:**
- Delete: `src/main/kotlin/com/qkt/broker/MockBroker.kt`
- Create: `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- Delete: `src/test/kotlin/com/qkt/broker/MockBrokerTest.kt`
- Create: `src/test/kotlin/com/qkt/broker/PaperBrokerTest.kt`

`PaperBroker` simulates fills in-process, emits events. Tier 1+2 supported natively.

Behaviors:
- `Market` fills inline at `priceProvider.lastPrice(symbol)` if available; else emits `OrderRejected("no price")`.
- `Limit` sits in a per-symbol working queue; fills when an incoming tick crosses the limit (BUY: tick ≤ limit; SELL: tick ≥ limit). Multiple limits per symbol allowed; first-submitted fills first.
- `Stop` triggers when a tick crosses `stopPrice` (BUY: tick ≥ stop; SELL: tick ≤ stop), then becomes an internal Market.
- `StopLimit` triggers same as Stop, then becomes an internal Limit at `limitPrice`.
- `IfTouched` triggers when tick crosses `triggerPrice` from the opposite side of a stop:
  - BUY IfTouched: tick ≤ triggerPrice (you want to buy on a dip)
  - SELL IfTouched: tick ≥ triggerPrice (you want to sell on a rally)
  - On trigger, becomes a Market (`onTrigger=MARKET`) or Limit at `limitPrice` (`onTrigger=LIMIT`).

PaperBroker subscribes to ticks via a callback the engine wires (rather than directly to a `MarketPriceProvider`). For backtest determinism the engine pushes ticks synchronously into PaperBroker's `onTick` before returning from each pipeline ingest.

The simplest in-engine wiring: `PaperBroker.onTick(tick)` is called by the pipeline (or the engine) after `MarketPriceTracker.update(tick)` and before strategies run. PaperBroker matches its working queue against the tick and emits any fills synchronously.

For migration parity with current MockBroker, the new PaperBroker must:
- Continue to fill `Market` orders at `priceProvider.lastPrice(symbol)`.
- Emit fill events via the bus instead of returning `Trade`.

- [ ] **Step 1: Delete the old files**

```bash
git rm src/main/kotlin/com/qkt/broker/MockBroker.kt
git rm src/test/kotlin/com/qkt/broker/MockBrokerTest.kt
```

- [ ] **Step 2: Write the failing PaperBroker tests**

`src/test/kotlin/com/qkt/broker/PaperBrokerTest.kt`:

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun tick(symbol: String, price: String, ts: Long = 1L) =
        Tick(symbol, Money.of(price), ts)

    @Test
    fun `Market fills at last tracker price`() {
        val tracker = MarketPriceTracker()
        tracker.update(tick("EURUSD", "1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        val req = OrderRequest.Market(
            id = "c1", symbol = "EURUSD", side = Side.BUY,
            quantity = Money.of("1"), timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        b.submit(req)

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.10"))
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `Market with no tracker price emits OrderRejected`() {
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { e -> rejects.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())

        b.submit(
            OrderRequest.Market(
                id = "c1", symbol = "UNKNOWN", side = Side.BUY,
                quantity = Money.of("1"), timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        assertThat(rejects).hasSize(1)
    }

    @Test
    fun `Limit fills when a tick crosses the limit price`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1", symbol = "EURUSD", side = Side.BUY,
                quantity = Money.of("1"), limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty() // not yet

        b.onTick(tick("EURUSD", "1.105"))                     // above limit, BUY does not fill
        assertThat(fills).isEmpty()

        b.onTick(tick("EURUSD", "1.099"))                     // crosses BUY limit → fill
        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.099"))
    }

    @Test
    fun `Stop converts to Market on trigger`() {
        val tracker = MarketPriceTracker()
        tracker.update(tick("EURUSD", "1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Stop(
                id = "c1", symbol = "EURUSD", side = Side.SELL,
                quantity = Money.of("1"), stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        b.onTick(tick("EURUSD", "1.085"))   // crosses SELL stop → market sell
        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `cancel removes a working Limit before fill`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1", symbol = "EURUSD", side = Side.BUY,
                quantity = Money.of("1"), limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        b.cancel("c1")

        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).isEmpty()
        assertThat(cancels).hasSize(1)
    }

    @Test
    fun `IfTouched MARKET fires when tick reaches trigger`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.IfTouched(
                id = "c1", symbol = "EURUSD", side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"), onTrigger = com.qkt.execution.TriggerType.MARKET,
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.099"))    // touches BUY IfTouched (≤ trigger)
        assertThat(fills).hasSize(1)
    }

    @Test
    fun `multiple Limits per symbol fill in submission order when a single tick crosses both`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1", symbol = "EURUSD", side = Side.BUY,
                quantity = Money.of("1"), limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        b.submit(
            OrderRequest.Limit(
                id = "c2", symbol = "EURUSD", side = Side.BUY,
                quantity = Money.of("1"), limitPrice = Money.of("1.11"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.099"))

        assertThat(fills.map { it.clientOrderId }).containsExactly("c1", "c2")
    }
}
```

- [ ] **Step 3: Confirm RED**

Run: `./gradlew test --tests "com.qkt.broker.PaperBrokerTest"`
Expected: compile failure (`Unresolved reference: PaperBroker`).

- [ ] **Step 4: Implement**

`src/main/kotlin/com/qkt/broker/PaperBroker.kt`:

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TriggerType
import com.qkt.execution.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.slf4j.LoggerFactory

class PaperBroker(
    private val bus: EventBus,
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider,
) : Broker {
    private val log = LoggerFactory.getLogger(PaperBroker::class.java)

    private val working: MutableList<WorkingOrder> = mutableListOf()

    override val name: String = "Paper"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
        )

    override fun submit(request: OrderRequest): SubmitAck {
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                timestamp = clock.now(),
            ),
        )
        when (request) {
            is OrderRequest.Market -> fillMarket(request)
            is OrderRequest.Limit, is OrderRequest.Stop,
            is OrderRequest.StopLimit, is OrderRequest.IfTouched ->
                working.add(WorkingOrder(request))
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        val removed = working.removeAll { it.req.id == orderId }
        if (removed) {
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = orderId,
                    brokerOrderId = orderId,
                    reason = "user cancel",
                    timestamp = clock.now(),
                ),
            )
        }
    }

    fun onTick(tick: Tick) {
        if (working.isEmpty()) return
        val toFill = mutableListOf<WorkingOrder>()
        for (wo in working) {
            if (wo.req.symbol != tick.symbol) continue
            val crossed = checkTrigger(wo.req, tick.price)
            if (crossed) toFill.add(wo)
        }
        for (wo in toFill) {
            working.remove(wo)
            fillFromTrigger(wo.req, tick.price)
        }
    }

    private fun fillMarket(req: OrderRequest.Market) {
        val px = priceProvider.lastPrice(req.symbol)
        if (px == null) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = req.id,
                    brokerOrderId = req.id,
                    reason = "no price",
                    timestamp = clock.now(),
                ),
            )
            return
        }
        publishFill(req.id, req.symbol, req.side, px, req.quantity)
    }

    private fun fillFromTrigger(req: OrderRequest, tickPrice: BigDecimal) {
        val (fillPrice, side, qty) = when (req) {
            is OrderRequest.Limit -> Triple(tickPrice, req.side, req.quantity)
            is OrderRequest.Stop -> Triple(tickPrice, req.side, req.quantity)
            is OrderRequest.StopLimit -> Triple(req.limitPrice, req.side, req.quantity)
            is OrderRequest.IfTouched ->
                if (req.onTrigger == TriggerType.MARKET) {
                    Triple(tickPrice, req.side, req.quantity)
                } else {
                    Triple(req.limitPrice!!, req.side, req.quantity)
                }
            is OrderRequest.Market -> error("Market should not reach fillFromTrigger")
        }
        publishFill(req.id, req.symbol, side, fillPrice, qty)
    }

    private fun publishFill(
        clientOrderId: String,
        symbol: String,
        side: Side,
        price: BigDecimal,
        qty: BigDecimal,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = clientOrderId,
                brokerOrderId = clientOrderId,
                symbol = symbol,
                side = side,
                price = price.setScale(Money.SCALE, Money.ROUNDING),
                quantity = qty,
                timestamp = clock.now(),
            ),
        )
    }

    private fun checkTrigger(
        req: OrderRequest,
        tickPrice: BigDecimal,
    ): Boolean =
        when (req) {
            is OrderRequest.Limit ->
                if (req.side == Side.BUY) tickPrice <= req.limitPrice else tickPrice >= req.limitPrice
            is OrderRequest.Stop ->
                if (req.side == Side.BUY) tickPrice >= req.stopPrice else tickPrice <= req.stopPrice
            is OrderRequest.StopLimit ->
                if (req.side == Side.BUY) tickPrice >= req.stopPrice else tickPrice <= req.stopPrice
            is OrderRequest.IfTouched ->
                if (req.side == Side.BUY) tickPrice <= req.triggerPrice else tickPrice >= req.triggerPrice
            is OrderRequest.Market -> false
        }

    private data class WorkingOrder(val req: OrderRequest)
}
```

- [ ] **Step 5: Confirm GREEN for the full Group A+B**

Run: `./gradlew test --tests "com.qkt.broker.PaperBrokerTest" --tests "com.qkt.broker.LogBrokerTest" --tests "com.qkt.execution.OrderRequestTest" --tests "com.qkt.execution.events.BrokerEventTest"`
Expected: all PaperBroker / LogBroker / OrderRequest / BrokerEvent tests PASS (~25 tests).

The wider build (`./gradlew test`) is still red because `TradingPipeline`, `OrderFactory`, etc. haven't migrated yet. That's expected.

- [ ] **Step 6: Commit Tasks 5–7 together**

```bash
git add \
  src/main/kotlin/com/qkt/broker/Broker.kt \
  src/main/kotlin/com/qkt/broker/LogBroker.kt \
  src/main/kotlin/com/qkt/broker/PaperBroker.kt \
  src/test/kotlin/com/qkt/broker/LogBrokerTest.kt \
  src/test/kotlin/com/qkt/broker/PaperBrokerTest.kt
git rm --cached src/main/kotlin/com/qkt/broker/MockBroker.kt 2>/dev/null || true
git rm --cached src/test/kotlin/com/qkt/broker/MockBrokerTest.kt 2>/dev/null || true
git commit -m "feat(broker): replace sync execute with async submit/cancel + LogBroker + PaperBroker"
```

(`git rm` ran in Step 1; this commit just records that. The `--cached` form is a safe no-op if files are already tracked-as-deleted.)

---

## Group C: position + signal + risk migration

### Task 8: Add `PositionTracker.applyFill(BrokerEvent.OrderFilled)`

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/PositionTracker.kt`

`PositionTracker` currently has `apply(trade: Trade)`. We add an event-friendly entry point that constructs a `Trade` from a `BrokerEvent.OrderFilled` and applies it. The old `apply(Trade)` stays for callers that already build `Trade`s.

- [ ] **Step 1: Read the current `PositionTracker` to understand the `apply` shape**

Run: `cat src/main/kotlin/com/qkt/positions/PositionTracker.kt`

Note the existing `apply(trade: Trade)` signature. The new method must produce identical behavior.

- [ ] **Step 2: Add the new method**

Append to `PositionTracker.kt` (next to the existing `apply(Trade)`):

```kotlin
fun applyFill(event: com.qkt.execution.events.BrokerEvent.OrderFilled) {
    val trade =
        com.qkt.execution.Trade(
            orderId = event.clientOrderId,
            symbol = event.symbol,
            price = event.price,
            quantity = event.quantity,
            side = event.side,
            timestamp = event.timestamp,
        )
    apply(trade)
}
```

(Use top-of-file imports rather than fully qualified names when the file already imports nearby types — this snippet is conservative for clarity.)

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: PositionTracker compiles. (Other files may still be red.)

- [ ] **Step 4: Stage but don't commit yet**

The commit lands in Task 13 with the pipeline rewire — the methods only become useful once the bus subscription exists.

---

### Task 9: Add `Signal.Submit(OrderRequest)` variant

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/Signal.kt`

- [ ] **Step 1: Add the variant**

`src/main/kotlin/com/qkt/strategy/Signal.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

sealed class Signal {
    data class Buy(
        val symbol: String,
        val size: BigDecimal,
    ) : Signal()

    data class Sell(
        val symbol: String,
        val size: BigDecimal,
    ) : Signal()

    data class Submit(
        val request: OrderRequest,
    ) : Signal()
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: still red on `OrderFactory` and `TradingPipeline`. That's expected.

- [ ] **Step 3: Stage; don't commit**

Commit lands with the pipeline rewire (Task 13).

---

### Task 10: Refactor `OrderFactory`: `Signal → OrderRequest`

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderFactory.kt`

Read the existing `OrderFactory.kt` first. It currently builds `Order` from a `Signal`. We replace it to build `OrderRequest` and to handle the new `Signal.Submit`.

- [ ] **Step 1: Read existing**

Run: `cat src/main/kotlin/com/qkt/execution/OrderFactory.kt`

- [ ] **Step 2: Rewrite**

`src/main/kotlin/com/qkt/execution/OrderFactory.kt`:

```kotlin
package com.qkt.execution

import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.Side
import com.qkt.strategy.Signal

class OrderFactory(
    private val clock: Clock,
    private val ids: IdGenerator,
    private val timeInForce: TimeInForce = TimeInForce.GTC,
) {
    fun fromSignal(signal: Signal): OrderRequest =
        when (signal) {
            is Signal.Buy ->
                OrderRequest.Market(
                    id = ids.next(),
                    symbol = signal.symbol,
                    side = Side.BUY,
                    quantity = signal.size,
                    timeInForce = timeInForce,
                    timestamp = clock.now(),
                )
            is Signal.Sell ->
                OrderRequest.Market(
                    id = ids.next(),
                    symbol = signal.symbol,
                    side = Side.SELL,
                    quantity = signal.size,
                    timeInForce = timeInForce,
                    timestamp = clock.now(),
                )
            is Signal.Submit -> signal.request
        }
}
```

If the existing file's API differs (e.g., free function vs class), keep the same shape; only the body changes. The intent is: Buy/Sell → `OrderRequest.Market`; Submit → pass-through.

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: still red elsewhere; `OrderFactory` compiles.

- [ ] **Step 4: Stage; don't commit**

---

### Task 11: Refactor `RiskRule` for `OrderRequest`

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/RiskRule.kt`

`RiskRule.evaluate(order: Order, ...)` becomes `evaluate(request: OrderRequest, ...)`.

- [ ] **Step 1: Read existing**

Run: `cat src/main/kotlin/com/qkt/risk/RiskRule.kt`

- [ ] **Step 2: Rewrite the interface**

`src/main/kotlin/com/qkt/risk/RiskRule.kt`:

```kotlin
package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider

interface RiskRule {
    fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision
}
```

(`Decision`, `RiskEngine` should stay the same shape; only the parameter type changes.)

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: still red on `MaxPositionSize`, `RiskEngine` (signature mismatch), `MaxOpenPositions`, downstream callers.

---

### Task 12: Refactor `MaxPositionSize` and `MaxOpenPositions` for `OrderRequest`

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt`
- Modify: `src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt`
- Modify: `src/main/kotlin/com/qkt/risk/RiskEngine.kt` (signature alignment)

- [ ] **Step 1: Rewrite `MaxPositionSize`**

`src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt`:

```kotlin
package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

class MaxPositionSize(
    private val symbol: String,
    private val maxQty: BigDecimal,
) : RiskRule {
    init {
        require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (request.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: Money.ZERO
        val projected =
            if (request.side == Side.BUY) current.add(request.quantity) else current.subtract(request.quantity)
        return if (projected.abs().compareTo(maxQty) <= 0) {
            Decision.Approve
        } else {
            Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
        }
    }
}
```

- [ ] **Step 2: Read and rewrite `MaxOpenPositions`**

Run: `cat src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt`

Apply the same pattern: wherever the parameter was `order: Order`, change to `request: OrderRequest`. The body does not need to change beyond the type swap if it only reads symbol/side/quantity (which `OrderRequest` exposes via interface members).

If `MaxOpenPositions` doesn't exist or is unaffected, skip it.

- [ ] **Step 3: Update `RiskEngine` signature**

Run: `cat src/main/kotlin/com/qkt/risk/RiskEngine.kt`

`RiskEngine.evaluate(order, positions): Decision` → `evaluate(request: OrderRequest, positions): Decision`. Same body.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: still red on `TradingPipeline`. That gets fixed in Task 13.

---

## Group D: pipeline rewire

### Task 13: Rewire `TradingPipeline` to event subscriptions

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`

The pipeline currently:
1. Calls `broker.execute(order)` synchronously.
2. Applies the returned `Trade` to `PositionTracker`, `PnL`.
3. Publishes `TradeEvent` to the bus.

After 7d-a:
1. Pipeline subscribes to `BrokerEvent.OrderFilled` / `OrderPartiallyFilled` ONCE at construction.
2. Subscriptions update positions and PnL and publish `TradeEvent`.
3. Pipeline calls `broker.submit(request)` instead of `execute`. Drops the `Trade?` return path.
4. If the broker is a `PaperBroker`, the pipeline forwards every ingested tick to `paperBroker.onTick(tick)` so the broker can match working limits/stops.

- [ ] **Step 1: Read existing**

Run: `cat src/main/kotlin/com/qkt/app/TradingPipeline.kt`

Identify:
- The constructor parameters.
- Where `broker.execute` is called.
- Where `Trade` is applied.
- Where `TradeEvent` is published.

- [ ] **Step 2: Change the `onFilled` callback signature**

Find the existing `onFilled: (Trade, Order) -> Unit` constructor parameter on `TradingPipeline`. Change to `onFilled: (Trade) -> Unit = { _ -> }`. Update every call site of the pipeline constructor:

- `LiveSession.start()` — `onFilled = { trade, _ -> trades.add(trade) }` → `onFilled = { trade -> trades.add(trade) }`.
- `Backtest` — same pattern.
- Any test that constructs a pipeline directly with an `onFilled` lambda.

Run: `grep -rEn 'onFilled' src/`. Update each match.

- [ ] **Step 3: Add the event subscriptions in `init` / construction**

Wherever the pipeline does its initialization, add:

```kotlin
init {
    bus.subscribe<BrokerEvent.OrderFilled> { e ->
        positions.applyFill(e)
        pnl.recompute()
        val trade = Trade(
            orderId = e.clientOrderId,
            symbol = e.symbol,
            price = e.price,
            quantity = e.quantity,
            side = e.side,
            timestamp = e.timestamp,
        )
        bus.publish(TradeEvent(trade))
        onFilled(trade)
    }
    bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
        val partialFill = BrokerEvent.OrderFilled(
            clientOrderId = e.clientOrderId,
            brokerOrderId = e.brokerOrderId,
            symbol = e.symbol,
            side = e.side,
            price = e.price,
            quantity = e.quantity,
            timestamp = e.timestamp,
        )
        positions.applyFill(partialFill)
        pnl.recompute()
    }
    bus.subscribe<BrokerEvent.OrderRejected> { e ->
        log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
    }
}
```

- [ ] **Step 4: Replace the `broker.execute` call site**

Wherever the pipeline does `val trade = broker.execute(order); if (trade != null) { ... }`, replace with:

```kotlin
val request = orderFactory.fromSignal(signal)
val decision = riskEngine.evaluate(request, positions)
when (decision) {
    is Decision.Approve -> broker.submit(request)
    is Decision.Reject  -> log.warn("Risk rejected: ${request.id} reason=${decision.reason}")
}
```

The `Trade` materialization moves to the `OrderFilled` subscription added in Step 3.

- [ ] **Step 5: Forward each ingested tick to PaperBroker**

In the `ingest(tick)` method, after updating the price tracker:

```kotlin
priceTracker.update(tick)
if (broker is PaperBroker) broker.onTick(tick)
strategies.forEach { ... }   // existing
```

The cast/downcall is a small wart; it's the simplest way to wire PaperBroker's tick-driven matching without adding a new abstraction in 7d-a. 7d-b will introduce the OrderManager and remove this branch.

- [ ] **Step 6: Compile + run tests**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: all production code compiles. Tests will still be red (Task 15+ migrates them).

- [ ] **Step 7: Commit Tasks 8–13 together**

```bash
git add \
  src/main/kotlin/com/qkt/positions/PositionTracker.kt \
  src/main/kotlin/com/qkt/strategy/Signal.kt \
  src/main/kotlin/com/qkt/execution/OrderFactory.kt \
  src/main/kotlin/com/qkt/risk/RiskRule.kt \
  src/main/kotlin/com/qkt/risk/RiskEngine.kt \
  src/main/kotlin/com/qkt/risk/rules/MaxPositionSize.kt \
  src/main/kotlin/com/qkt/risk/rules/MaxOpenPositions.kt \
  src/main/kotlin/com/qkt/app/TradingPipeline.kt
git commit -m "refactor(execution): rewire pipeline to event-based broker fills"
```

---

## Group E: cleanup + test migration

### Task 14: Delete obsolete `Order` and `OrderType`

**Files:**
- Delete: `src/main/kotlin/com/qkt/execution/Order.kt`
- Delete: `src/main/kotlin/com/qkt/execution/OrderType.kt`

- [ ] **Step 1: Verify no remaining references**

Run: `grep -rEn 'class Order\b|OrderType\b|com\.qkt\.execution\.Order\b' src/main/`
Expected: no output (or only matches inside `OrderRequest.kt` / similar — review each).

If references remain, fix them before deletion.

- [ ] **Step 2: Delete the files**

```bash
git rm src/main/kotlin/com/qkt/execution/Order.kt
git rm src/main/kotlin/com/qkt/execution/OrderType.kt
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(execution): remove obsolete Order and OrderType"
```

---

### Task 15: Migrate downstream tests (broker, risk)

**Files:**
- Modify: `src/test/kotlin/com/qkt/risk/rules/MaxPositionSizeTest.kt`
- Modify: `src/test/kotlin/com/qkt/risk/rules/MaxOpenPositionsTest.kt`
- Modify: `src/test/kotlin/com/qkt/risk/RiskEngineTest.kt`

These tests reference `Order(...)` and `OrderType.MARKET` etc. Migrate to `OrderRequest.Market(...)`.

- [ ] **Step 1: Open `MaxPositionSizeTest.kt` and replace usages**

For each test that constructs `Order(... type = OrderType.MARKET ...)`, replace with `OrderRequest.Market(...)` (or `Limit`, `Stop` if the test's intent involved that type). Use the same fields: `id`, `symbol`, `side`, `quantity`, `timestamp`. Add `timeInForce = TimeInForce.GTC`.

Pattern to find:
```kotlin
val order = Order(id = "1", symbol = "X", side = Side.BUY, quantity = Money.of("1"), type = OrderType.MARKET, timestamp = 0L)
```

Replacement:
```kotlin
val request = OrderRequest.Market(
    id = "1",
    symbol = "X",
    side = Side.BUY,
    quantity = Money.of("1"),
    timeInForce = TimeInForce.GTC,
    timestamp = 0L,
)
```

If the test calls `rule.evaluate(order, positions)`, change to `rule.evaluate(request, positions)` — same call, different parameter type.

- [ ] **Step 2: Same migration for `MaxOpenPositionsTest.kt`**

If it exists. If it doesn't, skip.

- [ ] **Step 3: Same migration for `RiskEngineTest.kt`**

- [ ] **Step 4: Run the migrated tests**

Run: `./gradlew test --tests "com.qkt.risk.*"`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/qkt/risk/
git commit -m "test(risk): migrate to OrderRequest"
```

---

### Task 16: Migrate remaining tests (engine + integration)

**Files:**
- Modify: `src/test/kotlin/com/qkt/app/EndToEndTest.kt`
- Modify: `src/test/kotlin/com/qkt/app/IndicatorWarmerTest.kt`
- Modify: `src/test/kotlin/com/qkt/app/TradingPipelineWarmupSplitTest.kt`
- Modify: `src/test/kotlin/com/qkt/bus/EventBusTest.kt`
- Modify: any other test that referenced `MockBroker` or `Order`

- [ ] **Step 1: Identify remaining**

Run:

```bash
grep -rEln 'MockBroker|new Order\(|OrderType\.|broker\.execute' src/test/
```

For each file:
- Replace `MockBroker(...)` → `PaperBroker(bus, clock, priceProvider)`. Note the new constructor takes the bus first.
- Replace `Order(...)` → `OrderRequest.Market(...)` (or appropriate subtype).
- Replace `broker.execute(order)` calls in tests with `broker.submit(request)` and assertions on bus events.

For tests that asserted on the synchronous `Trade?` return (e.g., `assertThat(trade).isNotNull()`), rewrite to:
```kotlin
val fills = mutableListOf<BrokerEvent.OrderFilled>()
bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
broker.submit(request)
broker.onTick(...)   // if needed
assertThat(fills).hasSize(1)
assertThat(fills.single().price).isEqualByComparingTo(expectedPrice)
```

- [ ] **Step 2: For each migrated file, run its tests**

Run: `./gradlew test --tests "com.qkt.app.EndToEndTest"` etc. Iterate until each is green.

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`
Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/
git commit -m "test: migrate engine and integration tests to event-based broker"
```

---

### Task 17: Update entry points (`Main`, `LiveDemo`, `LiveSession`, `Backtest`, `MaxAudit`)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Main.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveDemo.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`
- Modify: `src/main/kotlin/com/qkt/app/MaxAudit.kt` (only if it constructs a broker directly)

- [ ] **Step 1: `MockBroker(...)` → `PaperBroker(bus, clock, priceTracker)`**

Each entry point that constructs `MockBroker(...)` now constructs `PaperBroker(bus, clock, priceTracker)`. Adjust constructor call to pass `bus` (`EventBus`) first.

If a file uses `MockBroker` indirectly via `LiveSession.start()` (which internally builds the broker), no change is needed there — `LiveSession.start()` itself updates internally.

`LiveSession.kt` currently builds `MockBroker(clock, priceTracker)`. Change to `PaperBroker(bus, clock, priceTracker)`. The `bus` is already in scope.

- [ ] **Step 2: Backtest pipeline tick forwarding**

If `Backtest` builds its own pipeline and ingests ticks, ensure the pipeline forwards ticks to the PaperBroker's `onTick` (this happens automatically if `Backtest` uses `TradingPipeline.ingest(tick)` from Task 13).

- [ ] **Step 3: Run Main**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: same count as before the refactor (10, per Phase 7 acceptance). Backtest determinism preserved.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/
git commit -m "refactor(app): wire entry points to PaperBroker and event-based fills"
```

---

## Group F: verification

### Task 18: Full build + test

- [ ] **Step 1: Format**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. ~422 tests pass (excluding `@Tag("e2e")`).

- [ ] **Step 3: Verify no leftover references**

Run:

```bash
grep -rEln 'MockBroker|new Order\(|OrderType\.|broker\.execute|class Order\b' src/
```

Expected: no output.

- [ ] **Step 4: Commit any format-driven fixups**

```bash
git status
# if any reformatted files:
git add -p
git commit -m "style: ktlintFormat after broker refactor"
```

---

### Task 19: Verify `MaxAudit` (network optional)

If you have network access, run the end-to-end audit to make sure the broker refactor didn't regress live behavior.

- [ ] **Step 1: Run audit**

Run: `timeout 240 ./gradlew runMaxAudit --console=plain 2>&1 | tee /tmp/maxaudit-7da.log | tail -60`

Expected:
- Phase A (BARS): 5+ symbols return bars (varies by market hours).
- Phase B (LIVE TICKS): all 7 symbols deliver ticks.
- Phase C (strategy): cross-asset events fire; no crashes.

If no network, skip. The structural correctness is verified by tests.

- [ ] **Step 2: No commit**

This is verification only.

---

### Task 20: Final verification + branch cleanup

- [ ] **Step 1: Branch state**

Run: `git log --oneline main..HEAD`
Expected: ~10–14 commits with clean conventional messages.

- [ ] **Step 2: Read every commit message**

Run: `git log --pretty=format:'%h %s' main..HEAD`

Each must:
- Start with `feat(...)`, `fix(...)`, `refactor(...)`, `test(...)`, `style(...)`, `docs(...)`, or `chore(...)`.
- Be ≤70 chars.
- Use lowercase first word and no trailing period.
- Have no AI footers.

If any commit needs touching up, use `git rebase -i main` to reword (no need to amend pre-existing commits). Skip if all clean.

- [ ] **Step 3: Pre-push checklist**

Run:
```bash
./gradlew build
git status
grep -rEn 'TODO|FIXME|XXX' src/ | grep -v "// \?"
```

- `./gradlew build` ends BUILD SUCCESSFUL.
- `git status` is clean (or only `tt.txt` untracked).
- No new TODO/FIXME without an issue link.

- [ ] **Step 4: Plan handoff**

Phase 7d-a is shippable. Decide with the user whether to:

a. Merge `phase7d-broker-and-order-management` to main now and start a new branch for 7d-b.
b. Continue on the same branch and add 7d-b commits before merging.

Either is consistent with qkt SKILL.md §2; (a) gives a smaller blast radius per merge.

---

## Spec ambiguities encountered

These are decisions the plan made which the spec left open. They go into the spec as resolved or surface in 7d-b.

1. **PaperBroker tick forwarding.** Spec says "the engine pushes ticks". 7d-a adds a `paperBroker.onTick(tick)` call inside `TradingPipeline.ingest()` guarded by `broker is PaperBroker`. The cast is a wart; 7d-b's `OrderManager` removes it (the OrderManager will own all tick-driven trigger evaluation, including for any broker that needs it).

2. **`onFilled` callback signature in pipeline.** Existing signature `(Trade, Order) -> Unit` becomes `(Trade) -> Unit` because we no longer carry the input `Order` through. Callers (`LiveSession.start()`) updated accordingly.

3. **`Trade` lifecycle.** `Trade` stays as a derived record — constructed inside the `OrderFilled` subscription from the event fields. It is no longer a return value of any broker method. `recentTrades()` in `LiveSessionHandle` keeps working because the pipeline still publishes `TradeEvent` and the handle observes it.

4. **`SubmitAck.brokerOrderId` for paper/log brokers.** Both echo `request.id`. Real brokers in 7e+ will assign their own. The engine's mapping table doesn't need to be different for the two cases.

5. **`Side` for `OrderRequest.Market`.** Comes from `Signal.Buy` / `Signal.Sell`. `Signal.Submit(req)` carries `side` directly on the request. No new sugar needed.

6. **`MaxOpenPositions`** existence — if it doesn't reference `Order` at all, no migration needed. Plan checks at Step 2 of Task 12.

7. **Determinism check.** Backtest determinism is verified by Task 18 running existing Phase 4/7b backtest tests, which already assert two-run identity. No new assertions needed.
