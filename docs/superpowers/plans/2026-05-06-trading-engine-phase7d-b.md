# Phase 7d-b Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the engine-side `OrderManager` that owns order lifecycle, dispatches to the broker by capability, synthesizes engine-side triggers for any Tier 2 type the active broker doesn't natively support, and choreographs the Tier 3 composite types — `TrailingStop`, `TrailingStopLimit`, `StandaloneOCO`, `OTO`, `Bracket`, `ScaleOut`, `TimeExit`. After this lands, strategies can compose arbitrary order trees and run identically across paper, log, and (future) real brokers.

**Architecture:** New `OrderManager` is constructed by `TradingPipeline`. The pipeline's `OrderEvent` subscription routes through `orderManager.submit(req)` instead of `broker.submit(req)`. The OrderManager dispatches per the three-tier model in the spec: Tier 1 always to broker; Tier 2 to broker if `OrderTypeCapability` claims it, else engine-side fallback (pending order list + tick-driven triggers); Tier 3 always engine-side. Composites are walked recursively — children may themselves be composites. PaperBroker subscribes to `TickEvent` directly so the pipeline no longer needs the `if (broker is PaperBroker)` cast wart.

**Tech Stack:** Kotlin (sealed types, data classes), JUnit 5, AssertJ. Builds on the Phase 7d-a broker contract (`Broker.submit/cancel`, `BrokerEvent`, `OrderRequest`).

---

## File structure overview

### New files

```
src/main/kotlin/com/qkt/execution/
  OrderState.kt           # enum: state machine values
  ManagedOrder.kt         # data class — per-order tracked state
  TrailMode.kt            # enum: ABSOLUTE | PERCENT
  ExpiryAction.kt         # enum: CANCEL | CLOSE_AT_MARKET
  ScaleOutLeg.kt          # data class — (priceTarget, fraction)

src/main/kotlin/com/qkt/app/
  OrderManager.kt         # owns lifecycle, dispatch, triggers, composite choreography

src/test/kotlin/com/qkt/broker/
  FakeBroker.kt           # test-only Broker with configurable capabilities and recorded calls

src/test/kotlin/com/qkt/app/
  OrderManagerTest.kt              # core dispatch + state transitions
  OrderManagerTier2FallbackTest.kt # Stop/StopLimit/IfTouched engine fallback
  OrderManagerTrailingTest.kt      # TrailingStop / TrailingStopLimit
  OrderManagerOcoTest.kt           # StandaloneOCO sibling cancel
  OrderManagerOtoTest.kt           # OTO parent → children activation
  OrderManagerBracketTest.kt       # Bracket native + fallback
  OrderManagerScaleOutTest.kt      # ScaleOut multi-leg
  OrderManagerTimeExitTest.kt      # TimeExit deadline
```

### Modified files

```
src/main/kotlin/com/qkt/execution/OrderRequest.kt   # extend sealed hierarchy with Tier 3 types
src/main/kotlin/com/qkt/broker/PaperBroker.kt       # subscribe to TickEvent directly (drop pipeline-side onTick call)
src/main/kotlin/com/qkt/app/TradingPipeline.kt      # route OrderEvent → orderManager.submit; drop PaperBroker cast
src/main/kotlin/com/qkt/app/LiveSession.kt          # construct OrderManager
src/main/kotlin/com/qkt/app/Backtest.kt             # construct OrderManager

src/test/kotlin/com/qkt/broker/PaperBrokerTest.kt   # adapt to bus-driven onTick (remove direct b.onTick(...) calls; use bus.publish(TickEvent(t)))
docs/phases/phase-7d-broker-and-orders.md           # NEW phase changelog (final task)
```

### Deleted files

None — 7d-b is purely additive on top of 7d-a.

---

## Task summary

| # | Group | Title |
|---|---|---|
| 1 | A | Add `TrailMode`, `ExpiryAction`, `ScaleOutLeg` |
| 2 | A | Extend `OrderRequest` with `TrailingStop`, `TrailingStopLimit` |
| 3 | A | Extend `OrderRequest` with `StandaloneOCO`, `OTO` |
| 4 | A | Extend `OrderRequest` with `Bracket`, `ScaleOut`, `TimeExit` |
| 5 | B | Add `OrderState` enum |
| 6 | B | Add `ManagedOrder` data class |
| 7 | C | `OrderManager` skeleton + Tier 1 dispatch + state tracking |
| 8 | C | `OrderManager.cancel()` for Tier 1 |
| 9 | C | Tier 2 capability-based dispatch (broker handoff or fallback flag) |
| 10 | D | `FakeBroker` test fixture |
| 11 | E | Tier 2 engine-side fallback: pending list + tick-driven Stop / StopLimit / IfTouched |
| 12 | F | `TrailingStop` synthesis (HWM ratchet + cross trigger) |
| 13 | F | `TrailingStopLimit` synthesis |
| 14 | G | `StandaloneOCO` submission + sibling cancel-on-fill |
| 15 | G | `OTO` submission + parent-fill → children activation |
| 16 | G | `Bracket` (native handoff if `BRACKET` cap; else OTO+OCO decomposition) |
| 17 | G | `ScaleOut` (basis fill + fractional exits at price targets) |
| 18 | G | `TimeExit` (deadline + wrap target) |
| 19 | H | `PaperBroker` subscribes to `TickEvent` directly (drop the cast wart) |
| 20 | H | `TradingPipeline` routes through `OrderManager` |
| 21 | H | `LiveSession`/`Backtest` construct `OrderManager` |
| 22 | H | Migrate broken existing tests |
| 23 | I | Full build + tests |
| 24 | I | Optional `MaxAudit` smoke check |
| 25 | I | Phase 7d changelog at `docs/phases/phase-7d-broker-and-orders.md` |
| 26 | I | Final verification |

Cumulative test counts (rough):

| After task | Δ tests | Cumulative |
|---|---|---|
| Pre-7d-b baseline (from 7d-a) | — | 416 |
| 4 (Tier 3 types)              | +6 | 422 |
| 7 (OrderManager core)         | +6 | 428 |
| 11 (Tier 2 fallback)          | +5 | 433 |
| 13 (Trailing)                 | +6 | 439 |
| 14 (OCO)                      | +4 | 443 |
| 15 (OTO)                      | +4 | 447 |
| 16 (Bracket)                  | +4 | 451 |
| 17 (ScaleOut)                 | +4 | 455 |
| 18 (TimeExit)                 | +3 | 458 |

Final target: **~458 tests**. Rough; ±10 expected.

---

## Group A: OrderRequest Tier 3 extensions

### Task 1: Add `TrailMode`, `ExpiryAction`, `ScaleOutLeg`

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/TrailMode.kt`
- Create: `src/main/kotlin/com/qkt/execution/ExpiryAction.kt`
- Create: `src/main/kotlin/com/qkt/execution/ScaleOutLeg.kt`

- [ ] **Step 1: Create `TrailMode`**

`src/main/kotlin/com/qkt/execution/TrailMode.kt`:

```kotlin
package com.qkt.execution

enum class TrailMode {
    ABSOLUTE,
    PERCENT,
}
```

- [ ] **Step 2: Create `ExpiryAction`**

`src/main/kotlin/com/qkt/execution/ExpiryAction.kt`:

```kotlin
package com.qkt.execution

enum class ExpiryAction {
    CANCEL,
    CLOSE_AT_MARKET,
}
```

- [ ] **Step 3: Create `ScaleOutLeg`**

`src/main/kotlin/com/qkt/execution/ScaleOutLeg.kt`:

```kotlin
package com.qkt.execution

import java.math.BigDecimal

data class ScaleOutLeg(
    val priceTarget: BigDecimal,
    val fraction: BigDecimal,
) {
    init {
        require(priceTarget.signum() > 0) { "priceTarget must be > 0: $priceTarget" }
        require(fraction.signum() > 0 && fraction <= BigDecimal.ONE) {
            "fraction must be in (0, 1]: $fraction"
        }
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/TrailMode.kt src/main/kotlin/com/qkt/execution/ExpiryAction.kt src/main/kotlin/com/qkt/execution/ScaleOutLeg.kt
git commit -m "feat(execution): add TrailMode ExpiryAction ScaleOutLeg helpers"
```

---

### Task 2: Extend `OrderRequest` with `TrailingStop` and `TrailingStopLimit`

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Modify: `src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`

- [ ] **Step 1: Add the variants to `OrderRequest`**

Append inside the `sealed interface OrderRequest { ... }` body, after `IfTouched`:

```kotlin
    data class TrailingStop(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(trailAmount.signum() > 0) { "trailAmount must be > 0: $trailAmount" }
            if (trailMode == TrailMode.PERCENT) {
                require(trailAmount <= BigDecimal("100")) {
                    "PERCENT trailAmount must be <= 100: $trailAmount"
                }
            }
        }
    }

    data class TrailingStopLimit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        val limitOffset: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(trailAmount.signum() > 0) { "trailAmount must be > 0: $trailAmount" }
            require(limitOffset.signum() >= 0) { "limitOffset must be >= 0: $limitOffset" }
        }
    }
