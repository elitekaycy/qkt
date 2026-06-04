# Chaos Broker-Recovery Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deterministic test suite that injects faults and asserts the engine reconnects, reconciles venue state, and never loses or duplicates an order — exercising the real `ReconnectSupervisor` and `BybitSpot/LinearStateRecovery`.

**Architecture:** Pure in-memory, deterministic. A synchronous `SyncScheduler` drives `ReconnectSupervisor` without wall-clock delays; `FakeBybitClient` returns canned reconcile responses and fires disconnect/reconnect callbacks; a `ChaosBroker` decorator injects submit faults; assertions are on `EventBus`-published `BrokerEvent`s. No network, no `Thread.sleep`. Runs in default CI.

**Tech Stack:** Kotlin, JUnit 5 + AssertJ, existing test fakes (`FakeBybitClient`, `FakeBroker`), `EventBus`, `FixedClock`.

Spec: `docs/superpowers/specs/2026-06-04-issue65-chaos-broker-recovery-design.md`

---

## File Structure

- `src/test/kotlin/com/qkt/chaos/SyncScheduler.kt` (new) — reusable synchronous `ScheduledExecutorService` test helper (extracted from `ReconnectSupervisorTest`'s private `TestScheduler`): records `scheduledDelays`, queues tasks, `runNext()` runs the next one.
- `src/test/kotlin/com/qkt/chaos/ChaosFaultModel.kt` (new) — fault knobs.
- `src/test/kotlin/com/qkt/chaos/ChaosBroker.kt` (new) — `Broker` decorator applying the fault model.
- `src/test/kotlin/com/qkt/chaos/ReconnectChaosTest.kt` (new) — reconnect scenarios.
- `src/test/kotlin/com/qkt/chaos/ReconcileChaosTest.kt` (new) — spot reconcile-on-reconnect, missed-fill dedup, vanished-order cancel.
- `src/test/kotlin/com/qkt/chaos/PositionReconcileChaosTest.kt` (new) — linear stale-position reconcile.
- `src/test/kotlin/com/qkt/chaos/SubmitFaultChaosTest.kt` (new) — order-flow submit faults.
- `src/test/kotlin/com/qkt/common/net/ReconnectSupervisorTest.kt` (modify) — migrate onto `SyncScheduler`.

Verified reference facts (read from the code):
- `ReconnectSupervisor(backoff, attemptReconnect: () -> Boolean, onReconnected: () -> Unit = {}, executor: ScheduledExecutorService = ...)`; `scheduleReconnect()`, `abort()`, `val isReconnecting: Boolean`. On a scheduled attempt it calls `attemptReconnect()`; on `true` it resets and calls `onReconnected()` once; on `false` it reschedules.
- `FakeBybitClient` (`BybitTransport`): `responses: MutableMap<String,String>` (canned per-path), `emitDisconnect(reason)`, `fireOnReconnect()` (fires `onReconnect` handlers), `onReconnect(handler)`, `getSigned(path, query)`/`postSigned(path, body)`.
- `BybitSpotStateRecovery(transport, bus, clock, getKnownOrders: () -> Map<String, ManagedOrderView>, lastFillTimeProvider: () -> Long, seenExecIds: MutableSet<String>)`; `reconcile()` calls `/v5/order/realtime`, `/v5/execution/list`, `/v5/account/wallet-balance`. `ManagedOrderView(clientOrderId, symbol, side, strategyId="")`. Publishes `BrokerEvent.OrderCancelled` (known order missing from open list), `BrokerEvent.OrderFilled` (new execution, deduped by `seenExecIds`), `BrokerEvent.BalancesUpdated`.
- Canned response shape (from `BybitSpotStateRecoveryTest`): `{"retCode":0,"retMsg":"OK","result":{"list":[...]}}`.
- `Broker`: `submit(OrderRequest): SubmitAck`, `cancel(orderId: String)`, `getOpenPositions(): Map<String, List<Position>> = emptyMap()`, `shutdown()`. `SubmitAck(clientOrderId, brokerOrderId?, accepted: Boolean, rejectReason: String?)`. `OrderRequest` has `.id: String`.
- `EventBus(clock, sequencer)`; `bus.subscribe<BrokerEvent.X> { ... }`; `bus.publish(event)`.

Run tests: `./gradlew test --tests "com.qkt.chaos.*"`; ktlint: `./gradlew ktlintFormat ktlintMainSourceSetCheck ktlintTestSourceSetCheck`. PRs target `dev`; commits subject-only.

---

## Task 1: Extract `SyncScheduler`, migrate `ReconnectSupervisorTest`

**Files:**
- Create: `src/test/kotlin/com/qkt/chaos/SyncScheduler.kt`
- Modify: `src/test/kotlin/com/qkt/common/net/ReconnectSupervisorTest.kt`

- [ ] **Step 1: Create `SyncScheduler`** by lifting the private `TestScheduler` + `NoOpFuture` from `ReconnectSupervisorTest` into a public reusable helper. Read the current `ReconnectSupervisorTest.kt` for the exact `ScheduledExecutorService` anonymous-object body (the `schedule(command, delay, unit)` override records `scheduledDelays` and queues `tasks`; `runNext()` pops and runs). Wrap it:

```kotlin
package com.qkt.chaos

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A synchronous [ScheduledExecutorService] for deterministic tests: it records the delays it was
 * asked to schedule and runs queued tasks only when [runNext] is called — no wall-clock waiting.
 * e.g. schedule a reconnect attempt, then `runNext()` to execute it immediately.
 */
class SyncScheduler {
    val scheduledDelays: MutableList<Long> = mutableListOf()
    private val tasks: MutableList<Pair<Long, Runnable>> = mutableListOf()
    var aborted: Boolean = false
        private set

    fun runNext() {
        val (_, task) = tasks.removeAt(0)
        task.run()
    }

    val pending: Int get() = tasks.size

    fun asExecutor(): ScheduledExecutorService =
        object : ScheduledExecutorService {
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> { aborted = true; return mutableListOf() }
            override fun isShutdown(): Boolean = aborted
            override fun isTerminated(): Boolean = aborted
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
            override fun <T> submit(task: Callable<T>): Future<T> = NoOpFuture.cast()
            override fun <T> submit(task: Runnable, result: T): Future<T> = NoOpFuture.cast()
            override fun submit(task: Runnable): Future<*> = NoOpFuture
            override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> = mutableListOf()
            override fun <T> invokeAll(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): MutableList<Future<T>> = mutableListOf()
            override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>): T = error("not used")
            override fun <T> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T = error("not used")
            override fun execute(command: Runnable) {}
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                scheduledDelays.add(unit.toMillis(delay)); tasks.add(unit.toMillis(delay) to command); return NoOpFuture
            }
            override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> = NoOpFuture.cast()
            override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> = NoOpFuture
            override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> = NoOpFuture
        }

    private object NoOpFuture : ScheduledFuture<Any?> {
        override fun compareTo(other: Delayed?): Int = 0
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = true
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        @Suppress("UNCHECKED_CAST")
        fun <T> cast(): ScheduledFuture<T> = this as ScheduledFuture<T>
    }
}
```

- [ ] **Step 2: Migrate `ReconnectSupervisorTest`** — delete its private `TestScheduler`/`NoOpFuture`, `import com.qkt.chaos.SyncScheduler`, and replace `TestScheduler()` usages with `SyncScheduler()` (the API — `scheduledDelays`, `runNext()`, `asExecutor()` — matches).

- [ ] **Step 3: Run** — `./gradlew test --tests "com.qkt.common.net.ReconnectSupervisorTest"`. Expected: PASS (behavior-preserving extraction). ktlintFormat + ktlintCheck.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/chaos/SyncScheduler.kt src/test/kotlin/com/qkt/common/net/ReconnectSupervisorTest.kt
git commit -m "test(common): extract SyncScheduler helper for deterministic reconnect tests"
```

---

## Task 2: `ChaosFaultModel` + `ChaosBroker`

**Files:**
- Create: `src/test/kotlin/com/qkt/chaos/ChaosFaultModel.kt`, `src/test/kotlin/com/qkt/chaos/ChaosBroker.kt`
- Test: `src/test/kotlin/com/qkt/chaos/ChaosBrokerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.chaos

import com.qkt.bus.EventBus
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderTypeCapability
import com.qkt.common.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChaosBrokerTest {
    private fun bus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private fun order(id: String) =
        OrderRequest.Market(id, "BYBIT_SPOT:BTCUSDT", Side.BUY, BigDecimal("1"), TimeInForce.GTC, 1_000L)

    @Test
    fun `passes submit through to the delegate when no fault`() {
        val delegate = FakeBroker(bus(), FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val chaos = ChaosBroker(delegate, ChaosFaultModel())
        val ack = chaos.submit(order("ORD-1"))
        assertThat(ack.accepted).isTrue()
        assertThat(delegate.submits).hasSize(1)
    }

    @Test
    fun `REJECT fault returns an un-accepted ack without touching the delegate`() {
        val delegate = FakeBroker(bus(), FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val chaos = ChaosBroker(delegate, ChaosFaultModel(submitFault = SubmitFault.REJECT))
        val ack = chaos.submit(order("ORD-1"))
        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("chaos")
        assertThat(delegate.submits).isEmpty()
    }

    @Test
    fun `THROW fault propagates an exception`() {
        val delegate = FakeBroker(bus(), FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val chaos = ChaosBroker(delegate, ChaosFaultModel(submitFault = SubmitFault.THROW))
        assertThatThrownBy { chaos.submit(order("ORD-1")) }.hasMessageContaining("chaos")
    }

    @Test
    fun `stalePositions hides the delegate's open positions`() {
        val delegate = FakeBroker(bus(), FixedClock(0L), setOf(OrderTypeCapability.MARKET))
        val chaos = ChaosBroker(delegate, ChaosFaultModel(stalePositions = true))
        assertThat(chaos.getOpenPositions()).isEmpty()
    }
}
```

Before writing, confirm `OrderRequest.Market(...)` factory signature and `TimeInForce` import by reading `src/main/kotlin/com/qkt/execution/OrderRequest.kt`; adjust the `order(...)` helper to the real signature if it differs.

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.chaos.ChaosBrokerTest"`. Expected: compile error (`ChaosBroker`/`ChaosFaultModel`/`SubmitFault` missing).

- [ ] **Step 3: Implement**

`ChaosFaultModel.kt`:
```kotlin
package com.qkt.chaos

/** Which fault to inject on `submit`. */
enum class SubmitFault { NONE, REJECT, THROW }

/** Immutable description of the faults a [ChaosBroker] injects. */
data class ChaosFaultModel(
    val submitFault: SubmitFault = SubmitFault.NONE,
    val stalePositions: Boolean = false,
)
```

`ChaosBroker.kt`:
```kotlin
package com.qkt.chaos

import com.qkt.broker.Broker
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderTypeCapability
import com.qkt.execution.SubmitAck
import com.qkt.positions.Position

/**
 * A [Broker] decorator that injects faults from a [ChaosFaultModel] for chaos tests, otherwise
 * delegating. e.g. `ChaosBroker(real, ChaosFaultModel(submitFault = REJECT))` makes every submit
 * come back un-accepted without reaching `real`.
 */
class ChaosBroker(
    private val delegate: Broker,
    private val faults: ChaosFaultModel,
) : Broker {
    override val name: String = "Chaos(${delegate.name})"
    override val capabilities: Set<OrderTypeCapability> = delegate.capabilities

    override fun submit(request: OrderRequest): SubmitAck =
        when (faults.submitFault) {
            SubmitFault.NONE -> delegate.submit(request)
            SubmitFault.REJECT -> SubmitAck(request.id, null, accepted = false, rejectReason = "chaos: injected rejection")
            SubmitFault.THROW -> throw RuntimeException("chaos: injected submit exception")
        }

    override fun cancel(orderId: String) = delegate.cancel(orderId)

    override fun getOpenPositions(): Map<String, List<Position>> =
        if (faults.stalePositions) emptyMap() else delegate.getOpenPositions()

    override fun shutdown() = delegate.shutdown()
}
```

Confirm the `Position` import path (`com.qkt.positions.Position`) and `SubmitAck` package by reading `Broker.kt`'s imports; adjust if they differ.

- [ ] **Step 4: Run to verify pass** — same command. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/qkt/chaos/ChaosFaultModel.kt src/test/kotlin/com/qkt/chaos/ChaosBroker.kt src/test/kotlin/com/qkt/chaos/ChaosBrokerTest.kt
git commit -m "test(broker): ChaosBroker fault-injecting decorator"
```

---

## Task 3: `ReconnectChaosTest`

**Files:**
- Test: `src/test/kotlin/com/qkt/chaos/ReconnectChaosTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.chaos

import com.qkt.common.net.ExponentialBackoff
import com.qkt.common.net.ReconnectSupervisor
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconnectChaosTest {
    @Test
    fun `recovers after two failed attempts and fires onReconnected exactly once`() {
        val sched = SyncScheduler()
        val attempts = AtomicInteger(0)
        val reconnected = AtomicInteger(0)
        val supervisor =
            ReconnectSupervisor(
                backoff = ExponentialBackoff(),
                attemptReconnect = { attempts.incrementAndGet() >= 3 }, // false, false, true
                onReconnected = { reconnected.incrementAndGet() },
                executor = sched.asExecutor(),
            )

        supervisor.scheduleReconnect()
        assertThat(supervisor.isReconnecting).isTrue()
        sched.runNext() // attempt 1 -> false -> reschedules
        sched.runNext() // attempt 2 -> false -> reschedules
        sched.runNext() // attempt 3 -> true  -> onReconnected

        assertThat(attempts.get()).isEqualTo(3)
        assertThat(reconnected.get()).isEqualTo(1)
        assertThat(supervisor.isReconnecting).isFalse()
        assertThat(sched.scheduledDelays).hasSizeGreaterThanOrEqualTo(3)
        assertThat(sched.scheduledDelays[1]).isGreaterThanOrEqualTo(sched.scheduledDelays[0]) // backoff grows
    }
}
```

- [ ] **Step 2: Run** — `./gradlew test --tests "com.qkt.chaos.ReconnectChaosTest"`. Expected: PASS. (If `ExponentialBackoff()` needs explicit args, read `ReconnectSupervisor.kt`'s default and mirror it.) ktlintFormat + ktlintCheck.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/chaos/ReconnectChaosTest.kt
git commit -m "test(common): chaos — reconnect recovers after transient failures"
```

---

## Task 4: `ReconcileChaosTest` (spot)

**Files:**
- Test: `src/test/kotlin/com/qkt/chaos/ReconcileChaosTest.kt`

Read `src/test/kotlin/com/qkt/broker/bybit/spot/BybitSpotStateRecoveryTest.kt` first — these scenarios reuse its exact setup (`FakeBybitClient`, `client.responses[path]`, `BybitSpotStateRecovery(...)`, the `{"retCode":0,...,"result":{"list":[...]}}` shapes) and add the chaos framing.

- [ ] **Step 1: Write the test**

```kotlin
package com.qkt.chaos

import com.qkt.broker.bybit.FakeBybitClient
import com.qkt.broker.bybit.spot.BybitSpotStateRecovery
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReconcileChaosTest {
    private fun bus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val emptyList = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""

    private fun recovery(
        client: FakeBybitClient,
        bus: EventBus,
        known: Map<String, BybitSpotStateRecovery.ManagedOrderView>,
        seen: MutableSet<String>,
    ) = BybitSpotStateRecovery(
        transport = client,
        bus = bus,
        clock = FixedClock(1_000_000L),
        getKnownOrders = { known },
        lastFillTimeProvider = { 0L },
        seenExecIds = seen,
    )

    @Test
    fun `reconcile runs when the transport reconnects and cancels a vanished order`() {
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyList // engine-known order is NOT here
        client.responses["/v5/execution/list"] = emptyList
        val bus = bus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        val rec =
            recovery(
                client, bus,
                known = mapOf("c1" to BybitSpotStateRecovery.ManagedOrderView("c1", "BYBIT_SPOT:BTCUSDT", Side.BUY)),
                seen = mutableSetOf(),
            )
        client.onReconnect { rec.reconcile() }

        client.fireOnReconnect() // simulate the private WS reconnecting

        assertThat(cancels.map { it.clientOrderId }).containsExactly("c1")
    }

    @Test
    fun `a fill missed while disconnected is replayed exactly once across two reconciles`() {
        // Build the execution-list response shape from BybitSpotStateRecoveryTest's missed-fill test.
        // Read that test for the exact `/v5/execution/list` JSON (execId, symbol, side, price, qty, execTime).
        val execResponse = bybitSpotExecutionListJson(execId = "E1")
        val client = FakeBybitClient()
        client.responses["/v5/order/realtime"] = emptyList
        client.responses["/v5/execution/list"] = execResponse
        val bus = bus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val seen = mutableSetOf<String>()
        val rec = recovery(client, bus, known = emptyMap(), seen = seen)

        rec.reconcile() // first reconcile -> publishes the missed fill
        rec.reconcile() // second reconcile, same response -> dedup, no new fill

        assertThat(fills).hasSize(1)
    }
}
```

Add a `private fun bybitSpotExecutionListJson(execId: String): String` helper that returns the same `/v5/execution/list` JSON shape `BybitSpotStateRecoveryTest`'s missed-fill test uses (copy its fields verbatim — execId, symbol `BTCUSDT`, side, execPrice, execQty, execTime within the recovery window).

- [ ] **Step 2: Run** — `./gradlew test --tests "com.qkt.chaos.ReconcileChaosTest"`. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/chaos/ReconcileChaosTest.kt
git commit -m "test(broker): chaos — reconcile replays missed fills once on reconnect"
```

---

## Task 5: `PositionReconcileChaosTest` (linear stale position)

**Files:**
- Test: `src/test/kotlin/com/qkt/chaos/PositionReconcileChaosTest.kt`

Spot has no positions; the stale-position scenario lives in the *linear* recovery. Read `src/test/kotlin/com/qkt/broker/bybit/linear/BybitLinearStateRecoveryTest.kt` for the `BybitLinearStateRecovery` constructor, its `/v5/position/list` canned response shape, and the `BrokerEvent.PositionReconciled` assertion — then mirror it with the chaos reconnect framing.

- [ ] **Step 1: Write the test** — construct `BybitLinearStateRecovery` with a `FakeBybitClient` whose `/v5/position/list` response reports a position the engine did not track; wire `client.onReconnect { recovery.reconcile() }`; `client.fireOnReconnect()`; assert a `BrokerEvent.PositionReconciled` is published for that symbol. Use `BybitLinearStateRecoveryTest`'s exact response JSON + constructor args (read it; do not invent the shape).

- [ ] **Step 2: Run** — `./gradlew test --tests "com.qkt.chaos.PositionReconcileChaosTest"`. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/chaos/PositionReconcileChaosTest.kt
git commit -m "test(broker): chaos — stale position re-syncs on reconnect"
```

---

## Task 6: `SubmitFaultChaosTest`

**Files:**
- Test: `src/test/kotlin/com/qkt/chaos/SubmitFaultChaosTest.kt`

- [ ] **Step 1: Write the test** — through `ChaosBroker(FakeBroker, ChaosFaultModel(submitFault = REJECT))`, submit an order and assert `SubmitAck.accepted == false` and that no `BrokerEvent.OrderFilled` is published; with `THROW`, assert the exception propagates. (This asserts the engine-facing contract under a venue-rejecting broker — the `OrderManager` REJECTED-state assertion is covered by existing `OrderManager` tests, so keep this test at the broker contract level to avoid duplicating the full pipeline wiring.)

```kotlin
package com.qkt.chaos

import com.qkt.broker.FakeBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.TimeInForce
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderTypeCapability
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubmitFaultChaosTest {
    @Test
    fun `a venue-rejecting broker yields an un-accepted ack and no fill`() {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val chaos = ChaosBroker(FakeBroker(bus, FixedClock(0L), setOf(OrderTypeCapability.MARKET)), ChaosFaultModel(submitFault = SubmitFault.REJECT))

        val ack =
            chaos.submit(
                OrderRequest.Market("ORD-1", "BYBIT_SPOT:BTCUSDT", Side.BUY, BigDecimal("1"), TimeInForce.GTC, 1_000L),
            )

        assertThat(ack.accepted).isFalse()
        assertThat(fills).isEmpty()
    }
}
```

- [ ] **Step 2: Run** — `./gradlew test --tests "com.qkt.chaos.SubmitFaultChaosTest"`. Expected: PASS. ktlintFormat + ktlintCheck.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/chaos/SubmitFaultChaosTest.kt
git commit -m "test(broker): chaos — venue rejection yields no fill"
```

---

## Final verification

- [ ] `./gradlew test --tests "com.qkt.chaos.*" --tests "com.qkt.common.net.ReconnectSupervisorTest"` — green.
- [ ] `./gradlew ktlintMainSourceSetCheck ktlintTestSourceSetCheck` — clean.
- [ ] PR to `dev` (`Refs #65` — #65 is the umbrella; feed-recovery, stress, and soak remain as follow-up sub-projects).

## Notes for the implementer

- **Read the precedent tests before writing canned JSON:** `BybitSpotStateRecoveryTest` (spot reconcile shapes) and `BybitLinearStateRecoveryTest` (position shape). Copy their response JSON verbatim — do not invent venue payloads.
- **Determinism:** every test uses `SyncScheduler` + `FixedClock` + `FakeBybitClient`/`FakeBroker`. No `Thread.sleep`, no real sockets.
- **No mocks:** assert on published `BrokerEvent`s and `isReconnecting`, never on "we called X".
- **Scope:** broker recovery only. The feed-recovery gap (a feed disconnect terminates the session), stress, and soak are separate #65 sub-projects with their own specs.
