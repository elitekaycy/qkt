# Phase 7e Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first real broker integration (Bybit Spot, testnet) and the long-deferred `CompositeBroker` so multiple brokers can be composed by symbol-pattern routing. Add `Broker.capabilitiesFor(symbol)` to the interface so `CompositeBroker` can report capabilities per-routed-broker.

**Architecture:** `CompositeBroker` follows `CompositeMarketSource`'s pattern (immutable construction-time routing list, `SymbolPattern` matching, fallback). Per-product Bybit broker classes (`BybitSpotBroker` ships in 7e; `BybitLinearBroker` etc. follow later) share a low-level `BybitClient` for HMAC-signed REST + private WebSocket. Symbol convention `EXCHANGE_PRODUCT:SYMBOL` (e.g., `BYBIT_SPOT:BTCUSDT`) is locked in. Default-to-testnet auth model; live trading requires explicit `BYBIT_TESTNET=false`.

**Tech Stack:** Kotlin, OkHttp (existing dep from Phase 7c), kotlinx-serialization-json (existing dep), JUnit 5, AssertJ, `javax.crypto.Mac` for HMAC-SHA256 (JDK built-in).

---

## File structure overview

### New files

```
src/main/kotlin/com/qkt/broker/
  CompositeBroker.kt                      # NEW: pattern-based router
  bybit/
    BybitSigner.kt                        # NEW: HMAC-SHA256 helper
    BybitSymbol.kt                        # NEW: prefix parser + native conversion
    BybitClient.kt                        # NEW: shared REST + WS transport
    BybitOrderTranslator.kt               # NEW: OrderRequest <-> Bybit fields
    BybitSpotBroker.kt                    # NEW: Broker impl for spot

src/test/kotlin/com/qkt/broker/
  CompositeBrokerTest.kt                  # NEW
  bybit/
    BybitSignerTest.kt                    # NEW
    BybitSymbolTest.kt                    # NEW
    FakeBybitClient.kt                    # NEW test fixture
    BybitOrderTranslatorTest.kt           # NEW
    BybitSpotBrokerTest.kt                # NEW
    BybitSpotLiveSmokeTest.kt             # NEW @Tag("e2e-live") manual

docs/phases/
  phase-7e-bybit-and-composite.md         # NEW phase changelog
```

### Modified files

```
src/main/kotlin/com/qkt/broker/Broker.kt            # add capabilitiesFor + supports defaults
src/main/kotlin/com/qkt/app/OrderManager.kt         # use capabilitiesFor(symbol) in dispatch
build.gradle.kts                                    # extend e2e tag exclusion to "e2e-live"
```

### Deleted files

None — 7e is purely additive.

---

## Task summary

| # | Group | Title |
|---|---|---|
| 1 | A | Add `Broker.capabilitiesFor(symbol)` and `supports(symbol)` defaulted methods |
| 2 | A | Update `OrderManager.dispatch()` to use `capabilitiesFor(request.symbol)` |
| 3 | B | Implement `CompositeBroker` (routing + cancel map + capabilitiesFor delegation + bus pruning) |
| 4 | B | `CompositeBrokerTest` (routing, cancel, fallback, capabilities, ordering, pruning) |
| 5 | C | `BybitSigner` (HMAC-SHA256 helper) + tests |
| 6 | C | `BybitSymbol` (prefix parser + native conversion) + tests |
| 7 | C | `BybitClient` REST signing path + tests |
| 8 | C | `BybitClient` private WebSocket lifecycle (auth + topic subscribe + heartbeat) |
| 9 | C | `FakeBybitClient` test fixture |
| 10 | D | `BybitOrderTranslator` (OrderRequest -> Bybit body params; reverse for events) + tests |
| 11 | D | `BybitSpotBroker.submit` for Market and Limit + tests |
| 12 | D | `BybitSpotBroker.submit` for Stop, StopLimit, IfTouched + tests |
| 13 | D | `BybitSpotBroker.cancel` + tests |
| 14 | D | `BybitSpotBroker` WS event translation (`order` + `execution` topics) + tests |
| 15 | D | `BybitSpotBroker.modify` + test |
| 16 | E | `build.gradle.kts` extend tag exclusion to include `e2e-live` |
| 17 | E | `BybitSpotLiveSmokeTest` (manual `@Tag("e2e-live")`) |
| 18 | F | Full build + verify all Phase 7d tests pass with `capabilitiesFor` switch |
| 19 | F | Verify `./gradlew run` produces 10 FILLED+REJECTED (Phase 7 invariant preserved) |
| 20 | F | Phase 7e changelog at `docs/phases/phase-7e-bybit-and-composite.md` |
| 21 | F | Final verification + branch state check |

Cumulative test counts (rough):

| After task | Δ tests | Cumulative |
|---|---|---|
| Pre-7e baseline (from 7d-b) | — | 461 |
| 4 (CompositeBroker)         | +7 | 468 |
| 5 (BybitSigner)             | +3 | 471 |
| 6 (BybitSymbol)             | +5 | 476 |
| 7 (BybitClient REST)        | +4 | 480 |
| 9 (FakeBybitClient)         | 0  | 480 |
| 10 (Translator)             | +8 | 488 |
| 14 (BybitSpotBroker total)  | +12 | 500 |

Final target: **~500 tests** (excluding `@Tag("e2e-live")`). Rough; ±10 expected.

---

## Group A: Broker interface refinement

### Task 1: Add `capabilitiesFor(symbol)` and `supports(symbol)` defaulted methods to `Broker`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/Broker.kt`

The `Broker` interface gets two new defaulted methods. Existing impls (LogBroker, PaperBroker) inherit defaults; no behavior change.

- [ ] **Step 1: Read current `Broker.kt`**

Run: `cat src/main/kotlin/com/qkt/broker/Broker.kt`

Confirm the current shape:
```kotlin
interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>
    fun submit(request: OrderRequest): SubmitAck
    fun cancel(orderId: String)
    fun modify(orderId: String, changes: OrderModification): SubmitAck =
        throw UnsupportedOperationException("$name does not support modify")
}
data class OrderModification(...)
```

- [ ] **Step 2: Add the two defaulted methods**

Edit `src/main/kotlin/com/qkt/broker/Broker.kt`:

Insert these two methods after `val capabilities` and before `fun submit`:

```kotlin
    /**
     * Per-symbol capability query. Defaults to the flat `capabilities` set.
     * `CompositeBroker` overrides this to delegate to the routed sub-broker.
     */
    fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> = capabilities

    /**
     * Whether this broker handles the given symbol. Default: yes (used by tests and audits
     * that expect a "takes anything" broker). `CompositeBroker` does NOT consult this for
     * routing; routing is via the explicit `routes` list. `supports()` is informational.
     */
    fun supports(symbol: String): Boolean = true
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all existing tests to verify no regression**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. All Phase 7d tests pass unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/Broker.kt
git commit -m "feat(broker): add capabilitiesFor and supports defaulted methods"
```

---

### Task 2: Update `OrderManager.dispatch()` to use `capabilitiesFor(request.symbol)`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`

OrderManager currently checks `OrderTypeCapability.X in broker.capabilities`. Switch to `broker.capabilitiesFor(request.symbol)`. This is required for `CompositeBroker` to route capability checks correctly.

- [ ] **Step 1: Locate the dispatch method**

Run: `grep -n 'broker.capabilities' src/main/kotlin/com/qkt/app/OrderManager.kt`
Expected: 4 occurrences in `dispatch()` (one each for STOP, STOP_LIMIT, IF_TOUCHED, BRACKET).

- [ ] **Step 2: Replace each `broker.capabilities` with `broker.capabilitiesFor(request.symbol)`**

In `OrderManager.kt`, the `dispatch(request: OrderRequest): SubmitAck` method has 4 places. Replace:

```kotlin
            is OrderRequest.Stop ->
                if (OrderTypeCapability.STOP in broker.capabilities) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }
```

with:

```kotlin
            is OrderRequest.Stop ->
                if (OrderTypeCapability.STOP in broker.capabilitiesFor(request.symbol)) {
                    submitToBroker(request)
                } else {
                    holdPending(request)
                }
```

Same change for `StopLimit`, `IfTouched`, and `Bracket` (in the Bracket branch the symbol is `request.symbol` from the outer Bracket, not the entry's symbol — they're the same in well-formed orders).

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run all tests**

Run: `./gradlew test`
Expected: 461 tests pass — Phase 7d-b baseline. The default impl makes existing brokers behave identically.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "refactor(execution): OrderManager uses capabilitiesFor(symbol) in dispatch"
```

---

## Group B: CompositeBroker

### Task 3: Implement `CompositeBroker`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/CompositeBroker.kt`

`CompositeBroker` routes per-symbol via patterns, tracks `clientOrderId -> Broker` for cancel routing, prunes the map on terminal events.

- [ ] **Step 1: Write `CompositeBroker.kt`**