```

- [ ] **Step 2: Add tests**

Append to `src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`:

```kotlin
    @Test
    fun `TrailingStop ABSOLUTE constructs`() {
        val ts = OrderRequest.TrailingStop(
            id = "t1",
            symbol = "EURUSD",
            side = Side.SELL,
            quantity = Money.of("1"),
            trailAmount = Money.of("0.005"),
            trailMode = TrailMode.ABSOLUTE,
            timeInForce = TimeInForce.GTC,
            timestamp = 100L,
        )
        assertThat(ts.trailAmount).isEqualByComparingTo(Money.of("0.005"))
        assertThat(ts.trailMode).isEqualTo(TrailMode.ABSOLUTE)
    }

    @Test
    fun `TrailingStop PERCENT rejects values over 100`() {
        assertThatThrownBy {
            OrderRequest.TrailingStop(
                id = "t2",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("150"),
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("100")
    }

    @Test
    fun `TrailingStopLimit constructs with offset`() {
        val tsl = OrderRequest.TrailingStopLimit(
            id = "t3",
            symbol = "EURUSD",
            side = Side.SELL,
            quantity = Money.of("1"),
            trailAmount = Money.of("0.005"),
            trailMode = TrailMode.ABSOLUTE,
            limitOffset = Money.of("0.001"),
            timeInForce = TimeInForce.GTC,
            timestamp = 100L,
        )
        assertThat(tsl.limitOffset).isEqualByComparingTo(Money.of("0.001"))
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.execution.OrderRequestTest"`
Expected: 10 tests PASS (7 from 7d-a + 3 new).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt src/test/kotlin/com/qkt/execution/OrderRequestTest.kt
git commit -m "feat(execution): add TrailingStop and TrailingStopLimit"
```

---

### Task 3: Extend `OrderRequest` with `StandaloneOCO` and `OTO`

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Modify: `src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`

- [ ] **Step 1: Add variants**

Append to `OrderRequest` (after `TrailingStopLimit`):

```kotlin
    data class StandaloneOCO(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val leg1: OrderRequest,
        val leg2: OrderRequest,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }

    data class OTO(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val parent: OrderRequest,
        val children: List<OrderRequest>,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(children.isNotEmpty()) { "OTO must have at least one child" }
        }
    }
```

Note: `quantity` and `side` on the outer composite are descriptive (mirror the parent leg's quantity/side for risk-engine inspection). Inner legs carry their own.

- [ ] **Step 2: Add tests**

Append to `OrderRequestTest`:

```kotlin
    @Test
    fun `StandaloneOCO carries two legs`() {
        val l1 = OrderRequest.Limit(
            id = "l1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("1.10"), timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        val l2 = OrderRequest.Limit(
            id = "l2", symbol = "EURUSD", side = Side.SELL, quantity = Money.of("1"),
            limitPrice = Money.of("1.20"), timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        val oco = OrderRequest.StandaloneOCO(
            id = "oco1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
            leg1 = l1, leg2 = l2, timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        assertThat(oco.leg1).isSameAs(l1)
        assertThat(oco.leg2).isSameAs(l2)
    }

    @Test
    fun `OTO requires at least one child`() {
        val parent = OrderRequest.Market(
            id = "m1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        assertThatThrownBy {
            OrderRequest.OTO(
                id = "oto1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                parent = parent, children = emptyList(),
                timeInForce = TimeInForce.GTC, timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one child")
    }
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.execution.OrderRequestTest"
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt src/test/kotlin/com/qkt/execution/OrderRequestTest.kt
git commit -m "feat(execution): add StandaloneOCO and OTO composites"
```

---

### Task 4: Extend `OrderRequest` with `Bracket`, `ScaleOut`, `TimeExit`

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Modify: `src/test/kotlin/com/qkt/execution/OrderRequestTest.kt`

- [ ] **Step 1: Add variants**

```kotlin
    data class Bracket(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val entry: OrderRequest,
        val takeProfit: BigDecimal,
        val stopLoss: BigDecimal,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(takeProfit.signum() > 0) { "takeProfit must be > 0: $takeProfit" }
            require(stopLoss.signum() > 0) { "stopLoss must be > 0: $stopLoss" }
            require(takeProfit != stopLoss) {
                "takeProfit and stopLoss must differ: tp=$takeProfit sl=$stopLoss"
            }
        }
    }

    data class ScaleOut(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val basis: OrderRequest,
        val legs: List<ScaleOutLeg>,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
            require(legs.isNotEmpty()) { "ScaleOut requires at least one leg" }
            val totalFraction = legs.fold(BigDecimal.ZERO) { acc, l -> acc + l.fraction }
            require(totalFraction <= BigDecimal.ONE) {
                "ScaleOut total fraction exceeds 1.0: $totalFraction"
            }
        }
    }

    data class TimeExit(
        override val id: String,
        override val symbol: String,
        override val side: Side,
        override val quantity: BigDecimal,
        val target: OrderRequest,
        val deadline: java.time.Instant,
        val onExpiry: ExpiryAction,
        override val timeInForce: TimeInForce,
        override val timestamp: Long,
    ) : OrderRequest {
        init {
            require(quantity.signum() > 0) { "quantity must be > 0: $quantity" }
        }
    }
```

- [ ] **Step 2: Add tests**

```kotlin
    @Test
    fun `Bracket rejects equal tp and sl`() {
        val entry = OrderRequest.Limit(
            id = "e1", symbol = "XAUUSD", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("4500"), timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        assertThatThrownBy {
            OrderRequest.Bracket(
                id = "b1", symbol = "XAUUSD", side = Side.BUY, quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("4500"),
                stopLoss = Money.of("4500"),
                timeInForce = TimeInForce.GTC, timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ScaleOut total fraction must not exceed 1`() {
        val entry = OrderRequest.Market(
            id = "m1", symbol = "BTCUSDT", side = Side.BUY, quantity = Money.of("3"),
            timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        assertThatThrownBy {
            OrderRequest.ScaleOut(
                id = "s1", symbol = "BTCUSDT", side = Side.BUY, quantity = Money.of("3"),
                basis = entry,
                legs = listOf(
                    ScaleOutLeg(Money.of("90000"), Money.of("0.7")),
                    ScaleOutLeg(Money.of("100000"), Money.of("0.7")),  // sums to 1.4
                ),
                timeInForce = TimeInForce.GTC, timestamp = 100L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fraction")
    }

    @Test
    fun `TimeExit constructs with deadline`() {
        val entry = OrderRequest.Limit(
            id = "e1", symbol = "BTCUSDT", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("80000"), timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        val te = OrderRequest.TimeExit(
            id = "te1", symbol = "BTCUSDT", side = Side.BUY, quantity = Money.of("1"),
            target = entry,
            deadline = java.time.Instant.parse("2030-01-01T00:00:00Z"),
            onExpiry = ExpiryAction.CANCEL,
            timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        assertThat(te.onExpiry).isEqualTo(ExpiryAction.CANCEL)
    }
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.execution.OrderRequestTest"
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt src/test/kotlin/com/qkt/execution/OrderRequestTest.kt
git commit -m "feat(execution): add Bracket ScaleOut and TimeExit composites"
```

---

## Group B: OrderManager state foundation

### Task 5: Add `OrderState` enum

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/OrderState.kt`

- [ ] **Step 1: Create**

```kotlin
package com.qkt.execution

enum class OrderState {
    CREATED,
    PENDING,
    SUBMITTED,
    WORKING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}

val OrderState.isTerminal: Boolean
    get() = this == OrderState.FILLED || this == OrderState.CANCELLED || this == OrderState.REJECTED
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/com/qkt/execution/OrderState.kt
git commit -m "feat(execution): add OrderState enum"
```

---

### Task 6: Add `ManagedOrder` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/execution/ManagedOrder.kt`

- [ ] **Step 1: Create**

```kotlin
package com.qkt.execution

import java.math.BigDecimal

data class ManagedOrder(
    val id: String,
    val request: OrderRequest,
    val state: OrderState,
    val brokerOrderId: String? = null,
    val cumulativeFilledQuantity: BigDecimal = BigDecimal.ZERO,
    val avgFillPrice: BigDecimal? = null,
    val parentClientOrderId: String? = null,
    val childClientOrderIds: List<String> = emptyList(),
    val groupId: String? = null,
    val createdAt: Long,
    val lastUpdatedAt: Long,
)
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew compileKotlin
git add src/main/kotlin/com/qkt/execution/ManagedOrder.kt
git commit -m "feat(execution): add ManagedOrder lifecycle record"
```

---

## Group C: OrderManager core

### Task 7: `OrderManager` skeleton + Tier 1 dispatch + state tracking

**Files:**
- Create: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerTest.kt`

This task ships the skeleton: constructor, `submit` for Tier 1 (Market/Limit) only, BrokerEvent subscriptions that update state. Cancel and other tiers come in subsequent tasks.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/app/OrderManagerTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.LogBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `submit Market goes to broker and tracks state through accept`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val tracker = MarketPriceTracker()
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, tracker, clock)

        val req = OrderRequest.Market(
            id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC, timestamp = 100L,
        )
        val ack = om.submit(req)

        assertThat(ack.accepted).isTrue()
        // LogBroker emits OrderAccepted synchronously; state is now WORKING
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.WORKING)
        assertThat(managed.brokerOrderId).isEqualTo("c1")
    }

    @Test
    fun `submit Limit goes to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c2", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                limitPrice = Money.of("1.10"), timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c2")?.state).isEqualTo(OrderState.WORKING)
    }

    @Test
    fun `OrderFilled event transitions state to FILLED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        // LogBroker doesn't fill; manually publish a fill event
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1", brokerOrderId = "c1",
                symbol = "EURUSD", side = Side.BUY,
                price = Money.of("1.10"), quantity = Money.of("1"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("1"))
        assertThat(managed.avgFillPrice).isEqualByComparingTo(Money.of("1.10"))
    }

    @Test
    fun `OrderRejected event transitions state to REJECTED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = "c1", brokerOrderId = "c1", reason = "no price",
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.REJECTED)
    }

    @Test
    fun `OrderPartiallyFilled accumulates cumulative fill quantity`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("3"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1", brokerOrderId = "c1",
                symbol = "EURUSD", side = Side.BUY,
                price = Money.of("1.10"), quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1", brokerOrderId = "c1",
                symbol = "EURUSD", side = Side.BUY,
                price = Money.of("1.10"), quantity = Money.of("2"),
                cumulativeFilled = Money.of("3"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("3"))
    }

    @Test
    fun `activeOrders excludes terminal states`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        om.submit(
            OrderRequest.Market(
                id = "c2", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1", brokerOrderId = "c1",
                symbol = "EURUSD", side = Side.BUY,
                price = Money.of("1.10"), quantity = Money.of("1"),
            ),
        )

        assertThat(om.activeOrders().map { it.id }).containsExactly("c2")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTest"`
Expected: `Unresolved reference 'OrderManager'`.

- [ ] **Step 3: Implement skeleton**

`src/main/kotlin/com/qkt/app/OrderManager.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.slf4j.LoggerFactory

class OrderManager(
    private val broker: Broker,
    private val bus: EventBus,
    private val priceProvider: MarketPriceProvider,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OrderManager::class.java)

    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()

    init {
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> onAccepted(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> onRejected(e) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> onFilled(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> onPartiallyFilled(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> onCancelled(e) }
    }

    fun submit(request: OrderRequest): SubmitAck {
        val now = clock.now()
        track(
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.CREATED,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        return when (request) {
            is OrderRequest.Market, is OrderRequest.Limit -> submitToBroker(request)
            else -> error("Order type ${request::class.simpleName} not yet supported (added in subsequent tasks)")
        }
    }

    fun getOrder(clientOrderId: String): ManagedOrder? = orders[clientOrderId]

    fun activeOrders(): List<ManagedOrder> = orders.values.filter { !it.state.isTerminal }

    fun pendingOrders(): List<ManagedOrder> = orders.values.filter { it.state == OrderState.PENDING }

    private fun submitToBroker(request: OrderRequest): SubmitAck {
        update(request.id) { it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now()) }
        return broker.submit(request)
    }

    private fun track(managed: ManagedOrder) {
        orders[managed.id] = managed
    }

    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) {
        orders[id]?.let { orders[id] = change(it) }
    }

    private fun onAccepted(e: BrokerEvent.OrderAccepted) {
        update(e.clientOrderId) {
            it.copy(
                state = OrderState.WORKING,
                brokerOrderId = e.brokerOrderId ?: it.brokerOrderId,
                lastUpdatedAt = clock.now(),
            )
        }
    }

    private fun onRejected(e: BrokerEvent.OrderRejected) {
        update(e.clientOrderId) {
            it.copy(state = OrderState.REJECTED, lastUpdatedAt = clock.now())
        }
    }

    private fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled) {
        update(e.clientOrderId) {
            it.copy(
                state = OrderState.PARTIALLY_FILLED,
                cumulativeFilledQuantity = e.cumulativeFilled,
                avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                lastUpdatedAt = clock.now(),
            )
        }
    }

    private fun onFilled(e: BrokerEvent.OrderFilled) {
        update(e.clientOrderId) {
            val newCumulative = it.cumulativeFilledQuantity + e.quantity
            it.copy(
                state = OrderState.FILLED,
                cumulativeFilledQuantity = newCumulative,
                avgFillPrice = blendAvg(it.avgFillPrice, it.cumulativeFilledQuantity, e.price, e.quantity),
                lastUpdatedAt = clock.now(),
            )
        }
    }

    private fun onCancelled(e: BrokerEvent.OrderCancelled) {
        update(e.clientOrderId) {
            it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
        }
    }

    private fun blendAvg(
        oldAvg: BigDecimal?,
        oldQty: BigDecimal,
        newPrice: BigDecimal,
        newQty: BigDecimal,
    ): BigDecimal {
        if (oldAvg == null || oldQty.signum() == 0) return newPrice
        val totalQty = oldQty + newQty
        return (oldAvg * oldQty + newPrice * newQty)
            .divide(totalQty, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerTest.kt
git commit -m "feat(execution): add OrderManager skeleton with Tier 1 dispatch"
```

---

### Task 8: `OrderManager.cancel()` for Tier 1

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerTest.kt`

- [ ] **Step 1: Add a cancel test**

Append to `OrderManagerTest`:

```kotlin
    @Test
    fun `cancel routes to broker for working order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        // LogBroker emitted Accepted synchronously, so c1 is WORKING.
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.WORKING)

        om.cancel("c1")
        // LogBroker.cancel emits OrderCancelled synchronously
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `cancel of unknown order is a no-op`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        // Should not throw; nothing to assert beyond that
        om.cancel("does-not-exist")
        assertThat(om.getOrder("does-not-exist")).isNull()
    }
```

- [ ] **Step 2: Implement cancel**

Add to `OrderManager`:

```kotlin
    fun cancel(clientOrderId: String) {
        val managed = orders[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        when (managed.state) {
            OrderState.PENDING -> {
                // engine-side pending — just mark cancelled, no broker call
                update(clientOrderId) {
                    it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now())
                }
            }
            else -> broker.cancel(clientOrderId)
        }
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTest"`
Expected: 8 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerTest.kt
git commit -m "feat(execution): implement OrderManager.cancel"
```

---

### Task 9: Tier 2 capability-based dispatch

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`

For Tier 2 types (Stop, StopLimit, IfTouched), check `broker.capabilities`. If the broker advertises native handling, hand off. Else, mark as PENDING (engine fallback registered in Task 11).

- [ ] **Step 1: Extend `submit` dispatch**

Replace the `submit` body in `OrderManager.kt`:

```kotlin
    fun submit(request: OrderRequest): SubmitAck {
        val now = clock.now()
        track(
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.CREATED,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        return dispatch(request)
    }

    private fun dispatch(request: OrderRequest): SubmitAck =
        when (request) {
            is OrderRequest.Market, is OrderRequest.Limit -> submitToBroker(request)

            is OrderRequest.Stop ->
                if (OrderTypeCapability.STOP in broker.capabilities) submitToBroker(request)
                else holdPending(request)

            is OrderRequest.StopLimit ->
                if (OrderTypeCapability.STOP_LIMIT in broker.capabilities) submitToBroker(request)
                else holdPending(request)

            is OrderRequest.IfTouched ->
                if (OrderTypeCapability.IF_TOUCHED in broker.capabilities) submitToBroker(request)
                else holdPending(request)

            else -> error("Order type ${request::class.simpleName} dispatch not yet implemented")
        }

    private fun holdPending(request: OrderRequest): SubmitAck {
        update(request.id) {
            it.copy(state = OrderState.PENDING, lastUpdatedAt = clock.now())
        }
        // Synthesize OrderAccepted so consumers see consistent lifecycle
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
```

Note: `holdPending` publishes an `OrderAccepted` so the OrderManager state goes CREATED → PENDING via `holdPending` and then is observable as ACCEPTED via the bus subscription. To avoid double-stamping, after `holdPending` the existing `onAccepted` handler runs and would transition PENDING → WORKING — which is wrong for a pending fallback order.

Refine: gate `onAccepted` so it does not transition if state == PENDING. Update:

```kotlin
    private fun onAccepted(e: BrokerEvent.OrderAccepted) {
        update(e.clientOrderId) {
            if (it.state == OrderState.PENDING) {
                // engine-pending order — leave state, just record brokerOrderId
                it.copy(brokerOrderId = e.brokerOrderId ?: it.brokerOrderId, lastUpdatedAt = clock.now())
            } else {
                it.copy(
                    state = OrderState.WORKING,
                    brokerOrderId = e.brokerOrderId ?: it.brokerOrderId,
                    lastUpdatedAt = clock.now(),
                )
            }
        }
    }
```

- [ ] **Step 2: Add import**

Add to OrderManager imports:

```kotlin
import com.qkt.broker.OrderTypeCapability
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: No commit yet — Task 10 (FakeBroker) and Task 11 (engine fallback) commit together with this**

---

## Group D: Test fixture

### Task 10: `FakeBroker` test fixture

**Files:**
- Create: `src/test/kotlin/com/qkt/broker/FakeBroker.kt`

A test broker with configurable capabilities and recorded calls. Used to drive OrderManager fallback paths.

- [ ] **Step 1: Create**

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest

class FakeBroker(
    private val bus: EventBus,
    private val clock: Clock,
    override val capabilities: Set<OrderTypeCapability>,
) : Broker {
    override val name: String = "Fake"

    val submits: MutableList<OrderRequest> = mutableListOf()
    val cancels: MutableList<String> = mutableListOf()

    var emitAcceptOnSubmit: Boolean = true

    override fun submit(request: OrderRequest): SubmitAck {
        submits.add(request)
        if (emitAcceptOnSubmit) {
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = request.id,
                    brokerOrderId = request.id,
                    timestamp = clock.now(),
                ),
            )
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = request.id,
            accepted = true,
        )
    }

    override fun cancel(orderId: String) {
        cancels.add(orderId)
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = orderId,
                brokerOrderId = orderId,
                reason = "user cancel",
                timestamp = clock.now(),
            ),
        )
    }

    fun emitFill(
        request: OrderRequest,
        price: java.math.BigDecimal,
        quantity: java.math.BigDecimal = request.quantity,
    ) {
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = request.id,
                symbol = request.symbol,
                side = request.side,
                price = price,
                quantity = quantity,
                timestamp = clock.now(),
            ),
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

---

## Group E: Engine synthesis (Tier 2 fallback)

### Task 11: Tier 2 engine-side fallback (Stop/StopLimit/IfTouched)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerTier2FallbackTest.kt`

When `broker.capabilities` does NOT include the Tier 2 type, OrderManager holds the order as PENDING and watches `TickEvent`s. When a tick crosses the trigger, OrderManager submits an internal Tier 1 order (Market or Limit) to the broker on behalf of the original.

- [ ] **Step 1: Write tests**

`src/test/kotlin/com/qkt/app/OrderManagerTier2FallbackTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTier2FallbackTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private val tier1Only = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT)

    @Test
    fun `Stop without broker capability is held pending`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Stop(
                id = "c1", symbol = "EURUSD", side = Side.SELL, quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.PENDING)
        assertThat(broker.submits).isEmpty()
    }

    @Test
    fun `Stop SELL fires Market when tick crosses stopPrice`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Stop(
                id = "c1", symbol = "EURUSD", side = Side.SELL, quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(broker.submits.single().symbol).isEqualTo("EURUSD")
        assertThat(broker.submits.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `StopLimit fires Limit at limitPrice when tick crosses stopPrice`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StopLimit(
                id = "c1", symbol = "EURUSD", side = Side.SELL, quantity = Money.of("1"),
                stopPrice = Money.of("1.09"), limitPrice = Money.of("1.085"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))

        assertThat(broker.submits).hasSize(1)
        val submitted = broker.submits.single()
        assertThat(submitted).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat((submitted as OrderRequest.Limit).limitPrice).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `IfTouched MARKET fires Market when tick reaches trigger`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.IfTouched(
                id = "c1", symbol = "EURUSD", side = Side.BUY, quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"), onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.099"), 1L)))

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `pending Stop is removed once triggered (does not double-fire)`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = tier1Only)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Stop(
                id = "c1", symbol = "EURUSD", side = Side.SELL, quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.085"), 1L)))
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.080"), 2L)))

        assertThat(broker.submits).hasSize(1)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTier2FallbackTest"`
Expected: failures (no synthesis yet — broker.submits stays empty).

- [ ] **Step 3: Implement engine fallback**

Add to `OrderManager`:

```kotlin
    init {
        // existing BrokerEvent subscriptions ...
        bus.subscribe<TickEvent> { e -> evaluateTriggers(e.tick) }
    }

    private fun evaluateTriggers(tick: Tick) {
        val triggered: List<ManagedOrder> =
            orders.values
                .filter { it.state == OrderState.PENDING }
                .filter { it.request.symbol == tick.symbol }
                .filter { triggerHit(it.request, tick.price) }
                .toList()
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
    }

    private fun triggerHit(
        request: OrderRequest,
        tickPrice: BigDecimal,
    ): Boolean =
        when (request) {
            is OrderRequest.Stop ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice
                else tickPrice <= request.stopPrice
            is OrderRequest.StopLimit ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice
                else tickPrice <= request.stopPrice
            is OrderRequest.IfTouched ->
                if (request.side == Side.BUY) tickPrice <= request.triggerPrice
                else tickPrice >= request.triggerPrice
            else -> false
        }

    private fun fireFallbackTrigger(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        // Mark the original as filled-via-synthesis; submit the internal Tier 1.
        // The internal order shares the same client id so fills route back to the
        // outer ManagedOrder via the event subscription.
        update(managed.id) {
            it.copy(state = OrderState.SUBMITTED, lastUpdatedAt = clock.now())
        }
        val internal: OrderRequest =
            when (val req = managed.request) {
                is OrderRequest.Stop ->
                    OrderRequest.Market(
                        id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
                        timeInForce = req.timeInForce, timestamp = clock.now(),
                    )
                is OrderRequest.StopLimit ->
                    OrderRequest.Limit(
                        id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
                        limitPrice = req.limitPrice,
                        timeInForce = req.timeInForce, timestamp = clock.now(),
                    )
                is OrderRequest.IfTouched ->
                    if (req.onTrigger == TriggerType.MARKET) {
                        OrderRequest.Market(
                            id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
                            timeInForce = req.timeInForce, timestamp = clock.now(),
                        )
                    } else {
                        OrderRequest.Limit(
                            id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
                            limitPrice = req.limitPrice!!,
                            timeInForce = req.timeInForce, timestamp = clock.now(),
                        )
                    }
                else -> error("Not a Tier 2 fallback type: ${req::class.simpleName}")
            }
        broker.submit(internal)
    }
```

Add imports:

```kotlin
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.TriggerType
import com.qkt.marketdata.Tick
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTier2FallbackTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit Tasks 9 + 10 + 11 together**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/broker/FakeBroker.kt src/test/kotlin/com/qkt/app/OrderManagerTier2FallbackTest.kt
git commit -m "feat(execution): add engine-side Tier 2 fallback in OrderManager"
```

---

## Group F: TrailingStop synthesis

### Task 12: TrailingStop

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerTrailingTest.kt`

TrailingStop is always engine-side. Strategy: per pending TrailingStop, track a high-water mark (HWM) per symbol. On each tick, ratchet the stop level monotonically. When a tick crosses the current stop level, fire a Market.

- [ ] **Step 1: Write tests**

`src/test/kotlin/com/qkt/app/OrderManagerTrailingTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTrailingTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `TrailingStop SELL fires when price drops below trail level after ratcheting up`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val tracker = MarketPriceTracker()
        val om = OrderManager(broker, bus, tracker, clock)

        // initial price 100, trail by 5 absolute → initial stop at 95
        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
                trailAmount = Money.of("5"), trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))     // HWM=110, stop=105
        assertThat(broker.submits).isEmpty()

        bus.publish(TickEvent(Tick("X", Money.of("108"), 2L)))     // above stop, do not fire
        assertThat(broker.submits).isEmpty()

        bus.publish(TickEvent(Tick("X", Money.of("104.99"), 3L)))  // below stop → fire
        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(broker.submits.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `TrailingStop SELL stop level never moves down`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
                trailAmount = Money.of("5"), trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("120"), 1L)))   // stop=115
        bus.publish(TickEvent(Tick("X", Money.of("90"), 2L)))    // crashes — fires below stop=115
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `TrailingStop BUY (cover short) fires when price rises above trail level`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                trailAmount = Money.of("5"), trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("90"), 1L)))    // LWM=90, stop=95 (BUY ratchets DOWN)
        bus.publish(TickEvent(Tick("X", Money.of("96"), 2L)))    // crosses stop → fire
        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `TrailingStop PERCENT mode computes proportional trail`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
                trailAmount = Money.of("10"),       // 10%
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        // initial HWM=100, stop = 100 * 0.9 = 90
        bus.publish(TickEvent(Tick("X", Money.of("89"), 1L)))   // crosses
        assertThat(broker.submits).hasSize(1)
    }
}
```

- [ ] **Step 2: Implement TrailingStop synthesis**

Add internal state to `OrderManager` for trailing levels:

```kotlin
    // tracks trailing-stop high/low water marks per managed order id
    private val trailingHwm: MutableMap<String, BigDecimal> = mutableMapOf()
