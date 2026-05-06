# Phase 7g — Periodic Reconciliation and Balance Polling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add periodic REST reconciliation and wallet-balance polling to `BybitSpotBroker` so missed cancellations are caught within the polling window and balances are observable to strategies.

**Architecture:** Generic `PeriodicReconciler` in `com.qkt.common.net` runs on a daemon `ScheduledExecutorService`. `BybitStateRecovery` gains a third reconcile path (`reconcileBalances`). `BybitClient` caches balances. `BrokerEvent` gains an `OrderEvent` marker (lifting `clientOrderId`/`brokerOrderId` off the parent) and a new `BalancesUpdated` variant.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ, OkHttp, kotlinx-serialization-json, SLF4J, `java.util.concurrent`.

**Spec:** [`../specs/2026-05-06-trading-engine-phase7g-design.md`](../specs/2026-05-06-trading-engine-phase7g-design.md)

---

## Pre-flight

Branch already exists: `phase7g-reconciliation-and-balances`. Spec already committed (`253e7e8`).

```bash
git status      # clean (or only ?? tt.txt)
git branch --show-current   # phase7g-reconciliation-and-balances
./gradlew check  # green from main
```

---

## Task 1: `PeriodicReconciler`

**Files:**
- Create: `src/main/kotlin/com/qkt/common/net/PeriodicReconciler.kt`
- Create: `src/test/kotlin/com/qkt/common/net/PeriodicReconcilerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/common/net/PeriodicReconcilerTest.kt
package com.qkt.common.net

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PeriodicReconcilerTest {
    @Test
    fun `start schedules action at fixed rate with given interval`() {
        val scheduler = TestScheduler()
        val invocations = AtomicInteger()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { invocations.incrementAndGet() },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()

        assertThat(scheduler.fixedRateInitialDelays).containsExactly(30_000L)
        assertThat(scheduler.fixedRatePeriods).containsExactly(30_000L)
        assertThat(reconciler.isRunning).isTrue
    }

    @Test
    fun `tick invokes action`() {
        val scheduler = TestScheduler()
        val invocations = AtomicInteger()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { invocations.incrementAndGet() },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()
        scheduler.fireTick()
        scheduler.fireTick()
        scheduler.fireTick()

        assertThat(invocations.get()).isEqualTo(3)
    }

    @Test
    fun `tick swallows exceptions and keeps loop alive`() {
        val scheduler = TestScheduler()
        val invocations = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = {
                    val n = invocations.incrementAndGet()
                    if (n == 2) error("boom")
                },
                executor = scheduler.asExecutor(),
                onError = { errors.add(it) },
            )

        reconciler.start()
        scheduler.fireTick()
        scheduler.fireTick()
        scheduler.fireTick()

        assertThat(invocations.get()).isEqualTo(3)
        assertThat(errors).hasSize(1)
        assertThat(errors.single().message).isEqualTo("boom")
    }

    @Test
    fun `start is idempotent`() {
        val scheduler = TestScheduler()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()
        reconciler.start()
        reconciler.start()

        assertThat(scheduler.fixedRateInitialDelays).hasSize(1)
    }

    @Test
    fun `stop cancels future and flips isRunning`() {
        val scheduler = TestScheduler()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { },
                executor = scheduler.asExecutor(),
            )

        reconciler.start()
        reconciler.stop()

        assertThat(reconciler.isRunning).isFalse
        assertThat(scheduler.cancelled).isTrue
    }

    @Test
    fun `stop without prior start is a noop`() {
        val scheduler = TestScheduler()
        val reconciler =
            PeriodicReconciler(
                intervalMs = 30_000L,
                action = { },
                executor = scheduler.asExecutor(),
            )

        reconciler.stop()

        assertThat(reconciler.isRunning).isFalse
        assertThat(scheduler.cancelled).isFalse
    }

    private class TestScheduler {
        val fixedRateInitialDelays: MutableList<Long> = mutableListOf()
        val fixedRatePeriods: MutableList<Long> = mutableListOf()
        var cancelled: Boolean = false
        private var task: Runnable? = null

        fun fireTick() {
            task?.run() ?: error("no task scheduled")
        }

        fun asExecutor(): ScheduledExecutorService =
            object : ScheduledExecutorService {
                override fun scheduleAtFixedRate(
                    command: Runnable,
                    initialDelay: Long,
                    period: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> {
                    fixedRateInitialDelays.add(unit.toMillis(initialDelay))
                    fixedRatePeriods.add(unit.toMillis(period))
                    task = command
                    return CapturingFuture { cancelled = true }
                }

                override fun shutdown() {}

                override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

                override fun isShutdown(): Boolean = false

                override fun isTerminated(): Boolean = false

                override fun awaitTermination(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = true

                override fun <T> submit(task: Callable<T>): Future<T> = error("not used")

                override fun <T> submit(
                    task: Runnable,
                    result: T,
                ): Future<T> = error("not used")

                override fun submit(task: Runnable): Future<*> = error("not used")

                override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
                    mutableListOf()

                override fun <T> invokeAll(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): MutableList<Future<T>> = mutableListOf()

                override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T = error("not used")

                override fun <T> invokeAny(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): T = error("not used")

                override fun execute(command: Runnable) {}

                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")

                override fun <V> schedule(
                    callable: Callable<V>,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<V> = error("not used")

                override fun scheduleWithFixedDelay(
                    command: Runnable,
                    initialDelay: Long,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")
            }
    }

    private class CapturingFuture(
        private val onCancel: () -> Unit,
    ) : ScheduledFuture<Any?> {
        override fun compareTo(other: Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = 0L

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            onCancel()
            return true
        }

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.common.net.PeriodicReconcilerTest`
Expected: FAIL — `Unresolved reference: PeriodicReconciler`.