`src/main/kotlin/com/qkt/broker/CompositeBroker.kt`:

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.source.SymbolPattern
import org.slf4j.LoggerFactory

class CompositeBroker(
    private val routes: List<Pair<SymbolPattern, Broker>>,
    private val fallback: Broker? = null,
    bus: EventBus? = null,
) : Broker {
    private val log = LoggerFactory.getLogger(CompositeBroker::class.java)

    override val name: String = "Composite"

    override val capabilities: Set<OrderTypeCapability>
        get() = error("CompositeBroker.capabilities is symbol-dependent; use capabilitiesFor(symbol)")

    override fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> =
        brokerFor(symbol)?.capabilitiesFor(symbol) ?: emptySet()

    override fun supports(symbol: String): Boolean = brokerFor(symbol) != null

    private val orderIdToBroker: MutableMap<String, Broker> = mutableMapOf()

    init {
        bus?.let { b ->
            b.subscribe<BrokerEvent.OrderFilled> { e -> orderIdToBroker.remove(e.clientOrderId) }
            b.subscribe<BrokerEvent.OrderCancelled> { e -> orderIdToBroker.remove(e.clientOrderId) }
            b.subscribe<BrokerEvent.OrderRejected> { e -> orderIdToBroker.remove(e.clientOrderId) }
        }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        val target =
            brokerFor(request.symbol)
                ?: return SubmitAck(
                    clientOrderId = request.id,
                    brokerOrderId = null,
                    accepted = false,
                    rejectReason = "no broker for ${request.symbol}",
                )
        orderIdToBroker[request.id] = target
        return target.submit(request)
    }

    override fun cancel(orderId: String) {
        val target = orderIdToBroker[orderId]
        if (target == null) {
            log.warn("CompositeBroker.cancel: unknown orderId={}", orderId)
            return
        }
        target.cancel(orderId)
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val target = orderIdToBroker[orderId]
        return if (target == null) {
            SubmitAck(
                clientOrderId = orderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "unknown orderId $orderId",
            )
        } else {
            target.modify(orderId, changes)
        }
    }

    private fun brokerFor(symbol: String): Broker? =
        routes.firstOrNull { (pattern, _) -> pattern.matches(symbol) }?.second ?: fallback
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Stage; commit with tests in next task**

---

### Task 4: `CompositeBrokerTest`

**Files:**
- Create: `src/test/kotlin/com/qkt/broker/CompositeBrokerTest.kt`

- [ ] **Step 1: Write the test file**

`src/test/kotlin/com/qkt/broker/CompositeBrokerTest.kt`:

```kotlin
package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.source.SymbolPattern
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CompositeBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun marketReq(
        id: String,
        symbol: String,
    ) = OrderRequest.Market(
        id = id,
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `submit routes to broker matching symbol pattern`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to brokerA,
                        SymbolPattern.prefix("B:") to brokerB,
                    ),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        composite.submit(marketReq("c2", "B:Y"))

        assertThat(brokerA.submits.map { it.id }).containsExactly("c1")
        assertThat(brokerB.submits.map { it.id }).containsExactly("c2")
    }

    @Test
    fun `unmatched symbol routes to fallback`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val fallback = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                fallback = fallback,
                bus = bus,
            )

        composite.submit(marketReq("c1", "Z:UNMATCHED"))

        assertThat(brokerA.submits).isEmpty()
        assertThat(fallback.submits.map { it.id }).containsExactly("c1")
    }

    @Test
    fun `unmatched symbol with no fallback returns rejected SubmitAck`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                fallback = null,
                bus = bus,
            )

        val ack = composite.submit(marketReq("c1", "Z:UNMATCHED"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("no broker")
    }

    @Test
    fun `cancel routes to the broker that accepted the order`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to brokerA,
                        SymbolPattern.prefix("B:") to brokerB,
                    ),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        composite.cancel("c1")

        assertThat(brokerA.cancels).containsExactly("c1")
        assertThat(brokerB.cancels).isEmpty()
    }

    @Test
    fun `capabilitiesFor delegates to the routed sub-broker`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.STOP))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("A:") to brokerA,
                        SymbolPattern.prefix("B:") to brokerB,
                    ),
                bus = bus,
            )

        assertThat(composite.capabilitiesFor("A:X")).contains(OrderTypeCapability.LIMIT)
        assertThat(composite.capabilitiesFor("A:X")).doesNotContain(OrderTypeCapability.STOP)
        assertThat(composite.capabilitiesFor("B:Y")).contains(OrderTypeCapability.STOP)
        assertThat(composite.capabilitiesFor("B:Y")).doesNotContain(OrderTypeCapability.LIMIT)
        assertThat(composite.capabilitiesFor("UNMATCHED")).isEmpty()
    }

    @Test
    fun `flat capabilities throws because composite is symbol-dependent`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                bus = bus,
            )
        assertThatThrownBy { composite.capabilities }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `first matching route wins`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val brokerB = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes =
                    listOf(
                        SymbolPattern.prefix("X:") to brokerA,
                        SymbolPattern.prefix("X:") to brokerB,
                    ),
                bus = bus,
            )

        composite.submit(marketReq("c1", "X:Y"))

        assertThat(brokerA.submits).hasSize(1)
        assertThat(brokerB.submits).isEmpty()
    }

    @Test
    fun `terminal events prune the orderId-to-broker map`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val brokerA = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val composite =
            CompositeBroker(
                routes = listOf(SymbolPattern.prefix("A:") to brokerA),
                bus = bus,
            )

        composite.submit(marketReq("c1", "A:X"))
        // Synthesize a fill event on the bus
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "A:X",
                side = Side.BUY,
                price = Money.of("1"),
                quantity = Money.of("1"),
            ),
        )

        // Cancel after fill should now be a no-op (map pruned)
        composite.cancel("c1")
        assertThat(brokerA.cancels).isEmpty()
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "com.qkt.broker.CompositeBrokerTest"`
Expected: 7 tests PASS.

- [ ] **Step 3: Run full suite (regression check)**

Run: `./gradlew test`
Expected: ~468 tests pass.

- [ ] **Step 4: Commit Tasks 3 + 4 together**

```bash
git add src/main/kotlin/com/qkt/broker/CompositeBroker.kt src/test/kotlin/com/qkt/broker/CompositeBrokerTest.kt
git commit -m "feat(broker): add CompositeBroker with pattern-based routing"
```

---

## Group C: Bybit core

### Task 5: `BybitSigner` (HMAC-SHA256 helper)

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitSigner.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitSignerTest.kt`

Tiny helper that wraps `javax.crypto.Mac` for HMAC-SHA256 hex output.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/broker/bybit/BybitSignerTest.kt`:

```kotlin
package com.qkt.broker.bybit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSignerTest {
    @Test
    fun `produces deterministic HMAC-SHA256 hex`() {
        val signer = BybitSigner(secret = "test-secret")
        val sig = signer.signHex("hello world")
        // expected hex of HMAC-SHA256("test-secret", "hello world")
        assertThat(sig).isEqualTo("8ee14ed7c0f2acdf5e58fd9bdf04e1ee2a4ad5b5097a06e1c1d83a4ef7a0a09c")
    }

    @Test
    fun `different inputs produce different signatures`() {
        val signer = BybitSigner(secret = "k")
        val a = signer.signHex("a")
        val b = signer.signHex("b")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `same input always produces same signature`() {
        val signer = BybitSigner(secret = "k")
        val a = signer.signHex("payload")
        val b = signer.signHex("payload")
        assertThat(a).isEqualTo(b)
    }
}
```

Note: the exact hex in test 1 is the actual `HMAC-SHA256("test-secret", "hello world")` value. If your implementation produces a different value, verify against an independent tool (e.g., `echo -n "hello world" | openssl dgst -sha256 -hmac "test-secret"`).

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSignerTest"`
Expected: compile failure (`Unresolved reference: BybitSigner`).

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/broker/bybit/BybitSigner.kt`:

```kotlin
package com.qkt.broker.bybit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitSigner(
    private val secret: String,
) {
    fun signHex(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSignerTest"`
Expected: 3 tests PASS.

If test 1 fails because the hardcoded hex is wrong, run:
```
echo -n "hello world" | openssl dgst -sha256 -hmac "test-secret"
```
Update the test with the correct value.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitSigner.kt src/test/kotlin/com/qkt/broker/bybit/BybitSignerTest.kt
git commit -m "feat(broker): add BybitSigner HMAC-SHA256 helper"
```

---

### Task 6: `BybitSymbol` (prefix parser + native conversion)

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitSymbol.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitSymbolTest.kt`

Translates between qkt symbols (`BYBIT_SPOT:BTCUSDT`) and Bybit native symbols (`BTCUSDT`) plus categories.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/broker/bybit/BybitSymbolTest.kt`:

```kotlin
package com.qkt.broker.bybit

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitSymbolTest {
    @Test
    fun `splits BYBIT_SPOT prefix into category and bare symbol`() {
        val parsed = BybitSymbol.parse("BYBIT_SPOT:BTCUSDT")
        assertThat(parsed.category).isEqualTo("spot")
        assertThat(parsed.bare).isEqualTo("BTCUSDT")
    }

    @Test
    fun `splits BYBIT_LINEAR prefix into linear category`() {
        val parsed = BybitSymbol.parse("BYBIT_LINEAR:BTCUSDT")
        assertThat(parsed.category).isEqualTo("linear")
    }

    @Test
    fun `splits BYBIT_INVERSE prefix into inverse category`() {
        val parsed = BybitSymbol.parse("BYBIT_INVERSE:BTCUSD")
        assertThat(parsed.category).isEqualTo("inverse")
    }

    @Test
    fun `rejects symbol without a recognized Bybit prefix`() {
        assertThatThrownBy { BybitSymbol.parse("OANDA:EURUSD") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `re-prefix builds the qkt symbol from a native bare symbol and category`() {
        assertThat(BybitSymbol.toQkt(category = "spot", bare = "BTCUSDT")).isEqualTo("BYBIT_SPOT:BTCUSDT")
        assertThat(BybitSymbol.toQkt(category = "linear", bare = "ETHUSDT")).isEqualTo("BYBIT_LINEAR:ETHUSDT")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSymbolTest"`
Expected: `Unresolved reference: BybitSymbol`.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/broker/bybit/BybitSymbol.kt`:

```kotlin
package com.qkt.broker.bybit

object BybitSymbol {
    private val CATEGORY_BY_PREFIX: Map<String, String> =
        mapOf(
            "BYBIT_SPOT:" to "spot",
            "BYBIT_LINEAR:" to "linear",
            "BYBIT_INVERSE:" to "inverse",
            "BYBIT_OPTION:" to "option",
        )

    private val PREFIX_BY_CATEGORY: Map<String, String> =
        CATEGORY_BY_PREFIX.entries.associate { (k, v) -> v to k }

    data class Parsed(
        val category: String,
        val bare: String,
    )

    fun parse(qktSymbol: String): Parsed {
        val entry =
            CATEGORY_BY_PREFIX.entries.firstOrNull { qktSymbol.startsWith(it.key) }
                ?: throw IllegalArgumentException("Not a recognized Bybit symbol: $qktSymbol")
        val bare = qktSymbol.removePrefix(entry.key)
        return Parsed(category = entry.value, bare = bare)
    }

    fun toQkt(
        category: String,
        bare: String,
    ): String {
        val prefix = PREFIX_BY_CATEGORY[category] ?: error("Unknown Bybit category: $category")
        return "$prefix$bare"
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSymbolTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitSymbol.kt src/test/kotlin/com/qkt/broker/bybit/BybitSymbolTest.kt
git commit -m "feat(broker): add BybitSymbol prefix parser"
```

---

### Task 7: `BybitClient` REST signing path

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt`

This task implements the REST half. WebSocket comes in Task 8.

- [ ] **Step 1: Write `BybitClient.kt` (REST only for now; WS skeleton)**

`src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt`:

```kotlin
package com.qkt.broker.bybit

import com.qkt.common.Clock
import com.qkt.common.SystemClock
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory

class BybitApiException(
    val retCode: Int,
    val retMsg: String,
) : RuntimeException("Bybit retCode=$retCode retMsg=$retMsg")

class BybitClient(
    apiKey: String? = null,
    apiSecret: String? = null,
    testnet: Boolean? = null,
    recvWindowMs: Long? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val clock: Clock = SystemClock(),
) {
    private val log = LoggerFactory.getLogger(BybitClient::class.java)

    private val resolvedApiKey: String =
        apiKey
            ?: System.getenv("BYBIT_API_KEY")
            ?: error("Bybit API key required: pass apiKey=... or set BYBIT_API_KEY env var")

    private val resolvedApiSecret: String =
        apiSecret
            ?: System.getenv("BYBIT_API_SECRET")
            ?: error("Bybit API secret required: pass apiSecret=... or set BYBIT_API_SECRET env var")

    private val resolvedTestnet: Boolean =
        testnet
            ?: (System.getenv("BYBIT_TESTNET")?.equals("false", ignoreCase = true)?.let { !it })
            ?: true

    private val resolvedRecvWindowMs: Long =
        recvWindowMs
            ?: System.getenv("BYBIT_RECV_WINDOW_MS")?.toLongOrNull()
            ?: 5_000L

    private val signer = BybitSigner(resolvedApiSecret)

    val restBaseUrl: String =
        if (resolvedTestnet) "https://api-testnet.bybit.com" else "https://api.bybit.com"

    val wsPrivateUrl: String =
        if (resolvedTestnet) {
            "wss://stream-testnet.bybit.com/v5/private"
        } else {
            "wss://stream.bybit.com/v5/private"
        }

    /**
     * Sign and POST a JSON body to a Bybit V5 endpoint. Returns the response body string
     * (caller parses the envelope).
     *
     * Throws BybitApiException if the HTTP layer succeeds but the response body has retCode != 0.
     */
    fun postSigned(
        path: String,
        jsonBody: String,
    ): String {
        val timestamp = clock.now().toString()
        val recvWindow = resolvedRecvWindowMs.toString()
        val preSign = timestamp + resolvedApiKey + recvWindow + jsonBody
        val signature = signer.signHex(preSign)

        val req =
            Request
                .Builder()
                .url("$restBaseUrl$path")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("X-BAPI-API-KEY", resolvedApiKey)
                .addHeader("X-BAPI-TIMESTAMP", timestamp)
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-RECV-WINDOW", recvWindow)
                .build()

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: error("empty Bybit response (HTTP ${resp.code})")
            if (!resp.isSuccessful) {
                throw BybitApiException(retCode = resp.code, retMsg = "HTTP ${resp.code}: $body")
            }
            return body
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: No commit yet** — WS lifecycle (Task 8) and FakeBybitClient (Task 9) commit together.

---

### Task 8: `BybitClient` private WebSocket lifecycle

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt`

Add the private WS connection: auth on connect, subscribe ops, topic dispatch, ping heartbeat.

- [ ] **Step 1: Extend `BybitClient` with WS support**

Append to `BybitClient.kt`. Add these imports:

```kotlin
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
```

Inside the `BybitClient` class, add these members:

```kotlin
    private val wsRef: AtomicReference<WebSocket?> = AtomicReference(null)
    private val topicListeners: MutableMap<String, MutableList<(JsonObject) -> Unit>> = mutableMapOf()
    private val onDisconnectListeners: MutableList<(String) -> Unit> = CopyOnWriteArrayList()
    private val pingExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "bybit-ws-ping").apply { isDaemon = true }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSubscribeTopics: MutableSet<String> = mutableSetOf()

    fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    ) {
        synchronized(topicListeners) {
            topicListeners.getOrPut(topic) { mutableListOf() }.add(listener)
            pendingSubscribeTopics.add(topic)
        }
    }

    fun onDisconnect(handler: (String) -> Unit) {
        onDisconnectListeners.add(handler)
    }

    fun connect() {
        if (wsRef.get() != null) return // idempotent

        val req = Request.Builder().url(wsPrivateUrl).build()
        val ws =
            httpClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        sendAuth(webSocket)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        onWsMessage(text)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        log.warn("Bybit WS onFailure: {}", t.message)
                        onWsDisconnect("failure: ${t.message}")
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        log.info("Bybit WS onClosed: code={} reason={}", code, reason)
                        onWsDisconnect("closed: $code $reason")
                    }
                },
            )
        wsRef.set(ws)
        startPingScheduler()
    }

    fun close() {
        pingExecutor.shutdownNow()
        wsRef.getAndSet(null)?.close(1000, "client close")
    }

    private fun sendAuth(ws: WebSocket) {
        val expires = clock.now() + 10_000
        val toSign = "GET/realtime$expires"
        val signature = signer.signHex(toSign)
        val authMsg =
            """{"op":"auth","args":["$resolvedApiKey",$expires,"$signature"]}"""
        ws.send(authMsg)
        // After auth, send any pending subscriptions
        synchronized(topicListeners) {
            if (pendingSubscribeTopics.isNotEmpty()) {
                val args = pendingSubscribeTopics.joinToString(",") { "\"$it\"" }
                ws.send("""{"op":"subscribe","args":[$args]}""")
            }
        }
    }

    private fun onWsMessage(text: String) {
        val tree = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val topic = tree["topic"]?.jsonPrimitive?.content
        if (topic != null) {
            synchronized(topicListeners) {
                topicListeners[topic]?.forEach { runCatching { it(tree) } }
            }
        }
        // op messages (auth/subscribe acks) are logged but not dispatched
        val op = tree["op"]?.jsonPrimitive?.content
        if (op != null) {
            log.debug("Bybit WS op response: {}", text)
        }
    }

    private fun onWsDisconnect(reason: String) {
        wsRef.set(null)
        onDisconnectListeners.forEach { runCatching { it(reason) } }
    }

    private fun startPingScheduler() {
        pingExecutor.scheduleAtFixedRate(
            {
                wsRef.get()?.send("""{"op":"ping"}""")
            },
            20_000L,
            20_000L,
            TimeUnit.MILLISECONDS,
        )
    }
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: No commit yet** — Task 9 ships `FakeBybitClient` and we commit together.

---

### Task 9: `FakeBybitClient` test fixture

**Files:**
- Create: `src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt`

A test-side `BybitClient`-like surface that lets tests record REST calls and emit WS frames programmatically. Cleanest approach: introduce an interface that both real and fake share. We'll do that minimally — extract the surface used by `BybitSpotBroker` into an interface `BybitTransport`.

- [ ] **Step 1: Extract `BybitTransport` interface from `BybitClient`**

Edit `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt`. Above the `BybitClient` class, add:

```kotlin
interface BybitTransport {
    fun postSigned(
        path: String,
        jsonBody: String,
    ): String

    fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    )

    fun onDisconnect(handler: (String) -> Unit)
}
```

Then make `BybitClient` implement it:

```kotlin
class BybitClient(
    ...
) : BybitTransport {
```

`postSigned`, `subscribe`, `onDisconnect` already match. Add `override` to each. (Be careful that `connect()` and `close()` are NOT in the interface — they're lifecycle calls only the real client needs.)

- [ ] **Step 2: Write `FakeBybitClient`**

`src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt`:

```kotlin
package com.qkt.broker.bybit

import kotlinx.serialization.json.JsonObject

class FakeBybitClient : BybitTransport {
    data class Posted(
        val path: String,
        val body: String,
    )

    val posts: MutableList<Posted> = mutableListOf()

    /** Map of path -> response body to return. Set in test to simulate Bybit's reply. */
    val responses: MutableMap<String, String> = mutableMapOf()

    private val topicListeners: MutableMap<String, MutableList<(JsonObject) -> Unit>> = mutableMapOf()
    private val disconnectListeners: MutableList<(String) -> Unit> = mutableListOf()

    override fun postSigned(
        path: String,
        jsonBody: String,
    ): String {
        posts.add(Posted(path, jsonBody))
        return responses[path]
            ?: """{"retCode":0,"retMsg":"OK","result":{}}"""
    }

    override fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    ) {
        topicListeners.getOrPut(topic) { mutableListOf() }.add(listener)
    }

    override fun onDisconnect(handler: (String) -> Unit) {
        disconnectListeners.add(handler)
    }

    /** Test-side: emit a frame to all listeners on the given topic. */
    fun emitWsFrame(
        topic: String,
        json: JsonObject,
    ) {
        topicListeners[topic]?.forEach { it(json) }
    }

    /** Test-side: trigger disconnect callbacks. */
    fun emitDisconnect(reason: String) {
        disconnectListeners.forEach { it(reason) }
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit Tasks 7 + 8 + 9 together**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt
git commit -m "feat(broker): add BybitClient with REST signing WS lifecycle and BybitTransport"
```

---

## Group D: BybitSpotBroker

### Task 10: `BybitOrderTranslator`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitOrderTranslatorTest.kt`

Pure functions translating between `OrderRequest` types and Bybit V5 fields, plus reverse translation for `order` and `execution` WS frames. Pure — no transport, no state.

- [ ] **Step 1: Write tests**

`src/test/kotlin/com/qkt/broker/bybit/BybitOrderTranslatorTest.kt`:

```kotlin
package com.qkt.broker.bybit

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitOrderTranslatorTest {
    @Test
    fun `Market BUY translates to spot category market buy`() {
        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"category\":\"spot\"")
        assertThat(body).contains("\"symbol\":\"BTCUSDT\"")
        assertThat(body).contains("\"side\":\"Buy\"")
        assertThat(body).contains("\"orderType\":\"Market\"")
        assertThat(body).contains("\"orderLinkId\":\"c1\"")
        assertThat(body).contains("\"timeInForce\":\"GTC\"")
    }

    @Test
    fun `Limit translates with price`() {
        val req =
            OrderRequest.Limit(
                id = "c2",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Limit\"")
        assertThat(body).contains("\"price\":\"80000")
    }

    @Test
    fun `Stop SELL translates with triggerPrice and triggerDirection 2`() {
        val req =
            OrderRequest.Stop(
                id = "c3",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("79000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Market\"")
        assertThat(body).contains("\"triggerPrice\":\"79000")
        assertThat(body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `StopLimit BUY translates with triggerPrice limitPrice and triggerDirection 1`() {
        val req =
            OrderRequest.StopLimit(
                id = "c4",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("85000"),
                limitPrice = Money.of("85100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Limit\"")
        assertThat(body).contains("\"triggerPrice\":\"85000")
        assertThat(body).contains("\"price\":\"85100")
        assertThat(body).contains("\"triggerDirection\":1")
    }

    @Test
    fun `IfTouched BUY MARKET translates with opposite trigger direction`() {
        val req =
            OrderRequest.IfTouched(
                id = "c5",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                triggerPrice = Money.of("75000"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"orderType\":\"Market\"")
        assertThat(body).contains("\"triggerPrice\":\"75000")
        // BUY IfTouched: fall-to-trigger -> direction 2
        assertThat(body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `cancel body contains orderLinkId and category`() {
        val body = BybitOrderTranslator.toCancelBody(symbol = "BYBIT_SPOT:BTCUSDT", orderLinkId = "c1")
        assertThat(body).contains("\"category\":\"spot\"")
        assertThat(body).contains("\"symbol\":\"BTCUSDT\"")
        assertThat(body).contains("\"orderLinkId\":\"c1\"")
    }

    @Test
    fun `DAY tif maps to GTC on spot`() {
        val req =
            OrderRequest.Limit(
                id = "c6",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.DAY,
                timestamp = 0L,
            )
        val body = BybitOrderTranslator.toCreateBody(req)
        assertThat(body).contains("\"timeInForce\":\"GTC\"")
    }

    @Test
    fun `IOC and FOK pass through directly`() {
        val req1 =
            OrderRequest.Limit(
                id = "c7",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.IOC,
                timestamp = 0L,
            )
        assertThat(BybitOrderTranslator.toCreateBody(req1)).contains("\"timeInForce\":\"IOC\"")

        val req2 = req1.copy(id = "c8", timeInForce = TimeInForce.FOK)
        assertThat(BybitOrderTranslator.toCreateBody(req2)).contains("\"timeInForce\":\"FOK\"")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitOrderTranslatorTest"`
Expected: `Unresolved reference: BybitOrderTranslator`.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt`:

```kotlin
package com.qkt.broker.bybit

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType

object BybitOrderTranslator {
    /**
     * Translate an OrderRequest to a Bybit V5 /v5/order/create request body (JSON string).
     *
     * Note: Bybit Spot does not support DAY natively — we map it to GTC and document.
     */
    fun toCreateBody(request: OrderRequest): String {
        val parsed = BybitSymbol.parse(request.symbol)
        val side = if (request.side == Side.BUY) "Buy" else "Sell"
        val tif = mapTif(request.timeInForce)

        val (orderType, priceField, triggerFields) =
            when (request) {
                is OrderRequest.Market -> Triple("Market", null, null)
                is OrderRequest.Limit -> Triple("Limit", request.limitPrice.toPlainString(), null)
                is OrderRequest.Stop ->
                    Triple(
                        "Market",
                        null,
                        TriggerSet(request.stopPrice.toPlainString(), stopDirection(request.side)),
                    )
                is OrderRequest.StopLimit ->
                    Triple(
                        "Limit",
                        request.limitPrice.toPlainString(),
                        TriggerSet(request.stopPrice.toPlainString(), stopDirection(request.side)),
                    )
                is OrderRequest.IfTouched -> {
                    val ot = if (request.onTrigger == TriggerType.MARKET) "Market" else "Limit"
                    val limitField = if (request.onTrigger == TriggerType.LIMIT) request.limitPrice!!.toPlainString() else null
                    Triple(
                        ot,
                        limitField,
                        TriggerSet(request.triggerPrice.toPlainString(), ifTouchedDirection(request.side)),
                    )
                }
                else -> error("BybitOrderTranslator does not handle ${request::class.simpleName} (Tier 3 belongs in OrderManager)")
            }

        val sb = StringBuilder("{")
        sb.append("\"category\":\"${parsed.category}\",")
        sb.append("\"symbol\":\"${parsed.bare}\",")
        sb.append("\"side\":\"$side\",")
        sb.append("\"orderType\":\"$orderType\",")
        sb.append("\"qty\":\"${request.quantity.toPlainString()}\",")
        if (priceField != null) sb.append("\"price\":\"$priceField\",")
        if (triggerFields != null) {
            sb.append("\"triggerPrice\":\"${triggerFields.price}\",")
            sb.append("\"triggerDirection\":${triggerFields.direction},")
        }
        sb.append("\"timeInForce\":\"$tif\",")
        sb.append("\"orderLinkId\":\"${request.id}\"")
        sb.append("}")
        return sb.toString()
    }

    fun toCancelBody(
        symbol: String,
        orderLinkId: String,
    ): String {
        val parsed = BybitSymbol.parse(symbol)
        return """{"category":"${parsed.category}","symbol":"${parsed.bare}","orderLinkId":"$orderLinkId"}"""
    }

    private fun mapTif(tif: TimeInForce): String =
        when (tif) {
            TimeInForce.GTC, TimeInForce.DAY -> "GTC" // Bybit Spot has no DAY
            TimeInForce.IOC -> "IOC"
            TimeInForce.FOK -> "FOK"
        }

    /** BUY Stop: rise above (1). SELL Stop: fall below (2). */
    private fun stopDirection(side: Side): Int = if (side == Side.BUY) 1 else 2

    /** BUY IfTouched: fall to trigger (2). SELL IfTouched: rise to trigger (1). */
    private fun ifTouchedDirection(side: Side): Int = if (side == Side.BUY) 2 else 1

    private data class TriggerSet(
        val price: String,
        val direction: Int,
    )
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitOrderTranslatorTest"`
Expected: 8 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt src/test/kotlin/com/qkt/broker/bybit/BybitOrderTranslatorTest.kt
git commit -m "feat(broker): add BybitOrderTranslator for Spot order types"
```

---

### Task 11: `BybitSpotBroker.submit` for Market and Limit

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt`

Build the broker iteratively. This task ships submit for the Tier 1 leaf types (Market, Limit) and the WS skeleton. Stop/StopLimit/IfTouched extend in Task 12. Cancel in Task 13. WS fill events in Task 14.

- [ ] **Step 1: Write the test file (Market + Limit submit)**

`src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt`:

```kotlin
package com.qkt.broker.bybit

import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `name capabilities and supports`() {
        val client = FakeBybitClient()
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))
        assertThat(broker.name).isEqualTo("BybitSpot")
        assertThat(broker.capabilities).contains(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )
        assertThat(broker.supports("BYBIT_SPOT:BTCUSDT")).isTrue()
        assertThat(broker.supports("OANDA:EURUSD")).isFalse()
    }

    @Test
    fun `submit Market posts to v5 order create with correct body`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc-123","orderLinkId":"c1"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c1",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(client.posts).hasSize(1)
        assertThat(client.posts.single().path).isEqualTo("/v5/order/create")
        assertThat(client.posts.single().body).contains("\"category\":\"spot\"")
        assertThat(ack.accepted).isTrue()
        assertThat(ack.brokerOrderId).isEqualTo("abc-123")
    }

    @Test
    fun `submit Limit returns accepted=true on retCode 0`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc-456","orderLinkId":"c2"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))

        val ack =
            broker.submit(
                OrderRequest.Limit(
                    id = "c2",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    limitPrice = Money.of("80000"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(ack.brokerOrderId).isEqualTo("abc-456")
    }

    @Test
    fun `submit non-zero retCode produces accepted=false and OrderRejected`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":10001,"retMsg":"params error","result":{}}"""
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { e -> rejects.add(e) }
        val broker = BybitSpotBroker(client, bus, FixedClock(0L))

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "c3",
                    symbol = "BYBIT_SPOT:BTCUSDT",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("10001")
        assertThat(rejects).hasSize(1)
        assertThat(rejects.single().reason).contains("params error")
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSpotBrokerTest"`
Expected: `Unresolved reference: BybitSpotBroker`.

- [ ] **Step 3: Implement**

`src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`:

```kotlin
package com.qkt.broker.bybit

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class BybitSpotBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    private val log = LoggerFactory.getLogger(BybitSpotBroker::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override val name: String = "BybitSpot"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )

    override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_SPOT:")

    override fun submit(request: OrderRequest): SubmitAck {
        if (!supports(request.symbol)) {
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "BybitSpotBroker does not support symbol ${request.symbol}",
            )
        }
        val body = BybitOrderTranslator.toCreateBody(request)
        val response =
            try {
                transport.postSigned("/v5/order/create", body)
            } catch (e: Exception) {
                log.warn("Bybit submit failed: {}", e.message)
                bus.publish(
                    BrokerEvent.OrderRejected(
                        clientOrderId = request.id,
                        brokerOrderId = null,
                        reason = e.message ?: "transport failure",
                        timestamp = clock.now(),
                    ),
                )
                return SubmitAck(
                    clientOrderId = request.id,
                    brokerOrderId = null,
                    accepted = false,
                    rejectReason = e.message ?: "transport failure",
                )
            }
        return parseSubmitResponse(request.id, response)
    }

    override fun cancel(orderId: String) {
        // Implemented in Task 13
        TODO("cancel implemented in next task")
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        // Implemented in Task 15
        TODO("modify implemented in later task")
    }

    private fun parseSubmitResponse(
        clientOrderId: String,
        responseBody: String,
    ): SubmitAck {
        val tree = json.parseToJsonElement(responseBody).jsonObject
        val retCode = tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val retMsg = tree["retMsg"]?.jsonPrimitive?.content ?: ""
        if (retCode != 0) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = clientOrderId,
                    brokerOrderId = null,
                    reason = "$retCode: $retMsg",
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(
                clientOrderId = clientOrderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "$retCode: $retMsg",
            )
        }
        val brokerOrderId =
            tree["result"]?.jsonObject?.get("orderId")?.jsonPrimitive?.content
        return SubmitAck(
            clientOrderId = clientOrderId,
            brokerOrderId = brokerOrderId,
            accepted = true,
        )
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSpotBrokerTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Stage; commit when cancel + WS land in Task 13/14.**

---

### Task 12: `BybitSpotBroker.submit` for Stop, StopLimit, IfTouched

**Files:**
- Modify: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt`

The submit path already routes through `BybitOrderTranslator` which handles all five types. This task just adds tests verifying the Stop, StopLimit, IfTouched paths actually post the correct bodies.

- [ ] **Step 1: Append tests**

Append to `BybitSpotBrokerTest`:

```kotlin
    @Test
    fun `submit Stop posts a body containing triggerPrice and triggerDirection`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc-stop","orderLinkId":"c-stop"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))

        broker.submit(
            OrderRequest.Stop(
                id = "c-stop",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("79000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(client.posts.single().body).contains("\"triggerPrice\":\"79000")
        assertThat(client.posts.single().body).contains("\"triggerDirection\":2")
    }

    @Test
    fun `submit StopLimit posts a body with both triggerPrice and price`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))

        broker.submit(
            OrderRequest.StopLimit(
                id = "c",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("85000"),
                limitPrice = Money.of("85100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        val body = client.posts.single().body
        assertThat(body).contains("\"triggerPrice\":\"85000")
        assertThat(body).contains("\"price\":\"85100")
    }

    @Test
    fun `submit IfTouched MARKET posts triggerDirection 2 for BUY (fall to trigger)`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))

        broker.submit(
            OrderRequest.IfTouched(
                id = "c",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                triggerPrice = Money.of("75000"),
                onTrigger = com.qkt.execution.TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        val body = client.posts.single().body
        assertThat(body).contains("\"triggerDirection\":2")
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSpotBrokerTest"`
Expected: 7 tests PASS (4 from Task 11 + 3 here).

- [ ] **Step 3: Stage; commit at end of Task 14.**

---

### Task 13: `BybitSpotBroker.cancel`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`
- Modify: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt`

Cancel posts to `/v5/order/cancel`. The cancellation event arrives later via WS (`order` topic with `Cancelled` status); we don't synthesize an event from the REST ack.

- [ ] **Step 1: Add the test**

Append to `BybitSpotBrokerTest`:

```kotlin
    @Test
    fun `cancel posts to v5 order cancel with orderLinkId`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/cancel"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))
        // Submit first so we have a record (broker remembers the symbol for routing the cancel body)
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        broker.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        broker.cancel("c1")

        // Two posts: create + cancel
        assertThat(client.posts).hasSize(2)
        val cancelPost = client.posts[1]
        assertThat(cancelPost.path).isEqualTo("/v5/order/cancel")
        assertThat(cancelPost.body).contains("\"orderLinkId\":\"c1\"")
        assertThat(cancelPost.body).contains("\"category\":\"spot\"")
    }

    @Test
    fun `cancel of unknown order is a no-op`() {
        val client = FakeBybitClient()
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))
        broker.cancel("does-not-exist")
        assertThat(client.posts).isEmpty()
    }
```

- [ ] **Step 2: Implement cancel**

In `BybitSpotBroker.kt`, add a tracking map and replace the `cancel` method:

Add to class members:

```kotlin
    private val symbolByClientOrderId: MutableMap<String, String> = mutableMapOf()
```

In `submit`, after the `parseSubmitResponse(...)` call, when accepted is true, record the symbol. Restructure the bottom of `submit` to:

```kotlin
        val ack = parseSubmitResponse(request.id, response)
        if (ack.accepted) symbolByClientOrderId[request.id] = request.symbol
        return ack
```

Replace the stub `cancel`:

```kotlin
    override fun cancel(orderId: String) {
        val symbol = symbolByClientOrderId[orderId] ?: return  // unknown — no-op
        val body = BybitOrderTranslator.toCancelBody(symbol = symbol, orderLinkId = orderId)
        try {
            transport.postSigned("/v5/order/cancel", body)
        } catch (e: Exception) {
            log.warn("Bybit cancel failed for {}: {}", orderId, e.message)
        }
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSpotBrokerTest"`
Expected: 9 tests PASS (7 prior + 2 new).

- [ ] **Step 4: Stage; commit at end of Task 14.**

---

### Task 14: `BybitSpotBroker` WS event translation

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`
- Modify: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt`

WS frames on the `order` topic announce status changes (`New`, `Cancelled`, `Rejected`). The `execution` topic carries actual fill price/quantity. Translate both into `BrokerEvent`s on the bus.

Bybit V5 `order` frame shape:
```json
{
  "topic": "order",
  "data": [
    {
      "orderLinkId": "c1",
      "orderId": "abc-123",
      "symbol": "BTCUSDT",
      "side": "Buy",
      "orderStatus": "New",
      "category": "spot",
      ...
    }
  ]
}
```

`execution` frame:
```json
{
  "topic": "execution",
  "data": [
    {
      "orderLinkId": "c1",
      "orderId": "abc-123",
      "symbol": "BTCUSDT",
      "side": "Buy",
      "execPrice": "79998",
      "execQty": "0.01",
      "category": "spot",
      ...
    }
  ]
}
```

Each frame's `data` is an array; we iterate.

- [ ] **Step 1: Add tests for WS event translation**

Append to `BybitSpotBrokerTest`:

```kotlin
    @Test
    fun `WS order frame with status New publishes OrderAccepted`() {
        val client = FakeBybitClient()
        val bus = newBus()
        val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
        bus.subscribe<BrokerEvent.OrderAccepted> { accepts.add(it) }
        BybitSpotBroker(client, bus, FixedClock(0L))

        val frame =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"topic":"order","data":[{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","orderStatus":"New","category":"spot"}]}""",
            ).jsonObject
        client.emitWsFrame("order", frame)

        assertThat(accepts).hasSize(1)
        assertThat(accepts.single().clientOrderId).isEqualTo("c1")
        assertThat(accepts.single().brokerOrderId).isEqualTo("abc-123")
    }

    @Test
    fun `WS order frame with status Cancelled publishes OrderCancelled`() {
        val client = FakeBybitClient()
        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        BybitSpotBroker(client, bus, FixedClock(0L))

        val frame =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"topic":"order","data":[{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","orderStatus":"Cancelled","category":"spot"}]}""",
            ).jsonObject
        client.emitWsFrame("order", frame)

        assertThat(cancels).hasSize(1)
        assertThat(cancels.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `WS execution frame publishes OrderFilled with re-prefixed symbol`() {
        val client = FakeBybitClient()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        BybitSpotBroker(client, bus, FixedClock(0L))

        val frame =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"topic":"execution","data":[{"orderLinkId":"c1","orderId":"abc-123","symbol":"BTCUSDT","side":"Buy","execPrice":"79998.5","execQty":"0.01","category":"spot"}]}""",
            ).jsonObject
        client.emitWsFrame("execution", frame)

        assertThat(fills).hasSize(1)
        assertThat(fills.single().clientOrderId).isEqualTo("c1")
        assertThat(fills.single().symbol).isEqualTo("BYBIT_SPOT:BTCUSDT")
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("79998.5"))
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("0.01"))
    }
```

- [ ] **Step 2: Implement WS event translation**

In `BybitSpotBroker.kt`, add an `init` block that subscribes to topics, plus the handler methods.

Add these imports:
```kotlin
import com.qkt.common.Money
import com.qkt.common.Side
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
```

After the `name` / `capabilities` declarations, add:

```kotlin
    init {
        transport.subscribe("order") { frame -> onOrderFrame(frame) }
        transport.subscribe("execution") { frame -> onExecutionFrame(frame) }
    }

    private fun onOrderFrame(frame: JsonObject) {
        val data = frame["data"]?.jsonArray ?: return
        for (entry in data) {
            val obj = entry.jsonObject
            val clientOrderId = obj["orderLinkId"]?.jsonPrimitive?.content ?: continue
            val brokerOrderId = obj["orderId"]?.jsonPrimitive?.content
            val status = obj["orderStatus"]?.jsonPrimitive?.content ?: continue
            val now = clock.now()
            when (status) {
                "New" ->
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            timestamp = now,
                        ),
                    )
                "Cancelled" ->
                    bus.publish(
                        BrokerEvent.OrderCancelled(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            reason = "broker cancel",
                            timestamp = now,
                        ),
                    )
                "Rejected" ->
                    bus.publish(
                        BrokerEvent.OrderRejected(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            reason = obj["rejectReason"]?.jsonPrimitive?.content ?: "broker rejected",
                            timestamp = now,
                        ),
                    )
                // PartiallyFilled / Filled handled via the execution topic
                else -> log.debug("Bybit order frame status={} (no event)", status)
            }
        }
    }

    private fun onExecutionFrame(frame: JsonObject) {
        val data = frame["data"]?.jsonArray ?: return
        for (entry in data) {
            val obj = entry.jsonObject
            val clientOrderId = obj["orderLinkId"]?.jsonPrimitive?.content ?: continue
            val brokerOrderId = obj["orderId"]?.jsonPrimitive?.content
            val bareSymbol = obj["symbol"]?.jsonPrimitive?.content ?: continue
            val sideStr = obj["side"]?.jsonPrimitive?.content ?: continue
            val price = obj["execPrice"]?.jsonPrimitive?.content?.toBigDecimal() ?: continue
            val qty = obj["execQty"]?.jsonPrimitive?.content?.toBigDecimal() ?: continue
            val side = if (sideStr == "Buy") Side.BUY else Side.SELL
            val qktSymbol = BybitSymbol.toQkt(category = "spot", bare = bareSymbol)
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = clientOrderId,
                    brokerOrderId = brokerOrderId,
                    symbol = qktSymbol,
                    side = side,
                    price = price.setScale(Money.SCALE, Money.ROUNDING),
                    quantity = qty.setScale(Money.SCALE, Money.ROUNDING),
                    timestamp = clock.now(),
                ),
            )
        }
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSpotBrokerTest"`
Expected: 12 tests PASS.

- [ ] **Step 4: Commit Tasks 11-14 together**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt
git commit -m "feat(broker): add BybitSpotBroker submit cancel and WS event translation"
```

---

### Task 15: `BybitSpotBroker.modify`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`
- Modify: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt`

Modify posts to `/v5/order/amend`. Bybit accepts `qty`, `price`, `triggerPrice` updates.

- [ ] **Step 1: Add a test**

Append to `BybitSpotBrokerTest`:

```kotlin
    @Test
    fun `modify posts to v5 order amend with the changes`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/create"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        client.responses["/v5/order/amend"] =
            """{"retCode":0,"retMsg":"OK","result":{"orderId":"abc","orderLinkId":"c1"}}"""
        val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))

        broker.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "BYBIT_SPOT:BTCUSDT",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("80000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        val ack =
            broker.modify(
                "c1",
                com.qkt.broker.OrderModification(newLimitPrice = Money.of("80500")),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(client.posts.last().path).isEqualTo("/v5/order/amend")
        assertThat(client.posts.last().body).contains("\"price\":\"80500")
        assertThat(client.posts.last().body).contains("\"orderLinkId\":\"c1\"")
    }
```

- [ ] **Step 2: Implement modify**

Replace the `modify` stub in `BybitSpotBroker.kt`:

```kotlin
    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val symbol = symbolByClientOrderId[orderId]
            ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "unknown orderId $orderId")
        val parsed = BybitSymbol.parse(symbol)
        val sb = StringBuilder("{")
        sb.append("\"category\":\"${parsed.category}\",")
        sb.append("\"symbol\":\"${parsed.bare}\",")
        sb.append("\"orderLinkId\":\"$orderId\"")
        if (changes.newQuantity != null) sb.append(",\"qty\":\"${changes.newQuantity.toPlainString()}\"")
        if (changes.newLimitPrice != null) sb.append(",\"price\":\"${changes.newLimitPrice.toPlainString()}\"")
        if (changes.newStopPrice != null) sb.append(",\"triggerPrice\":\"${changes.newStopPrice.toPlainString()}\"")
        sb.append("}")
        val response =
            try {
                transport.postSigned("/v5/order/amend", sb.toString())
            } catch (e: Exception) {
                return SubmitAck(orderId, null, accepted = false, rejectReason = e.message ?: "transport failure")
            }
        return parseSubmitResponse(orderId, response)
    }
```

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "com.qkt.broker.bybit.BybitSpotBrokerTest"`
Expected: 13 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt
git commit -m "feat(broker): add BybitSpotBroker modify"
```

---

## Group E: e2e smoke

### Task 16: Extend `build.gradle.kts` to exclude `e2e-live` tag

**Files:**
- Modify: `build.gradle.kts`

The existing test config excludes `@Tag("e2e")`. Add `e2e-live` to the exclusion list (and to the include path for running on demand).

- [ ] **Step 1: Read current test config**

Run: `grep -nE 'e2e|excludeTags|includeTags' build.gradle.kts`

You should see:
```
excludeTags("e2e")
```

- [ ] **Step 2: Replace with multi-tag**

In `build.gradle.kts`, change:
```kotlin
excludeTags("e2e")
```
to:
```kotlin
excludeTags("e2e", "e2e-live")
```

(`useJUnitPlatform.excludeTags` accepts varargs.)

The `includeTags` path already accepts comma-separated values (from Phase 7c) and passes through unchanged.

- [ ] **Step 3: Verify default test run still excludes e2e and e2e-live**

Run: `./gradlew test --rerun-tasks 2>&1 | grep -c 'e2e'`
Expected: 0 (no e2e tests run by default).

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: exclude e2e-live tag from default test run"
```

---

### Task 17: `BybitSpotLiveSmokeTest`

**Files:**
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotLiveSmokeTest.kt`

`@Tag("e2e-live")` test that hits real Bybit testnet. Submits a far-from-market Limit BUY (will not fill), expects `OrderAccepted`, then `cancel`s and expects `OrderCancelled`.

- [ ] **Step 1: Write the test**

`src/test/kotlin/com/qkt/broker/bybit/BybitSpotLiveSmokeTest.kt`:

```kotlin
package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("e2e-live")
class BybitSpotLiveSmokeTest {
    @Test
    fun `submits a far-from-market limit on Bybit testnet then cancels`() {
        // Skip if no credentials (allow CI to run -PincludeTags=e2e-live without erroring)
        val key = System.getenv("BYBIT_API_KEY")
        val secret = System.getenv("BYBIT_API_SECRET")
        assumeTrue(key != null && secret != null, "BYBIT_API_KEY and BYBIT_API_SECRET required")

        val clock = SystemClock()
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val client = BybitClient(testnet = true)
        client.connect()
        try {
            val broker = BybitSpotBroker(client, bus, clock)

            val accepts = mutableListOf<BrokerEvent.OrderAccepted>()
            val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
            val acceptLatch = CountDownLatch(1)
            val cancelLatch = CountDownLatch(1)
            bus.subscribe<BrokerEvent.OrderAccepted> { e ->
                accepts.add(e)
                acceptLatch.countDown()
            }
            bus.subscribe<BrokerEvent.OrderCancelled> { e ->
                cancels.add(e)
                cancelLatch.countDown()
            }

            // BUY 0.001 BTC at $1 — far below market, will rest
            val orderId = "qkt-smoke-${clock.now()}"
            val ack =
                broker.submit(
                    OrderRequest.Limit(
                        id = orderId,
                        symbol = "BYBIT_SPOT:BTCUSDT",
                        side = Side.BUY,
                        quantity = Money.of("0.001"),
                        limitPrice = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = clock.now(),
                    ),
                )
            assertThat(ack.accepted).isTrue()

            assertThat(acceptLatch.await(15, TimeUnit.SECONDS))
                .withFailMessage("did not receive OrderAccepted within 15s")
                .isTrue()

            broker.cancel(orderId)

            assertThat(cancelLatch.await(15, TimeUnit.SECONDS))
                .withFailMessage("did not receive OrderCancelled within 15s")
                .isTrue()
            assertThat(cancels.single().clientOrderId).isEqualTo(orderId)
        } finally {
            client.close()
        }
    }
}
```

- [ ] **Step 2: Verify the test compiles and is excluded from default runs**

Run: `./gradlew test --rerun-tasks 2>&1 | grep -c BybitSpotLive`
Expected: 0 (excluded).

- [ ] **Step 3: Optionally run the smoke (if you have testnet credentials)**

```bash
export BYBIT_API_KEY=...
export BYBIT_API_SECRET=...
./gradlew test -PincludeTags=e2e-live --tests "com.qkt.broker.bybit.BybitSpotLiveSmokeTest"
```

Expected: PASS in ~5-30 seconds against Bybit testnet. Skip if no credentials available.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/broker/bybit/BybitSpotLiveSmokeTest.kt
git commit -m "test(broker): add BybitSpot e2e-live smoke test"
```

---

## Group F: verification

### Task 18: Full build + verify Phase 7d compatibility

- [ ] **Step 1: ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. ~500 tests pass (excluding `e2e` and `e2e-live`).

- [ ] **Step 3: Verify Phase 7d tests pass**

Run: `./gradlew test --tests "com.qkt.app.OrderManager*" --tests "com.qkt.broker.PaperBrokerTest" --tests "com.qkt.broker.LogBrokerTest"`
Expected: all pass — `capabilitiesFor` switch is backward compatible.

- [ ] **Step 4: Commit any format-driven fixups**

```bash
git status
git add -p
git commit -m "style: ktlintFormat after 7e additions" 2>&1 || echo "no format diffs"
```

---

### Task 19: Verify `./gradlew run` produces 10 FILLED+REJECTED

- [ ] **Step 1: Run**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: 10. Phase 7 demo invariant preserved.

- [ ] **Step 2: No commit** — verification only.

---

### Task 20: Phase 7e changelog

**Files:**
- Create: `docs/phases/phase-7e-bybit-and-composite.md`

Per qkt SKILL.md §6, every phase ships a changelog with worked examples.

- [ ] **Step 1: Write the changelog**

`docs/phases/phase-7e-bybit-and-composite.md`:

```markdown
# Phase 7e — Bybit Spot + CompositeBroker

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md)

## Summary

Phase 7e ships the first real broker integration — Bybit Spot via V5 REST + private WebSocket — and the long-deferred `CompositeBroker` so multiple brokers can be composed by symbol-pattern routing. Strategies can now place real (testnet) orders against a live venue. The composition pattern is in place for Phase 7f+ to add `BybitLinearBroker`, `AlpacaStocksBroker`, etc. as leaf additions.

## What's new

- `com.qkt.broker.CompositeBroker` — pattern-based broker router. Parallel to `CompositeMarketSource`.
- `com.qkt.broker.bybit.BybitClient` — shared low-level transport (HMAC-signed REST + private WebSocket). Used by all per-product Bybit brokers.
- `com.qkt.broker.bybit.BybitSpotBroker` — Bybit Spot broker. Native support for Market, Limit, Stop, StopLimit, IfTouched, Modify.
- `com.qkt.broker.bybit.BybitSigner` — HMAC-SHA256 helper.
- `com.qkt.broker.bybit.BybitSymbol` — prefix parser.
- `com.qkt.broker.bybit.BybitOrderTranslator` — pure functions translating `OrderRequest` to/from Bybit V5 fields.
- `com.qkt.broker.BybitTransport` — interface extracted for testability (real `BybitClient` and test `FakeBybitClient` both implement).
- `Broker.capabilitiesFor(symbol)` — defaulted method on the interface, used by `OrderManager.dispatch()` and overridden by `CompositeBroker`.
- `Broker.supports(symbol)` — defaulted method (informational).
- Symbol convention `EXCHANGE_PRODUCT:SYMBOL` locked in for brokers (`BYBIT_SPOT:BTCUSDT`).
- `e2e-live` JUnit tag for manual real-broker smoke tests; excluded from default `./gradlew test`.

## Migration from previous phase

| Phase 7d call | Phase 7e equivalent | Notes |
|---|---|---|
| `broker.capabilities` (in OrderManager) | `broker.capabilitiesFor(request.symbol)` | Default impl returns the flat set; behavior identical for non-composite brokers. |

No other breaking changes. Existing strategies, tests, and entry points compile unchanged.

## Usage cookbook

### 1. Construct a Bybit testnet broker

```kotlin
// API key/secret read from BYBIT_API_KEY / BYBIT_API_SECRET env vars; testnet defaults to true
val client = BybitClient()
client.connect()
val bybitSpot = BybitSpotBroker(client, bus, SystemClock())
```

### 2. Live trading explicit opt-in

```kotlin
val client = BybitClient(testnet = false)   // requires explicit false
client.connect()
```

Or via env: `export BYBIT_TESTNET=false`. **Never write live API keys in code.**

### 3. Composing multiple brokers

```kotlin
val composite = CompositeBroker(
    routes = listOf(
        SymbolPattern.prefix("BYBIT_SPOT:")    to bybitSpot,
        SymbolPattern.prefix("PAPER:")         to paperBroker,
    ),
    fallback = logBroker,
    bus = bus,
)
```

### 4. Strategy submitting to Bybit Spot

```kotlin
emit(Signal.Submit(
    OrderRequest.Limit(
        id = ids.next(),
        symbol = "BYBIT_SPOT:BTCUSDT",     // routed by Composite to BybitSpotBroker
        side = Side.BUY,
        quantity = Money.of("0.001"),
        limitPrice = Money.of("80000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))
```

`OrderManager` checks `composite.capabilitiesFor("BYBIT_SPOT:BTCUSDT")` (which delegates to `BybitSpotBroker.capabilities`), sees `LIMIT`, hands off. Bybit accepts; WS reports `New`; `BrokerEvent.OrderAccepted` lands on the bus.

### 5. Stop loss on Bybit Spot

```kotlin
emit(Signal.Submit(
    OrderRequest.Stop(
        id = ids.next(),
        symbol = "BYBIT_SPOT:BTCUSDT",
        side = Side.SELL,
        quantity = Money.of("0.001"),
        stopPrice = Money.of("75000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))
```

`BybitSpotBroker` advertises `STOP` natively. The order is sent to Bybit with `triggerPrice=75000` and `triggerDirection=2` (sell on fall). When BTC hits $75k, Bybit's server triggers and sends a Market sell. WS `execution` topic delivers the fill; bus publishes `OrderFilled`.

### 6. Bracket on Bybit Spot (engine fallback)

`BybitSpotBroker` does NOT advertise `BRACKET`. OrderManager decomposes:

```kotlin
emit(Signal.Submit(
    OrderRequest.Bracket(
        id = ids.next(),
        symbol = "BYBIT_SPOT:BTCUSDT", side = Side.BUY, quantity = Money.of("0.001"),
        entry = OrderRequest.Limit(...limitPrice = Money.of("80000")...),
        takeProfit = Money.of("82000"),
        stopLoss   = Money.of("78000"),
        ...
    ),
))
```

OrderManager decomposes to `OTO(entry, [OCO(Limit at 82000, Stop at 78000)])`. Entry posts to Bybit. On entry fill, OCO children activate: TP Limit posts to Bybit, SL Stop posts to Bybit. Whichever fills first cancels the other.

### 7. Adding a new broker product

```kotlin
class BybitLinearBroker(client: BybitClient, bus: EventBus, clock: Clock) : Broker {
    override val name = "BybitLinear"
    override val capabilities = setOf(MARKET, LIMIT, STOP, STOP_LIMIT, IF_TOUCHED, MODIFY)
    override fun supports(symbol) = symbol.startsWith("BYBIT_LINEAR:")
    // submit/cancel/modify use category="linear" via shared client
}

// Add one line to the composite:
val composite = CompositeBroker(
    routes = listOf(
        SymbolPattern.prefix("BYBIT_SPOT:")   to bybitSpot,
        SymbolPattern.prefix("BYBIT_LINEAR:") to bybitLinear,    // new
    ),
    fallback = paperBroker, bus = bus,
)
```

### 8. Testing with `FakeBybitClient`

```kotlin
val client = FakeBybitClient()
client.responses["/v5/order/create"] = """{"retCode":0,"result":{"orderId":"abc","orderLinkId":"c1"}}"""
val broker = BybitSpotBroker(client, bus, FixedClock(0L))
val ack = broker.submit(...)
assertThat(client.posts.single().path).isEqualTo("/v5/order/create")

// Drive a fill via WS:
client.emitWsFrame("execution", JsonObject(...))
```

## Testing patterns

- `FakeBybitClient` for unit tests — programmable REST responses, programmable WS frames.
- `CompositeBroker` tests use `FakeBroker` × N with different patterns.
- `BybitSpotLiveSmokeTest` (`@Tag("e2e-live")`) — hits real testnet; runs only via `-PincludeTags=e2e-live`.

## Known limitations

- No reconnect supervision. On WS disconnect, fill events stop arriving until JVM restart. Phase 7f.
- No position reconciliation against Bybit's view. `PositionTracker` remains canonical.
- No account / equity / buying-power reporting.
- No rate-limit enforcement.
- `BybitLinearBroker` (USDT perpetuals) not shipped.
- DAY time-in-force on Bybit Spot maps to GTC (Bybit Spot doesn't natively support DAY).
- `OrderManager.modify()` not exposed to strategies (cancel + resubmit).
- Decimal precision: orders sent at our `Money.SCALE = 8`; Bybit rejects if precision exceeds per-symbol step. No client-side rounding.
- `CompositeBroker.capabilities` (flat) throws — only `capabilitiesFor(symbol)` is safe.

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase7e.md`](../superpowers/plans/2026-05-06-trading-engine-phase7e.md)
- Bybit V5 API: https://bybit-exchange.github.io/docs/v5/intro
- Phase 7d baseline: [`phase-7d-broker-and-orders.md`](phase-7d-broker-and-orders.md)
```

- [ ] **Step 2: Verify line count**

Run: `wc -l docs/phases/phase-7e-bybit-and-composite.md`
Expected: 200-400 lines.

- [ ] **Step 3: Commit**

```bash
git add docs/phases/phase-7e-bybit-and-composite.md
git commit -m "docs: phase 7e changelog with usage cookbook"
```

---

### Task 21: Final verification + branch state

- [ ] **Step 1: Branch state**

Run: `git log --oneline main..HEAD`
Expected: ~15 commits, all conventional, no AI footers.

- [ ] **Step 2: Pre-push checklist**

Run:
```bash
./gradlew build
git status
grep -rEn 'TODO|FIXME|XXX' src/ | grep -v "// \?"
```

- `./gradlew build` ends BUILD SUCCESSFUL.
- `git status` clean (or only `tt.txt`).
- No new TODO/FIXME without an issue link. (We have one TODO from the plan — re-check we replaced both `TODO()` stubs in BybitSpotBroker with real impls. They were filled in by Tasks 13 and 15.)

- [ ] **Step 3: Plan handoff**

Phase 7e is shippable. Decide with the user whether to merge `phase7e-bybit-spot-and-composite-broker` into main.

After merge, Phase 7e is done. Strategies can place real orders on Bybit testnet via `BYBIT_SPOT:*` symbols. Phase 7f (reconnect, reconciliation, account polling) is the next step before live money.

---

## Spec ambiguities encountered

These are decisions the plan made which the spec left open or marked for plan resolution.

1. **`BybitTransport` interface placement.** Extracted to the `bybit` package alongside `BybitClient`. Could live in a generic `transport` package, but only Bybit uses it now — keep it scoped.

2. **`onWsMessage` topic dispatch.** Bybit V5 frames have a top-level `topic` field. We dispatch by exact match. Frames without a `topic` (op acks) are logged at debug only.

3. **Symbol stored on submit for cancel routing.** `BybitSpotBroker` keeps a `symbolByClientOrderId: MutableMap<String, String>`. Pruning is not implemented in this plan; the map grows unbounded over the broker's lifetime. Phase 7f's reconnect/reconciliation work should add pruning on terminal events. For 7e it's acceptable (broker lifetime ≈ session lifetime).

4. **Ping scheduler in `BybitClient`.** Sends `{"op":"ping"}` every 20s. Bybit V5's keepalive expectation is ~30s; 20s is conservative. Pong responses are received as op messages and not separately tracked.

5. **JSON building in `BybitOrderTranslator`.** Hand-built JSON strings (with manual escaping) vs `kotlinx.serialization`. Hand-built is fine here because the field set is small, all values are alphanumeric or numeric, and we control them. No injection risk from `request.symbol` since it's been parsed by `BybitSymbol.parse`. Document this.

6. **Test `BybitSignerTest` hex value.** Computed externally:
   ```
   echo -n "hello world" | openssl dgst -sha256 -hmac "test-secret"
   ```
   The plan hardcodes the value. If your environment produces a different value, the test catches a real bug.

7. **`SymbolPattern` reuse.** Currently in `com.qkt.marketdata.source.SymbolPattern`. `CompositeBroker` imports from there. No move; matches the spec's recommendation.

8. **`CompositeBroker.capabilities` getter throws.** This is intentional per spec §17 #7. Java consumers reading `composite.capabilities` get an `IllegalStateException`. In practice no consumer should — `OrderManager` uses `capabilitiesFor(symbol)`. Document.

9. **Decimal precision.** `request.quantity.toPlainString()` and `request.limitPrice.toPlainString()` send our 8-decimal values. Bybit per-symbol `qtyStep` and `tickSize` may reject. Surface as `OrderRejected` with `retCode != 0`. Phase 7f could fetch instrument info and round client-side.

10. **WebSocket pong handling.** Bybit's pong is not separately observed; we trust OkHttp's pong-frame heuristic plus the 30s server timeout. If our ping is received, server stays alive. If not, server disconnects within 30s; our `onClosed` fires.