```

Extend `dispatch` to handle `TrailingStop`:

```kotlin
            is OrderRequest.TrailingStop -> holdPending(request)
```

Extend `evaluateTriggers` to also process TrailingStop. Replace:

```kotlin
    private fun evaluateTriggers(tick: Tick) {
        // Update trailing HWMs first (no firing during update)
        for (managed in orders.values.toList()) {
            if (managed.state != OrderState.PENDING) continue
            if (managed.request.symbol != tick.symbol) continue
            when (val r = managed.request) {
                is OrderRequest.TrailingStop -> updateTrailingHwm(managed.id, r, tick.price)
                else -> Unit
            }
        }

        // Evaluate triggers
        val triggered =
            orders.values
                .filter { it.state == OrderState.PENDING }
                .filter { it.request.symbol == tick.symbol }
                .filter { triggerHit(it, tick.price) }
                .toList()
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
    }

    private fun updateTrailingHwm(
        id: String,
        req: OrderRequest.TrailingStop,
        tickPrice: BigDecimal,
    ) {
        val current = trailingHwm[id]
        when (req.side) {
            Side.SELL ->
                if (current == null || tickPrice > current) trailingHwm[id] = tickPrice
            Side.BUY ->
                if (current == null || tickPrice < current) trailingHwm[id] = tickPrice
        }
    }

    private fun trailingStopLevel(
        id: String,
        req: OrderRequest.TrailingStop,
    ): BigDecimal? {
        val hwm = trailingHwm[id] ?: return null
        return when (req.trailMode) {
            TrailMode.ABSOLUTE ->
                if (req.side == Side.SELL) hwm - req.trailAmount else hwm + req.trailAmount
            TrailMode.PERCENT -> {
                val factor = req.trailAmount.divide(BigDecimal("100"), Money.CONTEXT)
                if (req.side == Side.SELL) {
                    hwm.multiply(BigDecimal.ONE - factor, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING)
                } else {
                    hwm.multiply(BigDecimal.ONE + factor, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING)
                }
            }
        }
    }
