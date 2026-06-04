# Order Lifecycle GC + Live Trigger Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound `OrderManager`'s per-tick work and retained memory so a long-running daemon doesn't slow down or leak as it trades, and a frequently-trading backtest stays linear in tick count.

**Architecture:** Two independent mechanisms inside `OrderManager`. (1) A `liveOrderIds` index (non-terminal order ids) that the per-tick `evaluateTriggers` iterates instead of the full `orders` map — fixes speed. (2) A garbage collector: when an order goes terminal it's queued; a drain at the end of each `evaluateTriggers` reclaims it from `orders` and the order-keyed satellite maps, but only if no active structure (`timeExits` target, `stacks`) still references it — fixes memory. The GC only ever deletes dead, unreferenced orders, so trading behaviour and backtest determinism are unchanged.

**Tech Stack:** Kotlin, JUnit 5, AssertJ, Gradle. Tests run via `./gradlew test --tests '<FQN>'`; the throughput stress test via `./gradlew test -PincludeTags=stress --tests '<FQN>'`.

**Spec:** `docs/superpowers/specs/2026-06-04-issue255-order-lifecycle-gc-design.md`

**Branch:** `issue255-order-gc` (already created off `dev`, spec committed).

---

## File Structure

- **Modify** `src/main/kotlin/com/qkt/app/OrderManager.kt` — add `liveOrderIds` index + maintenance, switch `evaluateTriggers` to iterate it (Task 1); add the GC queue, reachability predicate, reclaim, and drain (Task 3).
- **Modify** `src/test/kotlin/com/qkt/backtest/BacktestThroughputStressTest.kt` — fix the BigDecimal-scale tick generator (Task 2).
- **Create** `src/test/kotlin/com/qkt/app/OrderManagerLiveIndexTest.kt` — index behaviour + determinism parity (Task 1).
- **Create** `src/test/kotlin/com/qkt/app/OrderManagerGcTest.kt` — reclamation + reachability safety (Task 3).

`OrderManager.kt` is already 1431 lines; this change adds a cohesive ~60 lines and does not warrant a split.

---

## Task 1: Live trigger index (the speed fix)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (field near line 55; `track` ~897; `update` ~902; restore insert ~191; OTO remove ~775; `evaluateTriggers` ~1086)
- Test: `src/test/kotlin/com/qkt/app/OrderManagerLiveIndexTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/app/OrderManagerLiveIndexTest.kt`:

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

class OrderManagerLiveIndexTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun stop(
        id: String,
        symbol: String,
        trigger: String,
    ) = OrderRequest.Stop(
        id = id,
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        stopPrice = Money.of(trigger),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `a pending order still fires after many finished orders precede it`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        // LogBroker has no STOP capability, so Stop orders are held PENDING and fire on a tick.
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        // Many market orders that fill immediately (become terminal), then one resting stop.
        repeat(50) { i ->
            om.submit(
                OrderRequest.Market(
                    id = "done-$i",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )
            bus.publish(BrokerEvent.OrderFilled("done-$i", "done-$i", Money.of("1.10"), Money.of("1"), 0L))
        }
        om.submit(stop("rest", "EURUSD", "1.20"))
        assertThat(om.getOrder("rest")!!.state).isEqualTo(OrderState.PENDING)

        // Price crosses the stop -> it must fire even though 50 finished orders precede it.
        bus.publish(TickEvent(Tick("EURUSD", Money.of("1.25"), 1L)))

        assertThat(om.getOrder("rest")!!.state).isNotEqualTo(OrderState.PENDING)
    }
}
```

- [ ] **Step 2: Run the test to confirm it passes against current code (characterization)**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerLiveIndexTest'`
Expected: PASS (current code already fires the stop — this test pins the behaviour the index must preserve). If the exact `BrokerEvent.OrderFilled` / `TickEvent` / `OrderRequest.Stop` constructor signatures differ, fix the test to compile against the real signatures (check `src/main/kotlin/com/qkt/events/BrokerEvent.kt`, `events/TickEvent.kt`, `execution/OrderRequest.kt`) before proceeding — it must pass now so it can guard the refactor.

- [ ] **Step 3: Add the `liveOrderIds` field**

In `OrderManager.kt`, immediately after the `orders` field (line ~55):

