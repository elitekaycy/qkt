# Phase 8 — StrategyContext and PnL Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every order, fill, position, and PnL number traceable to a named strategy. Replace `SessionContext` with `StrategyContext` (the DSL prerequisite). Introduce parallel `StrategyPositionTracker` + `StrategyPnL` for attribution alongside the existing broker-truth global view.

**Architecture:** Strategies are registered with names. `OrderRequest` and `BrokerEvent.OrderEvent` carry `strategyId`. Brokers thread it via internal `strategyByClientOrderId` map. Two parallel position trackers (global broker-truth + per-strategy attribution) coexist; `PositionReconciled` resets only the global one. `StrategyContext` bundles strategy-scoped views.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ.

**Spec:** [`../specs/2026-05-06-trading-engine-phase8-design.md`](../specs/2026-05-06-trading-engine-phase8-design.md)

**Scale warning:** This phase touches ~50-70 files. Many tasks are mechanical bulk edits (`perl -i -pe`). Build green at every commit boundary; intra-task the build may be transiently red.

---

## Pre-flight

Branch already exists: `phase8-strategy-context-and-pnl-attribution`. Spec already committed.

```bash
git status
git branch --show-current     # phase8-strategy-context-and-pnl-attribution
./gradlew check               # green from main
```

---

## Task 1: `StrategyPositionTracker`

**Files:**
- Create: `src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt`
- Create: `src/test/kotlin/com/qkt/positions/StrategyPositionTrackerTest.kt`