```

Update `triggerHit` signature to take `ManagedOrder` (so it can read state), and add the TrailingStop case:

```kotlin
    private fun triggerHit(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ): Boolean =
        when (val request = managed.request) {
            is OrderRequest.Stop ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice
                else tickPrice <= request.stopPrice
            is OrderRequest.StopLimit ->
                if (request.side == Side.BUY) tickPrice >= request.stopPrice
                else tickPrice <= request.stopPrice
            is OrderRequest.IfTouched ->
                if (request.side == Side.BUY) tickPrice <= request.triggerPrice
                else tickPrice >= request.triggerPrice
            is OrderRequest.TrailingStop -> {
                val level = trailingStopLevel(managed.id, request) ?: return false
                if (request.side == Side.SELL) tickPrice <= level else tickPrice >= level
            }
            else -> false
        }
```

Extend `fireFallbackTrigger` to handle `TrailingStop` (fires Market):

```kotlin
                is OrderRequest.TrailingStop ->
                    OrderRequest.Market(
                        id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
                        timeInForce = req.timeInForce, timestamp = clock.now(),
                    )
```

Add import:

```kotlin
import com.qkt.execution.TrailMode
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTrailingTest"`
Expected: 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerTrailingTest.kt
git commit -m "feat(execution): add TrailingStop synthesis in OrderManager"
```

---

### Task 13: TrailingStopLimit

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Modify: `src/test/kotlin/com/qkt/app/OrderManagerTrailingTest.kt`

Same trail logic; on trigger, fires a `Limit` at `trailingStopLevel ± limitOffset`.

- [ ] **Step 1: Add test**

```kotlin
    @Test
    fun `TrailingStopLimit fires Limit at stopLevel minus limitOffset for SELL`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStopLimit(
                id = "t1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
                trailAmount = Money.of("5"), trailMode = TrailMode.ABSOLUTE,
                limitOffset = Money.of("0.5"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))   // HWM=110, stop=105
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))   // crosses → fire Limit at 105 - 0.5 = 104.5
        assertThat(broker.submits).hasSize(1)
        val submitted = broker.submits.single()
        assertThat(submitted).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat((submitted as OrderRequest.Limit).limitPrice).isEqualByComparingTo(Money.of("104.5"))
    }

    @Test
    fun `TrailingStopLimit ratchets like TrailingStop`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStopLimit(
                id = "t1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
                trailAmount = Money.of("5"), trailMode = TrailMode.ABSOLUTE,
                limitOffset = Money.of("0.5"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("120"), 1L)))   // HWM=120, stop=115
        bus.publish(TickEvent(Tick("X", Money.of("116"), 2L)))   // above stop, do not fire
        bus.publish(TickEvent(Tick("X", Money.of("90"), 3L)))    // below stop=115 → fire
        assertThat(broker.submits).hasSize(1)
    }