```kotlin
    private val orders: MutableMap<String, ManagedOrder> = mutableMapOf()

    /**
     * Ids of orders that are not yet terminal. The per-tick [evaluateTriggers] scan walks this
     * instead of every order ever created, so its cost tracks live orders, not all-time orders.
     * A LinkedHashSet populated in [track] order keeps iteration order identical to [orders]
     * (a LinkedHashMap), so trigger-firing order is unchanged.
     */
    private val liveOrderIds: LinkedHashSet<String> = LinkedHashSet()
```

- [ ] **Step 4: Add the index maintenance helper and wire the four mutation sites**

In `OrderManager.kt`, add the helper next to `track`/`update` (~line 896):

```kotlin
    /** Keep [liveOrderIds] in sync: a non-terminal order is live, a terminal one is not. */
    private fun indexLive(managed: ManagedOrder) {
        if (managed.state.isTerminal) liveOrderIds.remove(managed.id) else liveOrderIds.add(managed.id)
    }
```

Change `track` (~897):

```kotlin
    private fun track(managed: ManagedOrder) {
        orders[managed.id] = managed
        indexLive(managed)
        persistAll()
    }
```

Change `update` (~902) so the index follows every state transition:

```kotlin
    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) {
        orders[id]?.let {
            val updated = change(it)
            orders[id] = updated
            indexLive(updated)
        }
        persistAll()
    }
```

In `restore` (~191), after `orders[leg.clientOrderId] = managed`:

```kotlin
                    orders[leg.clientOrderId] = managed
                    indexLive(managed)
                    siblings[leg.clientOrderId] = leg.siblingIds
```

In the OTO-expansion path (~775), after `orders.remove(req.id)`:

```kotlin
        orders.remove(req.id)
        liveOrderIds.remove(req.id)
        return submit(oto)
```

- [ ] **Step 5: Switch `evaluateTriggers` to iterate the index**

In `evaluateTriggers` (~1086), take one ordered snapshot of live orders at the top and use it for all three scans. Replace the three `orders.values...` reads (the trailing-HWM loop ~1088, the GTD sweep ~1099, and the trigger-hit filter ~1126) so they read from the snapshot:

```kotlin
    private fun evaluateTriggers(tick: Tick) {
        lastObservedPrice[tick.symbol] = tick.price
        // One ordered snapshot of non-terminal orders. Mirrors the previous `orders.values.toList()`
        // snapshot semantics (handlers below mutate state), but scoped to live orders only.
        val live = liveOrderIds.mapNotNull { orders[it] }

        for (managed in live) {
            if (managed.state != OrderState.PENDING) continue
            if (managed.request.symbol != tick.symbol) continue
            updateTrailingHwm(managed, tick.price)
        }

        if (!broker.supportsNativeGtd) {
            val nowMs = clock.now()
            for (managed in live) {
                if (managed.state.isTerminal) continue
                if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
                val deadline = managed.request.expiresAt ?: continue
                if (nowMs > deadline) cancel(managed.id)
            }
        }

        val now = clock.now()
        val expired =
            timeExits.values
                .filter { now >= it.deadline.toEpochMilli() }
                .toList()
        for (te in expired) {
            timeExits.remove(te.id)
            handleTimeExitExpiry(te)
        }

        val nowEpoch = clock.now()
        for (state in stacks.all()) {
            val deadline = state.deadlineEpochMs ?: continue
            if (nowEpoch < deadline) continue
            cancelStackPending(state.id)
            stacks.terminate(state.id)
        }

        val triggered: List<ManagedOrder> =
            live
                .filter { it.state == OrderState.PENDING }
                .filter { it.request.symbol == tick.symbol }
                .filter { triggerHit(it, tick.price) }
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
    }
```

(The `timeExits` and `stacks` loops are unchanged — they already iterate bounded, pruned structures.)

- [ ] **Step 6: Run the index test + the full OrderManager suite**