Independent of any other change. New component, no breakage.

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/com/qkt/positions/StrategyPositionTrackerTest.kt
package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerTest {
    private fun fill(
        strategyId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = "c-${System.nanoTime()}",
        brokerOrderId = "b",
        strategyId = strategyId,
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        timestamp = 0L,
    )

    @Test
    fun `same-symbol fills from different strategies do not commingle`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.SELL, "0.3", "81000"))

        assertThat(tracker.positionFor("A", "BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("1"))
        assertThat(tracker.positionFor("B", "BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("-0.3"))
    }

    @Test
    fun `realized accrues per strategy independently`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        val realizedA = tracker.applyFill(fill("A", "BTCUSDT", Side.SELL, "1", "82000"))

        tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "79000"))

        assertThat(realizedA).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(tracker.positionFor("A", "BTCUSDT")).isNull()
        assertThat(tracker.positionFor("B", "BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `positionsFor returns only that strategy's positions`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        tracker.applyFill(fill("A", "ETHUSDT", Side.BUY, "10", "3000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "79000"))

        assertThat(tracker.positionsFor("A").keys).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT")
        assertThat(tracker.positionsFor("B").keys).containsExactlyInAnyOrder("BTCUSDT")
    }

    @Test
    fun `applyFill with blank strategyId is a noop`() {
        val tracker = StrategyPositionTracker()
        val realized = tracker.applyFill(fill("", "BTCUSDT", Side.BUY, "1", "80000"))

        assertThat(realized).isEqualByComparingTo(Money.ZERO)
        assertThat(tracker.allByStrategy()).isEmpty()
    }

    @Test
    fun `driftFor returns difference between strategy sum and broker view`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "81000"))

        // Mock broker view that says we have only 1.0 (broker liquidated 0.5)
        val brokerView =
            object : PositionProvider {
                override fun positionFor(symbol: String): Position? =
                    if (symbol == "BTCUSDT") Position("BTCUSDT", BigDecimal("1.0"), BigDecimal("80000")) else null

                override fun allPositions(): Map<String, Position> = mapOf("BTCUSDT" to positionFor("BTCUSDT")!!)
            }

        val drift = tracker.driftFor("BTCUSDT", brokerView)
        assertThat(drift).isEqualByComparingTo(BigDecimal("0.5"))    // 1.5 strategy - 1.0 broker
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests com.qkt.positions.StrategyPositionTrackerTest`
Expected: FAIL — `StrategyPositionTracker` doesn't exist; also `BrokerEvent.OrderFilled` doesn't have `strategyId` yet.

**Note:** the test's `BrokerEvent.OrderFilled(strategyId = ...)` won't compile until T3 ships. Defer running this test until after T3.

For now, only stub the test compilation by removing the `strategyId =` field from each `fill(...)` helper temporarily. After T3, reinstate. (Or skip step 2 until T3.) Choose the latter — simpler.

- [ ] **Step 3: Implement `StrategyPositionTracker`**

Mirror `PositionTracker.applyFill` math but key by `(strategyId, symbol)`. Realized PnL math is identical.

```kotlin
// src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt
package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class StrategyPositionTracker {
    private val byStrategy: MutableMap<String, MutableMap<String, Position>> = ConcurrentHashMap()

    fun applyFill(event: BrokerEvent.OrderFilled): BigDecimal {
        if (event.strategyId.isBlank()) return Money.ZERO

        val trade =
            Trade(
                orderId = event.clientOrderId,
                symbol = event.symbol,
                price = event.price,
                quantity = event.quantity,
                side = event.side,
                timestamp = event.timestamp,
            )
        return apply(event.strategyId, trade)
    }

    fun apply(
        strategyId: String,
        trade: Trade,
    ): BigDecimal {
        val positions = byStrategy.getOrPut(strategyId) { ConcurrentHashMap() }
        val current = positions[trade.symbol]
        val signedTradeQty = if (trade.side == Side.BUY) trade.quantity else trade.quantity.negate()

        if (current == null) {
            positions[trade.symbol] = Position(trade.symbol, signedTradeQty, trade.price)
            return Money.ZERO
        }

        val currentQty = current.quantity
        val currentAvg = current.avgEntryPrice
        val sameDirection = currentQty.signum() == signedTradeQty.signum()

        if (sameDirection) {
            val totalQty = currentQty.add(signedTradeQty)
            val newAvg =
                currentAvg
                    .multiply(currentQty.abs())
                    .add(trade.price.multiply(trade.quantity))
                    .divide(totalQty.abs(), Money.CONTEXT)
                    .setScale(Money.SCALE, Money.ROUNDING)
            positions[trade.symbol] = Position(trade.symbol, totalQty, newAvg)
            return Money.ZERO
        }

        val closingQty = currentQty.abs().min(trade.quantity)
        val priceDiff =
            if (currentQty.signum() > 0) {
                trade.price.subtract(currentAvg)
            } else {
                currentAvg.subtract(trade.price)
            }
        val realized = closingQty.multiply(priceDiff).setScale(Money.SCALE, Money.ROUNDING)

        val remainingQty = currentQty.add(signedTradeQty)
        when {
            remainingQty.signum() == 0 -> positions.remove(trade.symbol)
            remainingQty.signum() == currentQty.signum() ->
                positions[trade.symbol] = Position(trade.symbol, remainingQty, currentAvg)
            else ->
                positions[trade.symbol] = Position(trade.symbol, remainingQty, trade.price)
        }
        return realized
    }

    fun positionFor(
        strategyId: String,
        symbol: String,
    ): Position? = byStrategy[strategyId]?.get(symbol)

    fun positionsFor(strategyId: String): Map<String, Position> =
        byStrategy[strategyId]?.toMap() ?: emptyMap()

    fun allByStrategy(): Map<String, Map<String, Position>> =
        byStrategy.mapValues { it.value.toMap() }

    fun driftFor(
        symbol: String,
        brokerView: PositionProvider,
    ): BigDecimal {
        val strategySum =
            byStrategy.values.fold(Money.ZERO) { acc, byMap ->
                acc.add(byMap[symbol]?.quantity ?: Money.ZERO)
            }
        val broker = brokerView.positionFor(symbol)?.quantity ?: Money.ZERO
        return strategySum.subtract(broker).setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

- [ ] **Step 4: Defer test verification to after T3**

The test references `BrokerEvent.OrderFilled.strategyId` which doesn't exist yet. Mark this task complete after the implementation file compiles standalone:

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL (test file may not compile, but main does).

- [ ] **Step 5: Commit (without running test)**

```bash
git add src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt \
        src/test/kotlin/com/qkt/positions/StrategyPositionTrackerTest.kt
git commit -m "feat(positions): add StrategyPositionTracker for per-strategy attribution"
```

The test will pass after T3 lands the strategyId on the event.

---

## Task 2: `StrategyPnL`

**Files:**
- Create: `src/main/kotlin/com/qkt/pnl/StrategyPnL.kt`
- Create: `src/test/kotlin/com/qkt/pnl/StrategyPnLTest.kt`

Depends on T1.

- [ ] **Step 1: Write tests**

```kotlin
// src/test/kotlin/com/qkt/pnl/StrategyPnLTest.kt
package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPnLTest {
    private fun fill(
        strategyId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = "c-${System.nanoTime()}",
        brokerOrderId = "b",
        strategyId = strategyId,
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        timestamp = 0L,
    )

    @Test
    fun `realizedFor accrues only this strategy's closes`() {
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        val rA1 = tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        val rA2 = tracker.applyFill(fill("A", "BTCUSDT", Side.SELL, "1", "82000"))
        pnl.recordRealized("A", rA1)
        pnl.recordRealized("A", rA2)

        val rB = tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "80000"))
        pnl.recordRealized("B", rB)

        assertThat(pnl.realizedFor("A")).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(pnl.realizedFor("B")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unrealizedFor uses this strategy's avg entry`() {
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))

        assertThat(pnl.unrealizedFor("A", "BTCUSDT")).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(pnl.unrealizedFor("B", "BTCUSDT")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `totalFor sums realized and unrealized for the strategy only`() {
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        val rA = tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        pnl.recordRealized("A", rA)
        prices.update("BTCUSDT", Money.of("82000"))

        assertThat(pnl.totalFor("A")).isEqualByComparingTo(BigDecimal("2000"))    // 0 realized + 2000 unrealized
    }
}
```

- [ ] **Step 2: Implement `StrategyPnL`**

```kotlin
// src/main/kotlin/com/qkt/pnl/StrategyPnL.kt
package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class StrategyPnL(
    private val strategyPositions: StrategyPositionTracker,
    private val prices: MarketPriceProvider,
) {
    private val realizedByStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun recordRealized(
        strategyId: String,
        realized: BigDecimal,
    ) {
        if (strategyId.isBlank()) return
        val current = realizedByStrategy[strategyId] ?: Money.ZERO
        realizedByStrategy[strategyId] = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun realizedFor(strategyId: String): BigDecimal = realizedByStrategy[strategyId] ?: Money.ZERO

    fun unrealizedFor(
        strategyId: String,
        symbol: String,
    ): BigDecimal {
        val pos = strategyPositions.positionFor(strategyId, symbol) ?: return Money.ZERO
        val price = prices.lastPrice(symbol) ?: return Money.ZERO
        return price
            .subtract(pos.avgEntryPrice)
            .multiply(pos.quantity)
            .setScale(Money.SCALE, Money.ROUNDING)
    }

    fun unrealizedTotalFor(strategyId: String): BigDecimal =
        strategyPositions
            .positionsFor(strategyId)
            .keys
            .map { unrealizedFor(strategyId, it) }
            .fold(Money.ZERO) { acc, v -> acc.add(v) }
            .setScale(Money.SCALE, Money.ROUNDING)

    fun totalFor(strategyId: String): BigDecimal =
        realizedFor(strategyId)
            .add(unrealizedTotalFor(strategyId))
            .setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 3: Defer test verification to after T3** (test depends on `BrokerEvent.OrderFilled.strategyId`)

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/pnl/StrategyPnL.kt \
        src/test/kotlin/com/qkt/pnl/StrategyPnLTest.kt
git commit -m "feat(pnl): add StrategyPnL for per-strategy realized and unrealized"
```

---

## Task 3: Add `strategyId` to `BrokerEvent.OrderEvent` variants

**Files:**
- Modify: `src/main/kotlin/com/qkt/events/BrokerEvent.kt`
- Modify: every event constructor call site (mechanical bulk edit, ~30 files)

**This is a big mechanical change.** Adding `strategyId: String` to a sealed-interface marker forces every call site to provide it. We bulk-edit with default `strategyId = ""` to keep compile errors localized, then incrementally fill in real values in subsequent tasks.

- [ ] **Step 1: Add the field with default**

In `src/main/kotlin/com/qkt/events/BrokerEvent.kt`:

```kotlin
sealed interface OrderEvent : BrokerEvent {
    val clientOrderId: String
    val brokerOrderId: String?
    val strategyId: String                  // NEW
}

data class OrderAccepted(
    override val clientOrderId: String,
    override val brokerOrderId: String?,
    override val strategyId: String = "",   // NEW with default for migration
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : OrderEvent

// Same pattern for OrderRejected, OrderFilled, OrderPartiallyFilled, OrderCancelled
```

The default `""` keeps existing call sites compiling. We strip the default later (T11 cleanup).

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Default value lets every existing constructor compile unchanged.

- [ ] **Step 3: Run T1 + T2 tests now that the field exists**

Run: `./gradlew test --tests 'com.qkt.positions.StrategyPositionTrackerTest' --tests 'com.qkt.pnl.StrategyPnLTest'`
Expected: PASS, 8 tests green.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/events/BrokerEvent.kt
git commit -m "feat(events): add strategyId to BrokerEvent OrderEvent variants"
```

---

## Task 4: Add `strategyId` to `OrderRequest` sealed variants

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- Modify: `src/main/kotlin/com/qkt/execution/Signal.kt` (if `toOrderRequest` lives there) — or `Signals.kt` extension file

- [ ] **Step 1: Add `strategyId` to `OrderRequest`**

In `OrderRequest.kt`, add `strategyId: String = ""` to the abstract base + every variant. Use default for migration safety:

```kotlin
sealed class OrderRequest {
    abstract val id: String
    open val strategyId: String = ""        // NEW with default
    abstract val symbol: String
    // ...

    data class Market(
        override val id: String,
        override val strategyId: String = "",   // NEW
        override val symbol: String,
        // ...
    ) : OrderRequest()

    // Same pattern for Limit, Stop, StopLimit, IfTouched, Bracket, OCO, OTO, Trailing, ...
}
```

For composite orders (Bracket, OCO, OTO), the strategyId is on the parent; child requests inherit during decomposition (handled in T7).

- [ ] **Step 2: Add `withStrategyId` extension**

In `OrderRequest.kt` (bottom):

```kotlin
fun OrderRequest.withStrategyId(strategyId: String): OrderRequest =
    when (this) {
        is OrderRequest.Market -> copy(strategyId = strategyId)
        is OrderRequest.Limit -> copy(strategyId = strategyId)
        is OrderRequest.Stop -> copy(strategyId = strategyId)
        is OrderRequest.StopLimit -> copy(strategyId = strategyId)
        is OrderRequest.IfTouched -> copy(strategyId = strategyId)
        is OrderRequest.Bracket -> copy(strategyId = strategyId)
        is OrderRequest.OCO -> copy(strategyId = strategyId)
        is OrderRequest.OTO -> copy(strategyId = strategyId)
        is OrderRequest.Trailing -> copy(strategyId = strategyId)
    }
```

(Adjust to match the actual sealed variants in your codebase — run `grep -E '^[[:space:]]*data class' src/main/kotlin/com/qkt/execution/OrderRequest.kt`.)

- [ ] **Step 3: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Default value keeps existing call sites compiling.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/execution/OrderRequest.kt
git commit -m "feat(execution): add strategyId to OrderRequest sealed variants"
```

---

## Task 5: Update `Signal.toOrderRequest` to thread strategyId

**Files:**
- Modify: `src/main/kotlin/com/qkt/execution/Signal.kt` or the extension file housing `toOrderRequest`

- [ ] **Step 1: Locate the converter**

```bash
grep -rn 'fun.*toOrderRequest\|fun Signal.toOrderRequest' src/main/kotlin
```

- [ ] **Step 2: Add `strategyId` parameter**

Change signature:

```kotlin
fun Signal.toOrderRequest(
    id: String,
    strategyId: String,                    // NEW
    timestamp: Long,
): OrderRequest = when (this) {
    is Signal.Buy ->
        OrderRequest.Market(
            id = id,
            strategyId = strategyId,
            symbol = symbol,
            side = Side.BUY,
            quantity = size,
            timeInForce = TimeInForce.IOC,
            timestamp = timestamp,
        )
    is Signal.Sell ->
        OrderRequest.Market(
            id = id,
            strategyId = strategyId,
            symbol = symbol,
            side = Side.SELL,
            quantity = size,
            timeInForce = TimeInForce.IOC,
            timestamp = timestamp,
        )
    is Signal.Submit -> request.withStrategyId(strategyId)
}
```

- [ ] **Step 3: Update all callers**

```bash
grep -rn '\.toOrderRequest(' src/main/kotlin src/test/kotlin
```

Each call gets a `strategyId` argument. For existing call sites that don't have a strategy context, pass the order's `request.id` or `"default"` as a placeholder. Most callers are inside `TradingPipeline` or `Backtest` which thread the strategyId once T8 lands.

For now, mechanical sweep: pass empty string `""` to compile.

```bash
perl -i -pe 's/\.toOrderRequest\(([^,]+), ([^)]+)\)/.toOrderRequest($1, "", $2)/g' src/main/kotlin/com/qkt/app/*.kt src/test/kotlin/com/qkt/app/*.kt
```

(Test the regex on one file first; adjust as needed.)

- [ ] **Step 4: Run tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(execution): thread strategyId through Signal toOrderRequest"
```

---

## Task 6: Brokers thread `strategyId` from request to event

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/LogBroker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/CompositeBroker.kt` (probably no changes — delegates to wrapped brokers)
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitLinearBroker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotStateRecovery.kt`
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitLinearStateRecovery.kt`

For each broker:
1. Add `strategyByClientOrderId: MutableMap<String, String>` — mirrors `symbolByClientOrderId`.
2. On submit: `strategyByClientOrderId[request.id] = request.strategyId`.
3. On every emitted event: include `strategyId` from the map (or from the request).
4. On terminal events: prune both maps.

For Bybit recovery: extend `BybitSpotStateRecovery.ManagedOrderView` to include `strategyId`. Recovery-emitted `OrderCancelled` carries it.

- [ ] **Step 1: Update `BybitSpotStateRecovery.ManagedOrderView`**

```kotlin
data class ManagedOrderView(
    val clientOrderId: String,
    val symbol: String,
    val side: Side,
    val strategyId: String = "",            // NEW
)
```

Update both recovery files to thread strategyId on `OrderCancelled` emissions:

```kotlin
bus.publish(
    BrokerEvent.OrderCancelled(
        clientOrderId = id,
        brokerOrderId = null,
        strategyId = known[id]?.strategyId ?: "",   // NEW
        reason = "recovered: not in open list",
        timestamp = clock.now(),
    ),
)
```

Recovery-emitted `OrderFilled` (from execution list) doesn't have direct knowledge of strategyId. Use empty string + WARN log when the clientOrderId isn't in `getKnownOrders`. When it IS in known orders, use that strategyId.

- [ ] **Step 2: Update each broker's submit / cancel / WS handlers**

For `BybitSpotBroker`:
- Add `strategyByClientOrderId: MutableMap<String, String> = ConcurrentHashMap()`.
- In `submit`: after the parsed `ack.accepted`, do `strategyByClientOrderId[request.id] = request.strategyId`.
- In `parseSubmitResponse` and `onOrderFrame` and `onExecutionFrame`: look up `strategyByClientOrderId[clientOrderId] ?: ""` and include in emitted events.
- In pruning subscriptions, also remove from `strategyByClientOrderId`.
- Update `knownOrders` map to use the new `ManagedOrderView` shape with strategyId.

For `BybitLinearBroker`: same pattern.

For `LogBroker` and `PaperBroker`: simpler — just include `request.strategyId` directly in emitted events (no async layer; submit produces events synchronously).

- [ ] **Step 3: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Existing tests pass with default `strategyId = ""`.

- [ ] **Step 4: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add -A
git commit -m "feat(broker): thread strategyId from OrderRequest to BrokerEvent across brokers"
```

---

## Task 7: Bracket / OCO / OTO child propagation in `OrderManager`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (if it decomposes Tier 3 orders)

`OrderManager.dispatch` decomposes Bracket → OTO + OCO + child Limits/Stops. Each child should inherit parent's strategyId.

- [ ] **Step 1: Locate decomposition**

```bash
grep -n 'Bracket\|OCO\|OTO\|withStrategyId' src/main/kotlin/com/qkt/app/OrderManager.kt
```

- [ ] **Step 2: Apply `withStrategyId` to children**

Wherever children are constructed, copy parent strategyId:

```kotlin
val child = OrderRequest.Limit(
    id = "${parent.id}-tp",
    strategyId = parent.strategyId,         // NEW
    symbol = parent.symbol,
    // ...
)
```

- [ ] **Step 3: Run order-manager tests**

Run: `./gradlew test --tests 'com.qkt.app.OrderManager*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "feat(app): propagate parent strategyId to OrderManager-decomposed children"
```

---

## Task 8: Rename `SessionContext` → `StrategyContext` and add fields

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/SessionContext.kt` → rename + restructure
- Modify: `src/main/kotlin/com/qkt/strategy/Strategy.kt`
- Modify: `src/test/kotlin/com/qkt/strategy/TestSessionContext.kt` → rename + update
- Bulk edit: every `SessionContext` reference → `StrategyContext`
- Bulk edit: every `testSessionContext()` → `testStrategyContext()`

**Big bulk edit.** ~25 files.

- [ ] **Step 1: Define `StrategyPositionView` and `StrategyPnLView`**

```kotlin
// src/main/kotlin/com/qkt/positions/StrategyPositionView.kt
package com.qkt.positions

interface StrategyPositionView {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

internal class StrategyPositionViewImpl(
    private val tracker: StrategyPositionTracker,
    private val strategyId: String,
) : StrategyPositionView {
    override fun positionFor(symbol: String): Position? = tracker.positionFor(strategyId, symbol)

    override fun allPositions(): Map<String, Position> = tracker.positionsFor(strategyId)
}
```

```kotlin
// src/main/kotlin/com/qkt/pnl/StrategyPnLView.kt
package com.qkt.pnl

import java.math.BigDecimal

interface StrategyPnLView {
    fun realized(): BigDecimal

    fun unrealizedFor(symbol: String): BigDecimal

    fun unrealizedTotal(): BigDecimal

    fun total(): BigDecimal
}

internal class StrategyPnLViewImpl(
    private val pnl: StrategyPnL,
    private val strategyId: String,
) : StrategyPnLView {
    override fun realized(): BigDecimal = pnl.realizedFor(strategyId)

    override fun unrealizedFor(symbol: String): BigDecimal = pnl.unrealizedFor(strategyId, symbol)

    override fun unrealizedTotal(): BigDecimal = pnl.unrealizedTotalFor(strategyId)

    override fun total(): BigDecimal = pnl.totalFor(strategyId)
}
```

- [ ] **Step 2: Replace `SessionContext` with `StrategyContext`**

Delete `SessionContext.kt` and create `StrategyContext.kt`:

```kotlin
// src/main/kotlin/com/qkt/strategy/StrategyContext.kt
package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.StrategyPositionView

data class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val positions: StrategyPositionView,
    val pnl: StrategyPnLView,
)
```

- [ ] **Step 3: Update `Strategy.kt`**

```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(
        tick: Tick,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    )

    fun onCandle(
        candle: Candle,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
    ) {}
}
```

- [ ] **Step 4: Update test helper**

```bash
git mv src/test/kotlin/com/qkt/strategy/TestSessionContext.kt \
       src/test/kotlin/com/qkt/strategy/TestStrategyContext.kt
```

In the renamed file, return a `StrategyContext` with no-op view defaults:

```kotlin
package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import java.math.BigDecimal

private val emptySource =
    object : MarketSource {
        override val name = "Empty"
        override val capabilities = emptySet<MarketSourceCapability>()

        override fun supports(symbol: String): Boolean = false
    }

private val emptyPositions =
    object : StrategyPositionView {
        override fun positionFor(symbol: String): Position? = null

        override fun allPositions(): Map<String, Position> = emptyMap()
    }

private val emptyPnL =
    object : StrategyPnLView {
        override fun realized(): BigDecimal = BigDecimal.ZERO

        override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

        override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

        override fun total(): BigDecimal = BigDecimal.ZERO
    }

fun testStrategyContext(
    strategyId: String = "test",
    mode: Mode = Mode.BACKTEST,
    clock: Clock = FixedClock(time = 0L),
    calendar: TradingCalendar = TradingCalendar.crypto(),
    source: MarketSource = emptySource,
    positions: StrategyPositionView = emptyPositions,
    pnl: StrategyPnLView = emptyPnL,
): StrategyContext =
    StrategyContext(
        strategyId = strategyId,
        mode = mode,
        clock = clock,
        calendar = calendar,
        source = source,
        positions = positions,
        pnl = pnl,
    )
```

- [ ] **Step 5: Bulk-rename `SessionContext` → `StrategyContext` and `testSessionContext` → `testStrategyContext`**

```bash
find src -name '*.kt' -type f -exec perl -i -pe 's/\bSessionContext\b/StrategyContext/g; s/\btestSessionContext\b/testStrategyContext/g' {} +
```

- [ ] **Step 6: Run full test suite**

Run: `./gradlew clean test`

Expected: BUILD FAILED initially (anonymous Strategy implementations have `ctx: SessionContext` parameter that's now `StrategyContext` after rename — should be fine; check for residual references).

If failures persist: `grep -rn 'SessionContext' src` and inspect each match.

- [ ] **Step 7: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add -A
git commit -m "refactor(strategy): rename SessionContext to StrategyContext and add strategyId positions pnl"
```

---

## Task 9: `TradingPipeline` named registration + parallel trackers

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: tests that construct `TradingPipeline` directly

- [ ] **Step 1: Update `TradingPipeline` constructor**

Change the strategies parameter type and add new tracker fields:

```kotlin
class TradingPipeline(
    // ... existing args ...
    private val strategies: List<Pair<String, Strategy>>,
    private val strategyPositions: StrategyPositionTracker,
    private val strategyPnL: StrategyPnL,
    // ... rest ...
) {
    init {
        require(strategies.map { it.first }.toSet().size == strategies.size) {
            "Strategy IDs must be unique: ${strategies.map { it.first }}"
        }
        require(strategies.all { it.first.isNotBlank() }) {
            "Strategy ID must be non-blank"
        }

        // ... existing init logic ...

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
                )
            bus.subscribe<TickEvent> { e ->
                strategy.onTick(e.tick, ctx) { sig ->
                    val request = sig.toOrderRequest(ids.next(), strategyId, clock.now())
                    bus.publish(SignalEvent(sig))
                    bus.publish(OrderEvent(request))
                }
            }
            bus.subscribe<CandleEvent> { e ->
                strategy.onCandle(e.candle, ctx) { sig ->
                    val request = sig.toOrderRequest(ids.next(), strategyId, clock.now())
                    bus.publish(SignalEvent(sig))
                    bus.publish(OrderEvent(request))
                }
            }
        }

        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            // existing broker-truth path
            val realized = positions.applyFill(e)
            pnl.recordRealized(realized)

            // NEW per-strategy attribution path
            val stratRealized = strategyPositions.applyFill(e)
            strategyPnL.recordRealized(e.strategyId, stratRealized)

            // existing TradeEvent and onFilled callback ...
        }
    }
}
```

(Adapt to existing pipeline init structure — match the existing emit lambda used in 7g/7h.)

- [ ] **Step 2: Update `Backtest`, `LiveSession`, `Main`**

Each entry point now constructs `StrategyPositionTracker` and `StrategyPnL`, and passes named strategies to the pipeline.

```bash
grep -rn 'TradingPipeline(' src/main/kotlin/com/qkt/app
```

Update each construction site.

- [ ] **Step 3: Update tests**

```bash
grep -rn 'TradingPipeline(' src/test/kotlin
```

Each test constructs the pipeline. Add named strategies, `StrategyPositionTracker()`, `StrategyPnL(...)`.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: ktlintFormat + commit**

```bash
./gradlew ktlintFormat
git add -A
git commit -m "feat(app): TradingPipeline accepts named strategies and per-strategy trackers"
```

---

## Task 10: Tighten `strategyId` defaults — remove `= ""`

**Files:**
- Modify: `src/main/kotlin/com/qkt/events/BrokerEvent.kt`
- Modify: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`

The `= ""` default was a migration aid. Now that all callers pass real values, remove the default to make the field truly required.

- [ ] **Step 1: Remove `= ""` default from `BrokerEvent.OrderEvent` variants**

In each variant, change `override val strategyId: String = ""` to `override val strategyId: String`. Same for `OrderRequest` variants.

- [ ] **Step 2: Run tests; fix any remaining call sites**

```bash
./gradlew test 2>&1 | grep -E 'error:|e: ' | head -30
```

Each error indicates a missing `strategyId = ` argument. Fix by adding `strategyId = "test"` (test code) or `strategyId = request.strategyId` (production code).

Repeat compile-fix cycle until green.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: make strategyId required on OrderRequest and BrokerEvent OrderEvent"
```

---

## Task 11: Full check + demo invariant

- [ ] **Step 1: ktlintFormat + clean build**

```bash
./gradlew ktlintFormat
./gradlew clean check
```

Expected: BUILD SUCCESSFUL. ~600+ tests pass.

- [ ] **Step 2: Demo invariant**

```bash
./gradlew run 2>&1 | grep -cE 'FILLED|REJECTED'
```

Expected: `10`.

- [ ] **Step 3: Commit any reformats**

```bash
git status --short
git add -A
git commit -m "style: ktlintFormat after 8 additions" || true
```

---

## Task 12: Phase 8 changelog

**Files:**
- Create: `docs/phases/phase-8-strategy-context-and-pnl-attribution.md`

Use `phase-7h-derivatives-and-rate-limit.md` as template. Required sections per qkt skill §6:

1. **Summary** — 2-4 sentences.
2. **What's new** — bullet list covering: StrategyContext, StrategyPositionTracker, StrategyPnL, view interfaces, named registration, OrderRequest.strategyId, BrokerEvent.OrderEvent.strategyId, broker threading.
3. **Migration from previous phase** — table covering renamed types, new required fields, named registration.
4. **Usage cookbook** — at least 5 worked examples:
   - Read your own positions in a strategy via `ctx.positions`
   - Read your own PnL via `ctx.pnl`
   - Register multiple named strategies in a pipeline
   - Subscribe to PositionReconciled to detect drift
   - Per-strategy + per-broker reporting via symbol-prefix grouping
5. **Testing patterns** — `testStrategyContext()`, `StrategyPositionTracker.applyFill` with strategyId, view filtering.
6. **Known limitations** — drift between attribution and broker truth, no equity curve yet, no auto-correction, etc.
7. **References** — spec, plan, prior phase changelogs.

Aim 200-500 lines.

- [ ] **Step 1: Write changelog**

(Free-form; follow template.)

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-8-strategy-context-and-pnl-attribution.md
git commit -m "docs: add phase 8 changelog"
```

---

## Task 13: Final verification + finishing-a-development-branch

- [ ] **Step 1: Verify branch state**

```bash
git log --oneline main..HEAD
git status --short
./gradlew check
```

- [ ] **Step 2: Use finishing-a-development-branch skill**

Default for this project: option 1 (`--no-ff` merge to main, delete branch).

Merge commit: `merge: phase 8 strategy context and pnl attribution`.

Post-merge:

```bash
git checkout main
git log --oneline -5
./gradlew check
```

Expected: BUILD SUCCESSFUL.

---

## Self-Review Checklist

Before completing the plan:

- [ ] Every type / method referenced in a later task is defined in an earlier task.
- [ ] No "TBD", "TODO", "fill in", "similar to above" text in any step.
- [ ] Every code step shows actual code.
- [ ] Every test step has both code and verification command.
- [ ] Every commit step has the exact `git commit -m` line.
- [ ] Spec coverage:
  - Spec §5 StrategyContext → T8.
  - Spec §6 Strategy interface → T8.
  - Spec §7 TradingPipeline registration → T9.
  - Spec §8 OrderRequest.strategyId → T4.
  - Spec §9 BrokerEvent.OrderEvent.strategyId → T3, T6.
  - Spec §10 StrategyPositionTracker → T1.
  - Spec §11 StrategyPnL → T2.
  - Spec §12 Wiring → T9.
  - Spec §13 testing → all tasks.
  - Spec §18 migration → T12 changelog.