```

- [ ] **Step 2: Extend OrderManager**

Add to `dispatch`:

```kotlin
            is OrderRequest.TrailingStopLimit -> holdPending(request)
```

Add a HWM helper variant for TrailingStopLimit (same logic as TrailingStop). Actually, easiest is to coalesce: change `updateTrailingHwm` to accept either type. Refactor:

```kotlin
    private fun updateTrailingHwm(managed: ManagedOrder, tickPrice: BigDecimal) {
        val (side, _) = trailParams(managed.request) ?: return
        val current = trailingHwm[managed.id]
        when (side) {
            Side.SELL -> if (current == null || tickPrice > current) trailingHwm[managed.id] = tickPrice
            Side.BUY -> if (current == null || tickPrice < current) trailingHwm[managed.id] = tickPrice
        }
    }

    /** Returns (side, trailAmount, trailMode, limitOffset?) for any TrailingStop variant. */
    private fun trailParams(req: OrderRequest): TrailParams? =
        when (req) {
            is OrderRequest.TrailingStop ->
                TrailParams(req.side, req.trailAmount, req.trailMode, limitOffset = null)
            is OrderRequest.TrailingStopLimit ->
                TrailParams(req.side, req.trailAmount, req.trailMode, limitOffset = req.limitOffset)
            else -> null
        }

    private data class TrailParams(
        val side: Side,
        val trailAmount: BigDecimal,
        val trailMode: TrailMode,
        val limitOffset: BigDecimal?,
    )

    private fun trailLevel(managed: ManagedOrder): BigDecimal? {
        val params = trailParams(managed.request) ?: return null
        val hwm = trailingHwm[managed.id] ?: return null
        return when (params.trailMode) {
            TrailMode.ABSOLUTE ->
                if (params.side == Side.SELL) hwm - params.trailAmount else hwm + params.trailAmount
            TrailMode.PERCENT -> {
                val factor = params.trailAmount.divide(BigDecimal("100"), Money.CONTEXT)
                if (params.side == Side.SELL) {
                    hwm.multiply(BigDecimal.ONE - factor, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING)
                } else {
                    hwm.multiply(BigDecimal.ONE + factor, Money.CONTEXT)
                        .setScale(Money.SCALE, Money.ROUNDING)
                }
            }
        }
    }
```

Update `triggerHit` (TrailingStop AND TrailingStopLimit):

```kotlin
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> {
                val params = trailParams(managed.request) ?: return false
                val level = trailLevel(managed) ?: return false
                if (params.side == Side.SELL) tickPrice <= level else tickPrice >= level
            }
```

Update `evaluateTriggers` to call the simpler `updateTrailingHwm(managed, tick.price)`:

```kotlin
    private fun evaluateTriggers(tick: Tick) {
        for (managed in orders.values.toList()) {
            if (managed.state != OrderState.PENDING) continue
            if (managed.request.symbol != tick.symbol) continue
            updateTrailingHwm(managed, tick.price)
        }
        val triggered =
            orders.values
                .filter { it.state == OrderState.PENDING }
                .filter { it.request.symbol == tick.symbol }
                .filter { triggerHit(it, tick.price) }
                .toList()
        for (managed in triggered) fireFallbackTrigger(managed, tick.price)
    }
```

Update `fireFallbackTrigger` to handle TrailingStopLimit:

```kotlin
                is OrderRequest.TrailingStopLimit -> {
                    val level = trailLevel(managed) ?: error("TrailingStopLimit level missing for ${managed.id}")
                    val limitPrice =
                        if (req.side == Side.SELL) level - req.limitOffset else level + req.limitOffset
                    OrderRequest.Limit(
                        id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
                        limitPrice = limitPrice.setScale(Money.SCALE, Money.ROUNDING),
                        timeInForce = req.timeInForce, timestamp = clock.now(),
                    )
                }
```

(Note: `fireFallbackTrigger` needs access to `managed`, not just `req`. Update its signature: `fireFallbackTrigger(managed: ManagedOrder, tickPrice: BigDecimal)`.)

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.app.OrderManagerTrailingTest"`
Expected: 6 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerTrailingTest.kt
git commit -m "feat(execution): add TrailingStopLimit synthesis"
```

---

## Group G: Composites

### Task 14: `StandaloneOCO` sibling cancel-on-fill

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerOcoTest.kt`

`StandaloneOCO(leg1, leg2)`: submit BOTH legs to broker (book-resident; getting good fills). Tag both with the same `groupId`. On OrderFilled for either leg, cancel the sibling.

- [ ] **Step 1: Write tests**

`src/test/kotlin/com/qkt/app/OrderManagerOcoTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOcoTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun limit(id: String, side: Side, price: String) =
        OrderRequest.Limit(
            id = id, symbol = "X", side = side, quantity = Money.of("1"),
            limitPrice = Money.of(price),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )

    @Test
    fun `submits both legs of OCO to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("l1", "l2")
    }

    @Test
    fun `leg1 fill cancels leg2`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l1" }, price = Money.of("100"))

        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).contains("l2")
    }

    @Test
    fun `leg2 fill cancels leg1`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l2" }, price = Money.of("120"))

        assertThat(broker.cancels).contains("l1")
    }

    @Test
    fun `cancelling the OCO group cancels both legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        om.cancel("oco1")

        assertThat(broker.cancels).containsExactlyInAnyOrder("l1", "l2")
    }
}
```

- [ ] **Step 2: Implement**

Extend `dispatch`:

```kotlin
            is OrderRequest.StandaloneOCO -> submitOco(request)
```

Add submitOco + sibling tracking:

```kotlin
    // siblings[clientOrderId] -> sibling clientOrderIds (for OCO)
    private val siblings: MutableMap<String, List<String>> = mutableMapOf()

    private fun submitOco(req: OrderRequest.StandaloneOCO): SubmitAck {
        val groupId = req.id
        // Track the OCO container as WORKING (synthetic)
        update(req.id) {
            it.copy(state = OrderState.WORKING, groupId = groupId, lastUpdatedAt = clock.now(),
                    childClientOrderIds = listOf(req.leg1.id, req.leg2.id))
        }
        // Track legs
        for (leg in listOf(req.leg1, req.leg2)) {
            track(
                ManagedOrder(
                    id = leg.id, request = leg, state = OrderState.CREATED,
                    parentClientOrderId = req.id, groupId = groupId,
                    createdAt = clock.now(), lastUpdatedAt = clock.now(),
                ),
            )
        }
        siblings[req.leg1.id] = listOf(req.leg2.id)
        siblings[req.leg2.id] = listOf(req.leg1.id)

        dispatch(req.leg1)
        dispatch(req.leg2)
        return SubmitAck(req.id, req.id, accepted = true)
    }
```