Run: `./gradlew test --tests 'com.qkt.app.OrderManager*' --tests 'com.qkt.app.OrderManagerLiveIndexTest'`
Expected: PASS — every existing OrderManager test (OCO, OTO, trailing, stacks, restore) plus the new one. These existing tests are the determinism/behaviour guard: identical trigger firing proves the index is behaviour-preserving.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerLiveIndexTest.kt
git commit -m "perf(orders): scan a live-order index per tick instead of all orders"
```

---

## Task 2: Fix the throughput stress generator and prove the speed fix end-to-end

**Files:**
- Modify: `src/test/kotlin/com/qkt/backtest/BacktestThroughputStressTest.kt` (`generateTicks` ~64-83; import ~9)

- [ ] **Step 1: Replace the BigDecimal price walk with a Double walk**

In `generateTicks`, swap the scale-growing BigDecimal walk for a Double walk:

```kotlin
    private fun generateTicks(): List<Tick> {
        val random = java.util.Random(seed)
        val ticks = ArrayList<Tick>(tickCount)
        // Walk in Double, not BigDecimal: a BigDecimal walk grows its scale a few digits every
        // tick (each multiply accumulates precision), so generating a million ticks slows to a
        // crawl. Double is O(1) and plenty for a synthetic price.
        var price = 50_000.0
        val tickInterval = 60_000L / 10L // 10 ticks per minute -> 1 candle per 60_000ms
        for (i in 0 until tickCount) {
            // Walk price by up to +/-0.05% per tick.
            val deltaBps = (random.nextInt(11) - 5) // -5..5
            price += price * deltaBps / 10_000.0
            if (price <= 0) price = 50_000.0
            ticks.add(
                Tick(
                    symbol = symbol,
                    price = Money.of(String.format(java.util.Locale.ROOT, "%.2f", price)),
                    timestamp = i * tickInterval,
                ),
            )
        }
        return ticks
    }
```

- [ ] **Step 2: Remove the now-unused BigDecimal import**

Delete line 9 of `BacktestThroughputStressTest.kt`:

```kotlin
import java.math.BigDecimal
```

- [ ] **Step 3: Run the stress test and confirm it completes and meets the floor**

Run: `./gradlew test -PincludeTags=stress --tests 'com.qkt.backtest.BacktestThroughputStressTest'`
Expected: PASS within ~30s. Stdout prints `BacktestThroughputStress: 1000000 ticks in X.XXs -> N ticks/s (target >= 50000)` with N >= 50000. The combination of the Task 1 index (bounded per-tick scan) plus this generation fix is what makes it complete — before, generation alone never finished. If N falls just under 50000, do NOT lower the floor blindly; first confirm via the printed time that the run is linear (no quadratic tail), then it is a hardware-tuning call to raise/lower `minThroughputTicksPerSec` — surface the number to elitekaycy rather than silently editing the floor.

- [ ] **Step 4: Run ktlint on the changed test source**

Run: `./gradlew ktlintTestSourceSetCheck`
Expected: BUILD SUCCESSFUL (catches spacing/formatting before CI does).

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/com/qkt/backtest/BacktestThroughputStressTest.kt
git commit -m "fix(test): bound throughput stress tick generation with a double walk"
```

---

## Task 3: Order GC (the memory fix)

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (fields ~55; `update` ~902; `evaluateTriggers` end ~1133)
- Test: `src/test/kotlin/com/qkt/app/OrderManagerGcTest.kt`

- [ ] **Step 1: Write the failing reclamation test**