- [ ] **Step 3: Write implementation**

```kotlin
// src/main/kotlin/com/qkt/common/net/PeriodicReconciler.kt
package com.qkt.common.net

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class PeriodicReconciler(
    private val intervalMs: Long,
    private val action: () -> Unit,
    private val executor: ScheduledExecutorService = defaultExecutor(),
    private val onError: (Throwable) -> Unit = { log.warn("periodic reconcile failed", it) },
) {
    private val started = AtomicBoolean(false)

    @Volatile
    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        future =
            executor.scheduleAtFixedRate(
                ::tick,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS,
            )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        future?.cancel(false)
        future = null
    }

    val isRunning: Boolean
        get() = started.get()

    private fun tick() {
        try {
            action()
        } catch (e: Throwable) {
            onError(e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PeriodicReconciler::class.java)

        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "qkt-periodic-reconciler").apply { isDaemon = true }
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.qkt.common.net.PeriodicReconcilerTest`
Expected: PASS, 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/common/net/PeriodicReconciler.kt \
        src/test/kotlin/com/qkt/common/net/PeriodicReconcilerTest.kt
git commit -m "feat(common): add PeriodicReconciler with fixed-rate scheduling"
```

---

## Task 2: `BybitBalanceTranslator`

**Files:**
- Create: `src/main/kotlin/com/qkt/broker/bybit/BybitBalanceTranslator.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitBalanceTranslatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitBalanceTranslatorTest.kt
package com.qkt.broker.bybit

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitBalanceTranslatorTest {
    @Test
    fun `parseWalletBalance extracts coin balances from valid response`() {
        val response =
            """
            {"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED",
            "coin":[{"coin":"BTC","walletBalance":"0.5","availableToWithdraw":"0.5"},
            {"coin":"USDT","walletBalance":"30000","availableToWithdraw":"30000"}]}]}}
            """.trimIndent()

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).containsOnlyKeys("BTC", "USDT")
        assertThat(balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(balances["USDT"]).isEqualByComparingTo(BigDecimal("30000"))
    }

    @Test
    fun `parseWalletBalance returns empty map when result list empty`() {
        val response = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).isEmpty()
    }

    @Test
    fun `parseWalletBalance returns empty map when account has no coins`() {
        val response =
            """{"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","coin":[]}]}}"""

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).isEmpty()
    }

    @Test
    fun `parseWalletBalance skips coins with blank walletBalance`() {
        val response =
            """
            {"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED",
            "coin":[{"coin":"BTC","walletBalance":""},{"coin":"USDT","walletBalance":"100"}]}]}}
            """.trimIndent()

        val balances = BybitBalanceTranslator.parseWalletBalance(response)

        assertThat(balances).containsOnlyKeys("USDT")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitBalanceTranslatorTest`
Expected: FAIL — `Unresolved reference: BybitBalanceTranslator`.

- [ ] **Step 3: Write implementation**

```kotlin
// src/main/kotlin/com/qkt/broker/bybit/BybitBalanceTranslator.kt
package com.qkt.broker.bybit

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BybitBalanceTranslator {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseWalletBalance(response: String): Map<String, BigDecimal> {
        val root = json.parseToJsonElement(response).jsonObject
        val list = root["result"]?.jsonObject?.get("list")?.jsonArray ?: return emptyMap()
        if (list.isEmpty()) return emptyMap()

        val coins = list.first().jsonObject["coin"]?.jsonArray ?: return emptyMap()
        val out = mutableMapOf<String, BigDecimal>()
        for (entry in coins) {
            val obj = entry.jsonObject
            val coin = obj["coin"]?.jsonPrimitive?.content ?: continue
            val rawBalance = obj["walletBalance"]?.jsonPrimitive?.content ?: continue
            if (rawBalance.isBlank()) continue
            out[coin] = BigDecimal(rawBalance)
        }
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitBalanceTranslatorTest`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitBalanceTranslator.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitBalanceTranslatorTest.kt
git commit -m "feat(broker): add BybitBalanceTranslator for wallet-balance parsing"
```

---

## Task 3: `BrokerEvent.OrderEvent` marker + `BalancesUpdated` variant

**Files:**
- Modify: `src/main/kotlin/com/qkt/events/BrokerEvent.kt`

This task is structural — no new test up front; existing tests must continue to pass after the refactor. After the structural change, add a small test verifying `BalancesUpdated` exists and round-trips through the bus.

- [ ] **Step 1: Refactor `BrokerEvent` — add `OrderEvent` marker, add `BalancesUpdated`**

Replace contents:

```kotlin
// src/main/kotlin/com/qkt/events/BrokerEvent.kt
package com.qkt.events

import com.qkt.common.Side
import java.math.BigDecimal

sealed interface BrokerEvent : Event {

    sealed interface OrderEvent : BrokerEvent {
        val clientOrderId: String
        val brokerOrderId: String?
    }

    data class OrderAccepted(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderRejected(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderPartiallyFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val cumulativeFilled: BigDecimal,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class OrderCancelled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    data class BalancesUpdated(
        val balances: Map<String, BigDecimal>,
        val source: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent
}
```

- [ ] **Step 2: Run the full test suite to confirm no regression**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 527+ tests green. Existing subscribers on `BrokerEvent.OrderFilled` etc. unaffected.

If anything fails, the most likely cause is a `when (event)` somewhere that exhaustively matched the previous flat hierarchy. Fix by adding `is BrokerEvent.BalancesUpdated -> ...` or by widening the match to use a sealed switch. Search:

```bash
grep -rEn 'when *\(.*BrokerEvent' src/main/kotlin src/test/kotlin
```

Resolve each hit, then re-run tests.

- [ ] **Step 3: Add a small bus round-trip test**

Append to `src/test/kotlin/com/qkt/events/BrokerEventTest.kt` (create file if not present):

```kotlin
// src/test/kotlin/com/qkt/events/BrokerEventTest.kt
package com.qkt.events

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerEventTest {
    @Test
    fun `BalancesUpdated round-trips through the EventBus`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val received = mutableListOf<BrokerEvent.BalancesUpdated>()
        bus.subscribe<BrokerEvent.BalancesUpdated> { received.add(it) }

        bus.publish(
            BrokerEvent.BalancesUpdated(
                balances = mapOf("BTC" to BigDecimal("0.5"), "USDT" to BigDecimal("30000")),
                source = "BYBIT_SPOT",
            ),
        )

        assertThat(received).hasSize(1)
        assertThat(received.single().source).isEqualTo("BYBIT_SPOT")
        assertThat(received.single().balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `OrderEvent marker is reachable from order variants`() {
        val accepted: BrokerEvent.OrderEvent =
            BrokerEvent.OrderAccepted(clientOrderId = "c1", brokerOrderId = "b1")
        assertThat(accepted.clientOrderId).isEqualTo("c1")
    }
}
```

- [ ] **Step 4: Run new test**

Run: `./gradlew test --tests com.qkt.events.BrokerEventTest`
Expected: PASS.

- [ ] **Step 5: Run ktlintFormat (the BrokerEvent refactor may shift indentation)**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/events/BrokerEvent.kt \
        src/test/kotlin/com/qkt/events/BrokerEventTest.kt
git commit -m "refactor(events): add OrderEvent marker and BalancesUpdated variant"
```

---

## Task 4: `BybitTransport.balances` and `updateBalances`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt` (interface + impl)
- Modify: `src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitClientBalancesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitClientBalancesTest.kt
package com.qkt.broker.bybit

import com.qkt.common.FixedClock
import java.math.BigDecimal
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitClientBalancesTest {
    @Test
    fun `balances starts empty and updateBalances replaces snapshot atomically`() {
        val httpClient = OkHttpClient.Builder().build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )

        assertThat(client.balances).isEmpty()

        client.updateBalances(mapOf("BTC" to BigDecimal("0.5"), "USDT" to BigDecimal("30000")))

        assertThat(client.balances).containsOnlyKeys("BTC", "USDT")
        assertThat(client.balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))

        client.updateBalances(mapOf("ETH" to BigDecimal("1.0")))

        assertThat(client.balances).containsOnlyKeys("ETH")
    }

    @Test
    fun `balances is an immutable snapshot — mutating the source map does not affect the cache`() {
        val httpClient = OkHttpClient.Builder().build()
        val client =
            BybitClient(
                apiKey = "k",
                apiSecret = "s",
                testnet = true,
                httpClient = httpClient,
                clock = FixedClock(0L),
            )
        val source = mutableMapOf("BTC" to BigDecimal("0.5"))

        client.updateBalances(source)
        source["BTC"] = BigDecimal("999")

        assertThat(client.balances["BTC"]).isEqualByComparingTo(BigDecimal("0.5"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitClientBalancesTest`
Expected: FAIL — `Unresolved reference: balances` / `updateBalances` on `BybitClient`.

- [ ] **Step 3: Extend `BybitTransport` interface**

Open `src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt`. Find the `interface BybitTransport` block. Add two new members at the end:

```kotlin
interface BybitTransport {
    val isConnected: Boolean
    fun onReconnect(handler: () -> Unit)
    fun postSigned(path: String, body: String): String
    fun subscribe(topic: String, listener: (kotlinx.serialization.json.JsonObject) -> Unit)
    fun onDisconnect(handler: (String) -> Unit)

    // 7g additions:
    val balances: Map<String, BigDecimal>
    fun updateBalances(snapshot: Map<String, BigDecimal>)
}
```

(Keep the existing imports and signatures; only add the two new lines and any required `import java.math.BigDecimal`.)

- [ ] **Step 4: Implement on `BybitClient`**

Inside `class BybitClient`, add a private `AtomicReference` field and the two members:

```kotlin
private val balancesRef = java.util.concurrent.atomic.AtomicReference<Map<String, BigDecimal>>(emptyMap())

override val balances: Map<String, BigDecimal>
    get() = balancesRef.get()

override fun updateBalances(snapshot: Map<String, BigDecimal>) {
    balancesRef.set(snapshot.toMap())
}
```

Place near the existing state fields (`connected: AtomicBoolean`, `hasEverConnected: AtomicBoolean`). Add `import java.math.BigDecimal` at the top of the file if not already present.

- [ ] **Step 5: Run the BybitClient test**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitClientBalancesTest`
Expected: PASS.

- [ ] **Step 6: Update `FakeBybitClient`**

In `src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt`, add:

```kotlin
private var balancesCache: Map<String, BigDecimal> = emptyMap()

override val balances: Map<String, BigDecimal>
    get() = balancesCache

override fun updateBalances(snapshot: Map<String, BigDecimal>) {
    balancesCache = snapshot.toMap()
}
```

Add `import java.math.BigDecimal` at the top if not already present.

- [ ] **Step 7: Run full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. All transport-implementing fakes compile, no regressions.

- [ ] **Step 8: Run ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt \
        src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitClientBalancesTest.kt
git commit -m "feat(broker): add BybitTransport balances cache and updateBalances"
```

---

## Task 5: `BybitStateRecovery.reconcileBalances`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitStateRecovery.kt`
- Modify: `src/test/kotlin/com/qkt/broker/bybit/BybitStateRecoveryTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `BybitStateRecoveryTest.kt` (the existing class):

```kotlin
@Test
fun `reconcile fetches wallet balance and writes it to the transport cache`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
    client.responses["/v5/execution/list"] = emptyExecutionsResponse()
    client.responses["/v5/account/wallet-balance"] =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","coin":[{"coin":"BTC","walletBalance":"0.5"},{"coin":"USDT","walletBalance":"30000"}]}]}}"""

    val recovery =
        BybitStateRecovery(
            transport = client,
            bus = newBus(),
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        )
    recovery.reconcile()

    assertThat(client.balances).containsOnlyKeys("BTC", "USDT")
    assertThat(client.balances["BTC"]).isEqualByComparingTo(java.math.BigDecimal("0.5"))
}

@Test
fun `reconcile publishes BalancesUpdated event with source set to BYBIT_SPOT`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
    client.responses["/v5/execution/list"] = emptyExecutionsResponse()
    client.responses["/v5/account/wallet-balance"] =
        """{"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","coin":[{"coin":"BTC","walletBalance":"0.5"}]}]}}"""

    val bus = newBus()
    val received = mutableListOf<BrokerEvent.BalancesUpdated>()
    bus.subscribe<BrokerEvent.BalancesUpdated> { received.add(it) }

    val recovery =
        BybitStateRecovery(
            transport = client,
            bus = bus,
            clock = FixedClock(1_234_567L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        )
    recovery.reconcile()

    assertThat(received).hasSize(1)
    assertThat(received.single().source).isEqualTo("BYBIT_SPOT")
    assertThat(received.single().balances).containsKey("BTC")
}

@Test
fun `reconcile sends accountType UNIFIED in wallet-balance request body`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
    client.responses["/v5/execution/list"] = emptyExecutionsResponse()
    client.responses["/v5/account/wallet-balance"] =
        """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    val recovery =
        BybitStateRecovery(
            transport = client,
            bus = newBus(),
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        )
    recovery.reconcile()

    val balancePost = client.posts.first { it.path == "/v5/account/wallet-balance" }
    assertThat(balancePost.body).contains("\"accountType\":\"UNIFIED\"")
}

@Test
fun `concurrent calls to reconcile are serialized`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/realtime"] = emptyOpenOrdersResponse()
    client.responses["/v5/execution/list"] = emptyExecutionsResponse()
    client.responses["/v5/account/wallet-balance"] =
        """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    val recovery =
        BybitStateRecovery(
            transport = client,
            bus = newBus(),
            clock = FixedClock(0L),
            getKnownOrders = { emptyMap() },
            lastFillTimeProvider = { 0L },
            seenExecIds = mutableSetOf(),
        )

    val threads =
        (1..8).map {
            Thread { recovery.reconcile() }
        }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    // 8 reconcile() calls, 3 REST endpoints each, all serialized
    assertThat(client.posts).hasSize(24)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitStateRecoveryTest`
Expected: FAIL — new tests fail because `reconcileBalances` doesn't exist (or is not called from `reconcile`).

- [ ] **Step 3: Implement `reconcileBalances` and synchronize `reconcile`**

In `src/main/kotlin/com/qkt/broker/bybit/BybitStateRecovery.kt`:

a) Add a lock object:

```kotlin
private val lock = Any()
```

b) Wrap the existing `reconcile()` body in `synchronized(lock) { ... }` and call the new path:

```kotlin
fun reconcile() {
    synchronized(lock) {
        reconcileOpenOrders()
        reconcileExecutions()
        reconcileBalances()
    }
}
```

c) Add the new method:

```kotlin
private fun reconcileBalances() {
    val response = transport.postSigned("/v5/account/wallet-balance", """{"accountType":"UNIFIED"}""")
    val parsed = BybitBalanceTranslator.parseWalletBalance(response)
    transport.updateBalances(parsed)
    bus.publish(
        BrokerEvent.BalancesUpdated(
            balances = parsed,
            source = "BYBIT_SPOT",
            timestamp = clock.now(),
        ),
    )
}
```

- [ ] **Step 4: Run BybitStateRecoveryTest**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitStateRecoveryTest`
Expected: PASS, all tests green.

- [ ] **Step 5: Run ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitStateRecovery.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitStateRecoveryTest.kt
git commit -m "feat(broker): add reconcileBalances and serialize reconcile calls"
```

---

## Task 6: Wire `PeriodicReconciler` into `BybitSpotBroker`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`
- Create: `src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerReconcilerIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerReconcilerIntegrationTest.kt
package com.qkt.broker.bybit

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSpotBrokerReconcilerIntegrationTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun emptyOk() = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    @Test
    fun `broker init triggers an immediate reconcile (3 REST calls)`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()

        BybitSpotBroker(client, newBus(), FixedClock(0L))

        val paths = client.posts.map { it.path }
        assertThat(paths).contains("/v5/order/realtime", "/v5/execution/list", "/v5/account/wallet-balance")
    }

    @Test
    fun `periodic ticks invoke reconcile`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()

        val scheduler = TickScheduler()
        BybitSpotBroker(
            client,
            newBus(),
            FixedClock(0L),
            pollExecutor = scheduler.asExecutor(),
        )

        val initialPosts = client.posts.size
        scheduler.fireTick()
        scheduler.fireTick()

        // 2 ticks, each adds 3 REST calls
        assertThat(client.posts.size - initialPosts).isEqualTo(6)
    }

    @Test
    fun `close stops the periodic reconciler`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyOk()
        client.responses["/v5/execution/list"] = emptyOk()
        client.responses["/v5/account/wallet-balance"] = emptyOk()

        val scheduler = TickScheduler()
        val broker =
            BybitSpotBroker(
                client,
                newBus(),
                FixedClock(0L),
                pollExecutor = scheduler.asExecutor(),
            )

        broker.close()

        assertThat(scheduler.cancelled).isTrue
    }

    private class TickScheduler {
        var cancelled: Boolean = false
        private var task: Runnable? = null

        fun fireTick() {
            task?.run() ?: error("no task scheduled")
        }

        fun asExecutor(): ScheduledExecutorService =
            object : ScheduledExecutorService {
                override fun scheduleAtFixedRate(
                    command: Runnable,
                    initialDelay: Long,
                    period: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> {
                    task = command
                    return CapturingFuture { cancelled = true }
                }

                override fun shutdown() {}

                override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

                override fun isShutdown(): Boolean = false

                override fun isTerminated(): Boolean = false

                override fun awaitTermination(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = true

                override fun <T> submit(task: Callable<T>): Future<T> = error("not used")

                override fun <T> submit(
                    task: Runnable,
                    result: T,
                ): Future<T> = error("not used")

                override fun submit(task: Runnable): Future<*> = error("not used")

                override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> =
                    mutableListOf()

                override fun <T> invokeAll(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): MutableList<Future<T>> = mutableListOf()

                override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T = error("not used")

                override fun <T> invokeAny(
                    tasks: MutableCollection<out Callable<T>>,
                    timeout: Long,
                    unit: TimeUnit,
                ): T = error("not used")

                override fun execute(command: Runnable) {}

                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")

                override fun <V> schedule(
                    callable: Callable<V>,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<V> = error("not used")

                override fun scheduleWithFixedDelay(
                    command: Runnable,
                    initialDelay: Long,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = error("not used")
            }
    }

    private class CapturingFuture(
        private val onCancel: () -> Unit,
    ) : ScheduledFuture<Any?> {
        override fun compareTo(other: Delayed?): Int = 0

        override fun getDelay(unit: TimeUnit): Long = 0L

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            onCancel()
            return true
        }

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = false

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitSpotBrokerReconcilerIntegrationTest`
Expected: FAIL — `BybitSpotBroker` does not accept `pollExecutor`; does not call reconcile at init.

- [ ] **Step 3: Modify `BybitSpotBroker`**

Open `src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt`. Make the following changes:

a) Add new constructor parameters (with defaults):

```kotlin
class BybitSpotBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val recoveryWindowMs: Long = 5 * 60_000L,
    private val pollIntervalMs: Long = 30_000L,
    pollExecutor: java.util.concurrent.ScheduledExecutorService? = null,
) : Broker {
```

b) Construct the reconciler. Locate the existing `init {}` block where `BybitStateRecovery` is created and `transport.onReconnect` is registered. After the `transport.onReconnect { recovery.reconcile() }` line, add:

```kotlin
        // 7g — initial sync at startup
        recovery.reconcile()

        // 7g — periodic poll loop
        reconciler =
            if (pollExecutor != null) {
                com.qkt.common.net.PeriodicReconciler(
                    intervalMs = pollIntervalMs,
                    action = { recovery.reconcile() },
                    executor = pollExecutor,
                )
            } else {
                com.qkt.common.net.PeriodicReconciler(
                    intervalMs = pollIntervalMs,
                    action = { recovery.reconcile() },
                )
            }
        reconciler.start()
```

c) Add a private field above the `init {}` block:

```kotlin
private val reconciler: com.qkt.common.net.PeriodicReconciler
```

d) Add or extend `close()`:

```kotlin
override fun close() {
    reconciler.stop()
    // existing close logic, if any
}
```

(If `Broker` interface doesn't already declare `close()`, this addition is a separate concern — confirm by searching; the existing 7e impl might not have it. If not present, add `override fun close()` and ensure `Broker` interface declares it as well, or add as a public method on `BybitSpotBroker` directly.)

Search:

```bash
grep -n 'fun close' src/main/kotlin/com/qkt/broker/Broker.kt src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt
```

If `Broker` does not declare `close()`, add the method on `BybitSpotBroker` as `fun close()` (no `override`). Tests reference it directly.

- [ ] **Step 4: Run integration test**

Run: `./gradlew test --tests com.qkt.broker.bybit.BybitSpotBrokerReconcilerIntegrationTest`
Expected: PASS, 3 tests green.

- [ ] **Step 5: Run full test suite to confirm no regression**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. The existing `BybitSpotBrokerTest` may need adjustment because the broker now calls `reconcile()` at construction — meaning `FakeBybitClient.responses` must contain replies for the three REST paths in any test that constructs the broker. Search and fix:

```bash
grep -rn 'BybitSpotBroker(' src/test/kotlin/com/qkt/broker/bybit
```

For each test that constructs the broker, ensure these three responses are seeded:

```kotlin
client.responses["/v5/order/realtime"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
client.responses["/v5/execution/list"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
client.responses["/v5/account/wallet-balance"] = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
```

Re-run `./gradlew test` until green.

- [ ] **Step 6: Run ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt \
        src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerReconcilerIntegrationTest.kt \
        src/test/kotlin/com/qkt/broker/bybit/  # any tests fixed up in step 5
git commit -m "feat(broker): wire PeriodicReconciler into BybitSpotBroker with initial reconcile"
```

---

## Task 7: Full build + verify Phase 7e/7f invariants preserved

- [ ] **Step 1: ktlintFormat**

Run: `./gradlew ktlintFormat`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. Tests count should be ~543 (527 from 7f + ~16 new from 7g — `PeriodicReconciler`: 6, `BybitBalanceTranslator`: 4, `BrokerEvent`: 2, `BybitClientBalances`: 2, `BybitStateRecovery` extension: 4, `BybitSpotBrokerReconcilerIntegration`: 3 = 21 — adjust expectation to ~548).

- [ ] **Step 3: Demo invariant**

Run: `./gradlew run 2>&1 | grep -cE 'FILLED|REJECTED'`
Expected: `10`.

- [ ] **Step 4: Commit any ktlint reformats**

```bash
git status --short
# If anything modified by ktlintFormat:
git add <reformatted files>
git commit -m "style: ktlintFormat after 7g additions"
```

---

## Task 8: Phase 7g changelog

**Files:**
- Create: `docs/phases/phase-7g-reconciliation-and-balances.md`

- [ ] **Step 1: Write the changelog**

Use the format from `docs/phases/phase-7f-broker-resilience.md` as a template. Required sections (per qkt skill §6):

1. **Summary** — 2–4 sentences.
2. **What's new** — bullet list per spec §2 goals.
3. **Migration from previous phase** — table (none breaking, but document `BrokerEvent.OrderEvent` marker addition + `BalancesUpdated`).
4. **Usage cookbook** — at least 5 worked examples covering:
   - Construct `BybitSpotBroker` with default poll cadence
   - Custom `pollIntervalMs` for high-frequency strategy
   - Subscribe to `BalancesUpdated` for sizing decisions
   - Subscribe to `BrokerEvent.OrderEvent` (the new marker) to handle "any order event"
   - Reuse `PeriodicReconciler` in a future broker
5. **Testing patterns** — `TickScheduler` shape, `FakeBybitClient.balances`, response seeding for the three reconcile paths.
6. **Known limitations** — list (defer derivatives, no retry budget on poll failures, etc.).
7. **References** — spec, plan, Bybit V5 wallet-balance docs.

The file should be 200–500 lines. Match the prose style of 7f.

- [ ] **Step 2: Commit**

```bash
git add docs/phases/phase-7g-reconciliation-and-balances.md
git commit -m "docs: add phase 7g changelog"
```

---

## Task 9: Final verification + finishing-a-development-branch

- [ ] **Step 1: Verify branch state**

Run:

```bash
git log --oneline main..HEAD
git status --short
./gradlew check
```

Expected:
- 8–9 commits ahead of main (spec, plan, T1, T2, T3, T4, T5, T6, ktlint, changelog).
- Clean working tree (or only `?? tt.txt`).
- BUILD SUCCESSFUL.

- [ ] **Step 2: Use finishing-a-development-branch skill**

Announce: "I'm using the finishing-a-development-branch skill to complete this work."

Follow the skill: present 4 options (merge / push+PR / keep / discard) and execute the chosen one. Default for this project: **option 1 (merge `--no-ff` to main, delete branch)**, matching 7e and 7f.

Merge commit message: `merge: phase 7g periodic reconciliation and balance polling`.

Post-merge:

```bash
git checkout main
git log --oneline -5
./gradlew check
```

Expected: BUILD SUCCESSFUL.

---

## Self-Review Checklist

Before marking this plan complete, the worker should verify:

- [ ] Every type, method, and field referenced in a later task is defined in an earlier task.
- [ ] No "TBD", "TODO", "fill in", or "similar to above" text in any step.
- [ ] Every code step shows the actual code, not a description.
- [ ] Every test step has both the test code AND the verification command.
- [ ] Every commit step has the exact `git commit -m` line.
- [ ] All files referenced exist after their creating task; modifications happen only on existing files.
- [ ] Spec coverage:
  - Spec §5 PeriodicReconciler → T1.
  - Spec §6 BrokerEvent.OrderEvent + BalancesUpdated → T3.
  - Spec §7 reconcileBalances → T5.
  - Spec §8 BybitClient.balances + updateBalances → T4.
  - Spec §9 BybitSpotBroker lifecycle → T6.
  - Spec §10 race-1 lock → T5 step 3 (synchronized).
  - Spec §13 testing → T1, T5, T6.
  - Spec §15 migration → T8 changelog.