Extend `onFilled` to cancel siblings:

```kotlin
    private fun onFilled(e: BrokerEvent.OrderFilled) {
        update(e.clientOrderId) { ... existing ... }
        // OCO sibling cancellation
        siblings[e.clientOrderId]?.forEach { sibId ->
            val sib = orders[sibId] ?: return@forEach
            if (!sib.state.isTerminal) cancel(sibId)
        }
    }
```

Extend `cancel` to cascade:

```kotlin
    fun cancel(clientOrderId: String) {
        val managed = orders[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        // Cascade to children if this is a composite container
        if (managed.childClientOrderIds.isNotEmpty()) {
            for (childId in managed.childClientOrderIds) cancel(childId)
            return
        }
        when (managed.state) {
            OrderState.PENDING ->
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            else -> broker.cancel(clientOrderId)
        }
    }
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.app.OrderManagerOcoTest"
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerOcoTest.kt
git commit -m "feat(execution): add StandaloneOCO sibling-cancel choreography"
```

---

### Task 15: `OTO` parent-fill → children activation

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerOtoTest.kt`

`OTO(parent, children)`: submit `parent` immediately. Children stay in CREATED state, not yet submitted to broker. On `parent` fill, dispatch each child via the normal recursive `dispatch()` (which routes by tier).

- [ ] **Step 1: Write tests**

`src/test/kotlin/com/qkt/app/OrderManagerOtoTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOtoTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `OTO submits parent only initially`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent = OrderRequest.Limit(
            id = "p1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("100"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        val child = OrderRequest.Limit(
            id = "c1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
            limitPrice = Money.of("110"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.OTO(
                id = "oto1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                parent = parent, children = listOf(child),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CREATED)
    }

    @Test
    fun `parent fill activates children`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent = OrderRequest.Limit(
            id = "p1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("100"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        val child = OrderRequest.Limit(
            id = "c1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
            limitPrice = Money.of("110"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.OTO(
                id = "oto1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                parent = parent, children = listOf(child),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        broker.emitFill(parent, price = Money.of("100"))

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("p1", "c1")
    }

    @Test
    fun `cancel before parent fill cancels parent and children stay CREATED then CANCELLED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent = OrderRequest.Limit(
            id = "p1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("100"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        val child = OrderRequest.Limit(
            id = "c1", symbol = "X", side = Side.SELL, quantity = Money.of("1"),
            limitPrice = Money.of("110"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.OTO(
                id = "oto1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                parent = parent, children = listOf(child),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        om.cancel("oto1")

        assertThat(broker.cancels).contains("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }
}
```

- [ ] **Step 2: Implement**

Extend `dispatch`:

```kotlin
            is OrderRequest.OTO -> submitOto(request)
```

Add `submitOto`:

```kotlin
    private val pendingChildren: MutableMap<String, List<OrderRequest>> = mutableMapOf()

    private fun submitOto(req: OrderRequest.OTO): SubmitAck {
        val childIds = req.children.map { it.id }
        update(req.id) {
            it.copy(state = OrderState.WORKING, childClientOrderIds = listOf(req.parent.id) + childIds,
                    lastUpdatedAt = clock.now())
        }
        // Track parent
        track(
            ManagedOrder(
                id = req.parent.id, request = req.parent, state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = clock.now(), lastUpdatedAt = clock.now(),
            ),
        )
        // Track children in CREATED, not yet dispatched
        for (child in req.children) {
            track(
                ManagedOrder(
                    id = child.id, request = child, state = OrderState.CREATED,
                    parentClientOrderId = req.id,
                    createdAt = clock.now(), lastUpdatedAt = clock.now(),
                ),
            )
        }
        pendingChildren[req.parent.id] = req.children
        dispatch(req.parent)
        return SubmitAck(req.id, req.id, accepted = true)
    }
```

Extend `onFilled` to activate children:

```kotlin
    private fun onFilled(e: BrokerEvent.OrderFilled) {
        // ... existing update ...
        // OTO child activation
        pendingChildren.remove(e.clientOrderId)?.forEach { dispatch(it) }
        // OCO sibling cancellation (existing)
        siblings[e.clientOrderId]?.forEach { ... }
    }
```

Extend `cancel` to handle CREATED children (mark CANCELLED without broker call):

```kotlin
    fun cancel(clientOrderId: String) {
        val managed = orders[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        if (managed.childClientOrderIds.isNotEmpty()) {
            for (childId in managed.childClientOrderIds) cancel(childId)
            update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING ->
                update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            else -> broker.cancel(clientOrderId)
        }
    }
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.app.OrderManagerOtoTest"
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerOtoTest.kt
git commit -m "feat(execution): add OTO parent-fill children-activation"
```

---

### Task 16: `Bracket` (native handoff or OTO+OCO decomposition)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerBracketTest.kt`

If broker advertises `BRACKET`, hand off whole. Otherwise decompose to `OTO(entry, [StandaloneOCO(Limit at TP, Stop at SL)])` and recursively dispatch.

- [ ] **Step 1: Write tests**

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerBracketTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun bracket(): OrderRequest.Bracket {
        val entry = OrderRequest.Limit(
            id = "e1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("100"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        return OrderRequest.Bracket(
            id = "b1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            entry = entry, takeProfit = Money.of("110"), stopLoss = Money.of("95"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
    }

    @Test
    fun `Bracket with native capability ships whole to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.BRACKET)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())

        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Bracket::class.java)
    }

    @Test
    fun `Bracket without native capability decomposes to entry first`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())

        // Only entry submitted initially; TP+SL waiting in OCO
        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single().id).isEqualTo("e1")
    }

    @Test
    fun `Bracket fallback: entry fill activates TP and SL legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        assertThat(broker.submits.size).isGreaterThanOrEqualTo(3)   // entry + TP + SL
        assertThat(broker.submits.map { it::class.simpleName })
            .contains("Limit", "Stop")
    }

    @Test
    fun `Bracket fallback: TP fill cancels SL`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val caps = setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))    // entry fills
        // After entry fill, broker.submits has entry + tp + sl. Find the TP-Limit (sell at 110).
        val tp = broker.submits.first { it is OrderRequest.Limit && it.id != "e1" }
        broker.emitFill(tp, price = Money.of("110"))

        // The SL (Stop) must be cancelled
        val slId = broker.submits.first { it is OrderRequest.Stop }.id
        assertThat(broker.cancels).contains(slId)
    }
}
```

- [ ] **Step 2: Implement**

Extend `dispatch`:

```kotlin
            is OrderRequest.Bracket ->
                if (OrderTypeCapability.BRACKET in broker.capabilities) submitToBroker(request)
                else submitBracketFallback(request)
```

Add `submitBracketFallback`:

```kotlin
    private fun submitBracketFallback(req: OrderRequest.Bracket): SubmitAck {
        // Build TP and SL legs from the price levels.
        val exitSide = if (req.side == Side.BUY) Side.SELL else Side.BUY
        val tp = OrderRequest.Limit(
            id = "${req.id}-tp", symbol = req.symbol, side = exitSide, quantity = req.quantity,
            limitPrice = req.takeProfit, timeInForce = req.timeInForce, timestamp = clock.now(),
        )
        val sl = OrderRequest.Stop(
            id = "${req.id}-sl", symbol = req.symbol, side = exitSide, quantity = req.quantity,
            stopPrice = req.stopLoss, timeInForce = req.timeInForce, timestamp = clock.now(),
        )
        val oco = OrderRequest.StandaloneOCO(
            id = "${req.id}-oco", symbol = req.symbol, side = exitSide, quantity = req.quantity,
            leg1 = tp, leg2 = sl, timeInForce = req.timeInForce, timestamp = clock.now(),
        )
        val oto = OrderRequest.OTO(
            id = req.id, symbol = req.symbol, side = req.side, quantity = req.quantity,
            parent = req.entry, children = listOf(oco),
            timeInForce = req.timeInForce, timestamp = clock.now(),
        )
        // Replace the existing tracked entry for req.id (currently the Bracket) with the OTO container.
        // Cleanest: remove req.id and re-submit as OTO.
        orders.remove(req.id)
        return submit(oto)
    }
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.app.OrderManagerBracketTest"
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerBracketTest.kt
git commit -m "feat(execution): add Bracket native and fallback dispatch"
```

---

### Task 17: `ScaleOut` multi-leg

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerScaleOutTest.kt`

`ScaleOut(basis, legs)`: submit basis. On basis fill, for each leg create an `IfTouched(triggerPrice = leg.priceTarget, side = exit, quantity = filledQty * leg.fraction, onTrigger = MARKET)` and dispatch each.