Create `src/test/kotlin/com/qkt/app/OrderManagerGcTest.kt`:

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
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerGcTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun market(id: String) =
        OrderRequest.Market(
            id = id,
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private fun tick(price: String): TickEvent = TickEvent(Tick("EURUSD", Money.of(price), 1L))

    @Test
    fun `a finished unreferenced order is reclaimed after a tick`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(market("c1"))
        bus.publish(BrokerEvent.OrderFilled("c1", "c1", Money.of("1.10"), Money.of("1"), 0L))
        assertThat(om.getOrder("c1")).isNotNull() // present while terminal-but-unswept

        bus.publish(tick("1.11")) // a tick drives the GC drain

        assertThat(om.getOrder("c1")).isNull() // reclaimed
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerGcTest'`
Expected: FAIL — `getOrder("c1")` is still non-null because nothing reclaims terminal orders yet.

- [ ] **Step 3: Add the GC queue, reachability predicate, reclaim, and drain**

In `OrderManager.kt`, add the queue field after `liveOrderIds`:

```kotlin
    /** Ids of orders that have gone terminal and await reclamation once nothing references them. */
    private val gcQueue: ArrayDeque<String> = ArrayDeque()
```

Add the GC methods next to `indexLive` (~896):

```kotlin
    /**
     * True while some active structure still points at [id], so reclaiming it would break a
     * later lookup: a pending timed-exit whose target is this order, or an active stack that
     * owns it as the parent, layer-one, or a pending/filled/closed layer. Per-order satellite
     * data (siblings, trailing state) is NOT a reference — it is read synchronously during the
     * order's own terminal transition and evicted on reclaim, never read afterwards.
     */
    private fun isReferenced(id: String): Boolean {
        if (timeExits.values.any { it.target.id == id }) return true
        for (s in stacks.all()) {
            if (id == s.id || id == s.layerOneOrderId) return true
            if (id in s.pendingLayerIds || id in s.filledLayerIds || id in s.closedLayerIds) return true
        }
        return false
    }

    /** Drop a dead, unreferenced order and all its order-keyed satellite state. */
    private fun reclaim(id: String) {
        orders.remove(id)
        liveOrderIds.remove(id)
        trailingHwm.remove(id)
        armedTrailArmed.remove(id)
        siblings.remove(id)
        scaleOutLegs.remove(id)
        pendingChildren.remove(id)
    }

    /**
     * Reclaim terminal orders that nothing references. Processes each queued id once per drain;
     * a still-referenced id is re-queued for a later pass. Only removes dead, unreferenced
     * orders, so it can never change a trading decision.
     */
    private fun runGc() {
        repeat(gcQueue.size) {
            val id = gcQueue.removeFirst()
            val managed = orders[id]
            when {
                managed == null -> Unit // already gone
                !managed.state.isTerminal -> Unit // no longer terminal; will re-queue if it goes terminal again
                isReferenced(id) -> gcQueue.addLast(id)
                else -> reclaim(id)
            }
        }
    }
```

Enqueue on terminal transition — extend `update` (from Task 1) to queue the id when it becomes terminal:

```kotlin
    private fun update(
        id: String,
        change: (ManagedOrder) -> ManagedOrder,
    ) {
        orders[id]?.let {
            val updated = change(it)
            orders[id] = updated
            indexLive(updated)
            if (updated.state.isTerminal) gcQueue.addLast(id)
        }
        persistAll()
    }
```

Drain at the very end of `evaluateTriggers` (after the `triggered` loop):

```kotlin
        for (managed in triggered) {
            fireFallbackTrigger(managed, tick.price)
        }
        runGc()
    }
```

- [ ] **Step 4: Run the reclamation test**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerGcTest'`
Expected: PASS — the filled order is gone after a tick.

- [ ] **Step 5: Add the reachability-safety tests (the critical cases)**

Append to `OrderManagerGcTest.kt`. These prove the GC never reclaims an order another structure still needs. Build them against the real OCO/stack/time-exit entry points used by the existing `OrderManagerOcoTest` / `StackBacktestTest` (read those for the exact `OrderRequest.OCO` / `OrderRequest.Stack` / `OrderRequest.TimeExit` construction and fill/cancel event shapes), then assert:

```kotlin
    @Test
    fun `a filled order held by a pending timed-exit is retained until the exit fires`() {
        // Arrange: submit an entry that fills, with a TimeExit (CLOSE_AT_MARKET) whose deadline
        // is in the future. Model it the way OrderManagerOcoTest / the TimeExit tests do.
        // Act 1: fill the entry, then publish a tick BEFORE the deadline.
        // Assert 1: getOrder(entryId) is still non-null — the pending time-exit references it.
        // Act 2: advance the clock past the deadline, publish a tick (fires the exit, then GC).
        // Assert 2: after the exit has fired and removed itself, a further tick reclaims the
        //           entry -> getOrder(entryId) is null.
    }

    @Test
    fun `an OCO sibling is cancelled correctly even though GC runs between fill and cancel`() {
        // Arrange: submit an OCO pair (leg1/leg2) the way OrderManagerOcoTest does.
        // Act: fill leg1 (synchronously cancels leg2), then publish ticks to drive GC.
        // Assert: leg2 ended CANCELLED (the sibling link was read at fill time, before GC),
        //         and both legs are eventually reclaimed (getOrder null) once neither is
        //         referenced.
    }
```

Fill in each body using the same construction the sibling test files use. The assertions above are the contract; the arrange/act wiring must mirror existing tests so it exercises the real code paths (no mocks).

- [ ] **Step 6: Run the full OrderManager + backtest suites**

Run: `./gradlew test --tests 'com.qkt.app.OrderManager*' --tests 'com.qkt.backtest.*'`
Expected: PASS — GC tests plus every existing OCO/OTO/stack/trailing/restore/backtest test. Any failure here means the GC reclaimed something still in use; fix `isReferenced` to cover the missed structure before continuing.

- [ ] **Step 7: Run the full default test suite (determinism / no regressions)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. The existing backtest and parity tests are the determinism guard — identical trades before/after prove the GC is behaviour-neutral.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerGcTest.kt
git commit -m "perf(orders): reclaim terminal orders once nothing references them"
```

---

## Task 4: Verify memory is actually reclaimed under load

**Files:**
- Test: `src/test/kotlin/com/qkt/app/OrderManagerGcTest.kt` (one more test)

- [ ] **Step 1: Add a churn test asserting the order map stays bounded**

Append to `OrderManagerGcTest.kt`:

```kotlin
    @Test
    fun `repeated fills do not accumulate orders`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        repeat(5_000) { i ->
            om.submit(market("c$i"))
            bus.publish(BrokerEvent.OrderFilled("c$i", "c$i", Money.of("1.10"), Money.of("1"), 0L))
            bus.publish(tick("1.11")) // each tick drains the GC
        }

        // Every order filled and was unreferenced, so all should be reclaimed.
        assertThat(om.activeOrders()).isEmpty()
        // None of the 5000 ids remain retrievable.
        assertThat((0 until 5_000).count { om.getOrder("c$it") != null }).isEqualTo(0)
    }
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerGcTest'`
Expected: PASS — `orders` does not grow with the 5000 filled orders.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/qkt/app/OrderManagerGcTest.kt
git commit -m "test(orders): order map stays bounded under repeated fills"
```

---

## Self-Review

**Spec coverage:**
- Live index (speed) → Task 1. ✓
- Determinism preserved (LinkedHashSet in track order) → Task 1 field doc + existing-suite guard (Step 6). ✓
- GC with to-clean queue → Task 3 (`gcQueue`, `runGc`). ✓
- Reachability rule (timeExits targets + stacks; siblings/trailing not roots) → Task 3 `isReferenced` + doc. ✓
- Satellite-map eviction on reclaim → Task 3 `reclaim`. ✓
- Per-tick drain cadence, determinism-neutral → Task 3 drain in `evaluateTriggers`. ✓
- `getOrder` returns null after reclaim (intended) → Task 3 reclamation test. ✓
- Reachability safety (OCO sibling, stack layer, time-exit target) → Task 3 Step 5. ✓
- Fold in BigDecimal→Double generation fix; stress test greens → Task 2. ✓
- Memory actually bounded under load → Task 4. ✓

**Placeholder scan:** Task 3 Step 5 leaves the OCO/time-exit test *bodies* as described contracts rather than literal code, because the exact `OrderRequest.OCO`/`Stack`/`TimeExit` and fill-event construction must be copied from the existing sibling tests (`OrderManagerOcoTest`, the stack/time-exit tests) to exercise the real paths — writing speculative constructors here would risk not compiling. The assertions (the contract) are explicit. This is the one acceptable deviation; every other step has literal code.

**Type consistency:** `liveOrderIds: LinkedHashSet<String>`, `gcQueue: ArrayDeque<String>`, helpers `indexLive(ManagedOrder)`, `isReferenced(String): Boolean`, `reclaim(String)`, `runGc()` — names and signatures match across Tasks 1 and 3. `evaluateTriggers` snapshot variable `live` is introduced in Task 1 and reused (drain appended) in Task 3. `ActiveStack` fields (`id`, `layerOneOrderId`, `pendingLayerIds`, `filledLayerIds`, `closedLayerIds`) match `StackTracker.kt`. `timeExits` value field `target.id` matches `OrderRequest.TimeExit`.

**Risk note for the implementer:** the entire safety of Task 3 rests on `isReferenced` being exhaustive. If any existing OCO/stack/time-exit test fails after Step 3, the predicate is missing a structure — fix the predicate, do not weaken the test. If the reachability cases prove too subtle to land safely, Tasks 1–2 already deliver the speed fix and green the stress test, and can ship without Task 3.