- [ ] **Step 1: Tests**

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerScaleOutTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `ScaleOut submits basis only`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis = OrderRequest.Market(
            id = "e1", symbol = "X", side = Side.BUY, quantity = Money.of("3"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1", symbol = "X", side = Side.BUY, quantity = Money.of("3"),
                basis = basis,
                legs = listOf(
                    ScaleOutLeg(Money.of("110"), Money.of("0.33")),
                    ScaleOutLeg(Money.of("120"), Money.of("0.33")),
                ),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("e1")
    }

    @Test
    fun `basis fill activates leg orders sized by fraction`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.IF_TOUCHED))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis = OrderRequest.Market(
            id = "e1", symbol = "X", side = Side.BUY, quantity = Money.of("3"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1", symbol = "X", side = Side.BUY, quantity = Money.of("3"),
                basis = basis,
                legs = listOf(
                    ScaleOutLeg(Money.of("110"), Money.of("0.5")),
                    ScaleOutLeg(Money.of("120"), Money.of("0.5")),
                ),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        broker.emitFill(basis, price = Money.of("100"))

        // After basis fill: 2 IfTouched legs submitted, each with quantity 1.5
        val legSubmits = broker.submits.filter { it is OrderRequest.IfTouched }
        assertThat(legSubmits).hasSize(2)
        assertThat(legSubmits.first().quantity).isEqualByComparingTo(Money.of("1.5"))
    }

    @Test
    fun `ScaleOut leg side is opposite of basis side`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.IF_TOUCHED))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis = OrderRequest.Market(
            id = "e1", symbol = "X", side = Side.BUY, quantity = Money.of("2"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1", symbol = "X", side = Side.BUY, quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        broker.emitFill(basis, price = Money.of("100"))

        val leg = broker.submits.first { it is OrderRequest.IfTouched } as OrderRequest.IfTouched
        assertThat(leg.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `cancelling ScaleOut before basis fill cancels basis`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis = OrderRequest.Market(
            id = "e1", symbol = "X", side = Side.BUY, quantity = Money.of("2"),
            timeInForce = TimeInForce.GTC, timestamp = 0L,
        )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1", symbol = "X", side = Side.BUY, quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC, timestamp = 0L,
            ),
        )
        om.cancel("s1")

        assertThat(broker.cancels).contains("e1")
    }
}
```

- [ ] **Step 2: Implement**

Add to `dispatch`:

```kotlin
            is OrderRequest.ScaleOut -> submitScaleOut(request)
```

Add helper:

```kotlin
    private val scaleOutLegs: MutableMap<String, Pair<OrderRequest.ScaleOut, BigDecimal /*basisQty*/>> = mutableMapOf()

    private fun submitScaleOut(req: OrderRequest.ScaleOut): SubmitAck {
        update(req.id) {
            it.copy(state = OrderState.WORKING, childClientOrderIds = listOf(req.basis.id),
                    lastUpdatedAt = clock.now())
        }
        track(
            ManagedOrder(
                id = req.basis.id, request = req.basis, state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = clock.now(), lastUpdatedAt = clock.now(),
            ),
        )
        scaleOutLegs[req.basis.id] = req to req.basis.quantity
        dispatch(req.basis)
        return SubmitAck(req.id, req.id, accepted = true)
    }
```

Extend `onFilled`:

```kotlin
        scaleOutLegs.remove(e.clientOrderId)?.let { (scaleReq, basisQty) ->
            val exitSide = if (scaleReq.side == Side.BUY) Side.SELL else Side.BUY
            scaleReq.legs.forEachIndexed { idx, leg ->
                val legQty = basisQty.multiply(leg.fraction).setScale(Money.SCALE, Money.ROUNDING)
                val legReq = OrderRequest.IfTouched(
                    id = "${scaleReq.id}-leg-$idx",
                    symbol = scaleReq.symbol,
                    side = exitSide,
                    quantity = legQty,
                    triggerPrice = leg.priceTarget,
                    onTrigger = TriggerType.MARKET,
                    timeInForce = scaleReq.timeInForce,
                    timestamp = clock.now(),
                )
                dispatch(legReq)
            }
        }
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.app.OrderManagerScaleOutTest"
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerScaleOutTest.kt
git commit -m "feat(execution): add ScaleOut multi-leg fractional exits"
```

---

### Task 18: `TimeExit` deadline

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Create: `src/test/kotlin/com/qkt/app/OrderManagerTimeExitTest.kt`

`TimeExit(target, deadline, onExpiry)`: submit `target` immediately. Track the deadline. On every tick (or scheduled tick), check whether `clock.now() >= deadline.toEpochMilli()`. On expiry: if `target` is still active, perform `onExpiry`:
- `CANCEL`: call `cancel(target.id)`.
- `CLOSE_AT_MARKET`: cancel target (if pending) and submit a Market in opposite direction with the same quantity.

For 7d-b, time advances via tick events (deterministic in backtest; real-clock in live where ticks fire frequently). A scheduled timer is out of scope.

- [ ] **Step 1: Tests**

```kotlin
package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTimeExitTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `TimeExit CANCEL cancels target after deadline`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val target = OrderRequest.Limit(
            id = "tg1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("100"),
            timeInForce = TimeInForce.GTC, timestamp = 1_000L,
        )
        om.submit(
            OrderRequest.TimeExit(
                id = "te1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                target = target,
                deadline = Instant.ofEpochMilli(2_000L),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC, timestamp = 1_000L,
            ),
        )
        assertThat(broker.submits.map { it.id }).containsExactly("tg1")

        clock.time = 2_500L
        bus.publish(TickEvent(Tick("X", Money.of("99"), 2_500L)))

        assertThat(broker.cancels).contains("tg1")
    }

    @Test
    fun `TimeExit before deadline does nothing`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.TimeExit(
                id = "te1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                target = OrderRequest.Limit(
                    id = "tg1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                    limitPrice = Money.of("100"),
                    timeInForce = TimeInForce.GTC, timestamp = 1_000L,
                ),
                deadline = Instant.ofEpochMilli(5_000L),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC, timestamp = 1_000L,
            ),
        )

        clock.time = 1_500L
        bus.publish(TickEvent(Tick("X", Money.of("99"), 1_500L)))

        assertThat(broker.cancels).isEmpty()
    }

    @Test
    fun `TimeExit CLOSE_AT_MARKET fires Market on opposite side after deadline if target was filled`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val target = OrderRequest.Limit(
            id = "tg1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
            limitPrice = Money.of("100"),
            timeInForce = TimeInForce.GTC, timestamp = 1_000L,
        )
        om.submit(
            OrderRequest.TimeExit(
                id = "te1", symbol = "X", side = Side.BUY, quantity = Money.of("1"),
                target = target,
                deadline = Instant.ofEpochMilli(2_000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC, timestamp = 1_000L,
            ),
        )
        broker.emitFill(target, price = Money.of("100"))      // target fills

        clock.time = 2_500L
        bus.publish(TickEvent(Tick("X", Money.of("101"), 2_500L)))

        // After deadline + filled, expect a Market SELL (opposite side) for the same quantity
        val closing = broker.submits.lastOrNull { it is OrderRequest.Market && it.id != "tg1" }
        assertThat(closing).isNotNull
        assertThat(closing!!.side).isEqualTo(Side.SELL)
        assertThat(closing.quantity).isEqualByComparingTo(Money.of("1"))
    }
}
```

- [ ] **Step 2: Implement**

Add to `dispatch`:

```kotlin
            is OrderRequest.TimeExit -> submitTimeExit(request)
```

Add:

```kotlin
    private val timeExits: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()

    private fun submitTimeExit(req: OrderRequest.TimeExit): SubmitAck {
        update(req.id) {
            it.copy(state = OrderState.WORKING, childClientOrderIds = listOf(req.target.id),
                    lastUpdatedAt = clock.now())
        }
        track(
            ManagedOrder(
                id = req.target.id, request = req.target, state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = clock.now(), lastUpdatedAt = clock.now(),
            ),
        )
        timeExits[req.id] = req
        dispatch(req.target)
        return SubmitAck(req.id, req.id, accepted = true)
    }
```

Extend `evaluateTriggers` to also check time exits:

```kotlin
    private fun evaluateTriggers(tick: Tick) {
        // ... existing trail HWM update ...
        // Time-exit deadline checks
        val now = clock.now()
        val expired =
            timeExits.entries
                .filter { (_, te) -> now >= te.deadline.toEpochMilli() }
                .map { it.value }
                .toList()
        for (te in expired) {
            timeExits.remove(te.id)
            handleTimeExitExpiry(te)
        }
        // ... existing trigger evaluation ...
    }

    private fun handleTimeExitExpiry(te: OrderRequest.TimeExit) {
        val target = orders[te.target.id] ?: return
        when (te.onExpiry) {
            ExpiryAction.CANCEL -> {
                if (!target.state.isTerminal) cancel(te.target.id)
                update(te.id) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            }
            ExpiryAction.CLOSE_AT_MARKET -> {
                if (target.state == OrderState.FILLED) {
                    val exitSide = if (te.target.side == Side.BUY) Side.SELL else Side.BUY
                    val closing = OrderRequest.Market(
                        id = "${te.id}-close",
                        symbol = te.symbol,
                        side = exitSide,
                        quantity = te.target.quantity,
                        timeInForce = te.timeInForce,
                        timestamp = clock.now(),
                    )
                    dispatch(closing)
                } else if (!target.state.isTerminal) {
                    cancel(te.target.id)
                }
                update(te.id) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
            }
        }
    }
```

Add import:

```kotlin
import com.qkt.execution.ExpiryAction
```

- [ ] **Step 3: Run tests; commit**

```bash
./gradlew test --tests "com.qkt.app.OrderManagerTimeExitTest"
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerTimeExitTest.kt
git commit -m "feat(execution): add TimeExit deadline-driven expiry handling"
```

---

## Group H: Pipeline integration

### Task 19: `PaperBroker` subscribes to `TickEvent` directly

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- Modify: `src/test/kotlin/com/qkt/broker/PaperBrokerTest.kt`

Drop the `b.onTick(tick)` direct call pattern. PaperBroker now subscribes to `TickEvent` in `init`. The pipeline-side cast wart is then removable (Task 20).

- [ ] **Step 1: Update `PaperBroker`**

In the constructor body, add:

```kotlin
    init {
        bus.subscribe<com.qkt.events.TickEvent> { e -> onTick(e.tick) }
    }
```

Make `onTick` `private fun onTick(tick: Tick)` (currently public). Tests need to publish ticks via the bus instead of calling `onTick` directly.

- [ ] **Step 2: Update `PaperBrokerTest`**

Replace every `b.onTick(tick(...))` call with `bus.publish(TickEvent(tick(...)))`. Add `import com.qkt.events.TickEvent`.

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.broker.PaperBrokerTest"`
Expected: all PaperBroker tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/PaperBroker.kt src/test/kotlin/com/qkt/broker/PaperBrokerTest.kt
git commit -m "refactor(broker): PaperBroker subscribes to TickEvent on the bus"
```

---

### Task 20: `TradingPipeline` routes through `OrderManager`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`

- [ ] **Step 1: Update `TradingPipeline`**

Replace the relevant subscription block. Was:

```kotlin
        bus.subscribe<OrderEvent> { e ->
            broker.submit(e.request)
        }
```

After:

```kotlin
        bus.subscribe<OrderEvent> { e ->
            orderManager.submit(e.request)
        }
```

Add `orderManager: OrderManager` to the constructor (build it in `init` if not already constructed by callers — but it needs broker, bus, priceTracker, clock; cleaner to construct it here):

```kotlin
class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionTracker,
    val pnl: PnLCalculator,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Strategy>,
    val riskEngine: RiskEngine,
    val sessionContext: SessionContext,
    val candleWindow: TimeWindow? = null,
    val onFilled: (Trade, BigDecimal) -> Unit = { _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
) {
    val orderManager = OrderManager(broker, bus, priceTracker, clock)
    // ... rest of init ...
}
```

Drop the `if (broker is PaperBroker) broker.onTick(tick)` line in `ingest()`. PaperBroker now subscribes directly.

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 21: `LiveSession`/`Backtest` construct `OrderManager`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`

Since `TradingPipeline` now constructs OrderManager internally, no changes needed in callers. Verify by compile.

- [ ] **Step 1: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

---

### Task 22: Migrate broken existing tests

**Files:**
- Inspect: `src/test/kotlin/com/qkt/...` — find any tests broken by the `PaperBroker.onTick` privatization or the pipeline rewire.

- [ ] **Step 1: Compile tests**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL. Any errors → migrate the failing tests.

- [ ] **Step 2: Run all tests**

Run: `./gradlew test`
Expected: ~458 tests PASS.

- [ ] **Step 3: Commit Tasks 20–22 together**

```bash
git add src/main/kotlin/com/qkt/app/TradingPipeline.kt src/test/
git commit -m "refactor(app): pipeline routes through OrderManager"
```

---

## Group I: Verification

### Task 23: Full build + tests

- [ ] **Step 1: ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. ~458 tests pass.

- [ ] **Step 3: Verify Main demo**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: 10 (Phase 7 invariant).

- [ ] **Step 4: Commit any format-driven fixups**

```bash
git status
git add -p
git commit -m "style: ktlintFormat after OrderManager additions"
```

---

### Task 24: Optional `MaxAudit` smoke check

If you have network access, re-run the live audit to confirm broker-layer changes did not regress live behavior.

- [ ] **Step 1: Run**

Run: `timeout 240 ./gradlew runMaxAudit --console=plain 2>&1 | tee /tmp/maxaudit-7db.log | tail -60`

Expected: same shape as 7d-a's audit (5+ symbols return bars, 7/7 live ticks, cross-asset events fire). Skip if no network.

- [ ] **Step 2: No commit**

Verification only.

---

### Task 25: Phase 7d changelog

**Files:**
- Create: `docs/phases/phase-7d-broker-and-orders.md`

Per qkt SKILL.md §6, every phase ships a changelog. Phase 7d (a + b combined) gets one document.

- [ ] **Step 1: Write the changelog**

Aim for 250–400 lines. Required sections (per SKILL.md §6):

1. **Summary** — 2–4 sentences.
2. **What's new** — list every new public type / function added across 7d-a and 7d-b.
3. **Migration from previous phase** — `MockBroker → PaperBroker`, `Order → OrderRequest`, `OrderType → sealed hierarchy`, `Broker.execute → submit/cancel`, `RiskRule(order) → RiskRule(request)`.
4. **Usage cookbook** — at least 8 worked examples covering:
   - Submitting Market and Limit through the pipeline (existing path).
   - Using `Signal.Submit(OrderRequest.Stop(...))` for non-Market types.
   - Bracket on a broker that supports it natively.
   - Bracket on a broker that does not (engine fallback).
   - StandaloneOCO with two competing entries.
   - OTO with TrailingStop child (compose).
   - ScaleOut over a Market entry.
   - TimeExit wrapping a Limit.
   - LogBroker for strategy testing (no fills).
5. **Testing patterns** — `FakeBroker` programmable capabilities; OrderManager state inspection; `BrokerEvent.OrderFilled` synthesis for testing.
6. **Known limitations** — same list as the spec section 19 plus:
   - TimeExit's `CLOSE_AT_MARKET` only fires after a tick advances the clock past the deadline (no scheduler).
   - OrderManager's pending-order list is in-memory only; restarts lose state.
   - Order modify is not exposed via OrderManager (cancel + resubmit only).
7. **References** — links to spec, plan 7d-a, plan 7d-b, merge SHAs (placeholders OK).

- [ ] **Step 2: Verify line count**

Run: `wc -l docs/phases/phase-7d-broker-and-orders.md`
Expected: between 200 and 600 lines.

- [ ] **Step 3: Commit**

```bash
git add docs/phases/phase-7d-broker-and-orders.md
git commit -m "docs: phase 7d changelog with usage cookbook"
```

---

### Task 26: Final verification + branch state

- [ ] **Step 1: Branch state**

Run: `git log --oneline main..HEAD`
Expected: ~25 commits, all conventional, no AI footers.

- [ ] **Step 2: Pre-push checklist**

Run:
```bash
./gradlew build
git status
grep -rEn 'TODO|FIXME|XXX' src/ | grep -v "// \?"
```

- `./gradlew build` ends BUILD SUCCESSFUL.
- `git status` clean (or only `tt.txt`).
- No new TODO/FIXME without an issue link.

- [ ] **Step 3: Plan handoff**

Phase 7d-b is shippable. Decide with the user whether to merge `phase7d-b-order-manager-and-composites` into main.

After merge, Phase 7d (a + b) is fully shipped. Strategies can compose arbitrary order trees. Real-broker integration (Phase 7e) can begin.

---

## Spec ambiguities encountered

These are decisions the plan made which the spec left open or vague.

1. **TrailingStop behavior with no prior tick**: if a TrailingStop is submitted before any tick for the symbol, no HWM exists. The first tick for that symbol after submission seeds the HWM. Plan documents this by initializing trailingHwm on first tick.

2. **TrailingStop ratchet direction for BUY (cover short)**: BUY-side trailing stop ratchets DOWN (lowest-low watermark), and stop level = LWM + trailAmount. Fires when tick rises ABOVE the level. Test #3 in the trailing test suite asserts this.

3. **ScaleOut quantity = basis quantity, not filled quantity**: 7d-b uses `req.basis.quantity` (the requested amount) rather than the actual cumulative filled quantity. If the basis partially fills then completes, leg quantities are based on the original. Acceptable for paper/log brokers (they fill exactly the requested quantity); will need refinement when partial fills become realistic with real brokers (Phase 7e).

4. **Bracket fallback id naming**: synthesized child ids use `${parentId}-tp`, `${parentId}-sl`, `${parentId}-oco` to maintain traceability without external id generation.

5. **TimeExit time source**: deadline checked against `clock.now()` evaluated on each TickEvent. No scheduler. Deterministic in backtest where ticks advance the clock; in live, ticks fire frequently enough for effective deadline accuracy.

6. **Risk evaluation of composite orders**: 7d-b passes the OUTER composite (e.g. the OTO container) through the risk gate at submission time. Children are not individually risk-checked. This is consistent with spec section 14's "risk evaluates parent only" guidance and acceptable for 7d. ScaleOut is the edge case (legs unwind, never grow); Bracket and OCO same. Document in changelog.

7. **OrderManager + multiple brokers**: 7d-b assumes a single Broker per pipeline. Multi-broker dispatch (CompositeBroker analogue to CompositeMarketSource) is deferred to Phase 7e+; the OrderManager constructor's broker parameter is non-null, single-instance.

8. **`onAccepted` for engine-pending orders**: when OrderManager synthesizes `OrderAccepted` for an engine-side pending order (Stop fallback, TrailingStop, etc.), the bus emits the event so external subscribers see consistent lifecycle. The handler (also in OrderManager) preserves PENDING state when state is already PENDING — see Task 9 Step 1.

9. **`childClientOrderIds` as the cancel-cascade root**: any ManagedOrder that has children is treated as a composite container. `cancel(containerId)` recursively cancels children, then marks the container CANCELLED. This works uniformly for OTO, OCO, Bracket, ScaleOut, TimeExit.

10. **PaperBroker bus subscription ordering**: `PaperBroker.init { bus.subscribe<TickEvent> ... }` registers the broker's listener at construction time. If pipeline subscribes other handlers later, the broker's handler fires first. Tests don't depend on this ordering, but document for future reference.
