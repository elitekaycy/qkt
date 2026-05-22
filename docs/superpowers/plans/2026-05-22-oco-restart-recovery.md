# OCO Restart Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On daemon restart, `OrderManager` restores its live OCO legs and sibling linkage from the persistor, and cancels any sibling whose pair filled while the daemon was down.

**Architecture:** Add a first-class `saveSiblings`/`loadSiblings` pair to `StatePersistor`. `OrderManager` gains a `restore(strategyIds)` method that rebuilds `orders` (from `loadPendingOrders`) and `siblings` (from `loadSiblings`), and subscribes to startup-recovery `PositionReconciled` events to cancel a sibling whose pair filled during downtime. `LiveSession` calls `restore` at startup.

**Tech Stack:** Kotlin, kotlinx.serialization (persistence DTOs), JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-05-22-oco-restart-recovery-design.md`

**Working branch:** `fix-oco-restart-linkage` (already exists; the spec is committed there). All tasks commit to it; one PR into `dev` at the end.

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `persistence/StatePersistor.kt` | Persistence contract. | Modify — add `saveSiblings`/`loadSiblings` (default no-op / empty). |
| `persistence/AsyncStatePersistor.kt` | Async-delegating persistor. | Modify — delegate both. |
| `persistence/FileStatePersistor.kt` | On-disk persistor. | Modify — real `saveSiblings`/`loadSiblings` + `SiblingsDto`. |
| `app/OrderManager.kt` | Order lifecycle + OCO linkage. | Modify — `persistAll` saves siblings; new `restore`; reconciliation subscription. |
| `app/LiveSession.kt` | Session startup. | Modify — call `orderManager.restore(...)`. |

---

## Task 1: `saveSiblings` / `loadSiblings` on the persistor contract

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/StatePersistor.kt`
- Modify: `src/main/kotlin/com/qkt/persistence/AsyncStatePersistor.kt`
- Test: `src/test/kotlin/com/qkt/persistence/NoopStatePersistorTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `NoopStatePersistorTest.kt`:

```kotlin
@Test
fun `siblings save is a no-op and load returns empty`() {
    val p = NoopStatePersistor()
    p.saveSiblings("alpha", mapOf("leg1" to listOf("leg2")))
    assertThat(p.loadSiblings("alpha")).isEmpty()
}
```

- [ ] **Step 2: Run it, expect failure**

Run: `./gradlew test --tests 'com.qkt.persistence.NoopStatePersistorTest' --console=plain`
Expected: FAIL — `saveSiblings` / `loadSiblings` unresolved.

- [ ] **Step 3: Add the methods to `StatePersistor`**

In `StatePersistor.kt`, after `loadPendingStacks`, add — with default bodies so existing implementers and test fakes are unaffected:

```kotlin
    /** Persist the OCO sibling linkage for [strategyId] — order id → its sibling ids. */
    fun saveSiblings(
        strategyId: String,
        siblings: Map<String, List<String>>,
    ) {}

    /** Restore the OCO sibling linkage for [strategyId]; empty when none persisted. */
    fun loadSiblings(strategyId: String): Map<String, List<String>> = emptyMap()
```

- [ ] **Step 4: Make `AsyncStatePersistor` delegate**

In `AsyncStatePersistor.kt`, alongside the other delegating overrides:

```kotlin
    override fun saveSiblings(
        strategyId: String,
        siblings: Map<String, List<String>>,
    ) = delegate.saveSiblings(strategyId, siblings)

    override fun loadSiblings(strategyId: String): Map<String, List<String>> =
        delegate.loadSiblings(strategyId)
```

(If `AsyncStatePersistor` batches writes on a worker thread for the other `save*` methods, mirror that pattern for `saveSiblings` instead of a direct delegate — check how `savePendingOrders` is handled and match it.)

- [ ] **Step 5: Run the test, expect pass**

Run: `./gradlew test --tests 'com.qkt.persistence.NoopStatePersistorTest' --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/persistence/StatePersistor.kt src/main/kotlin/com/qkt/persistence/AsyncStatePersistor.kt src/test/kotlin/com/qkt/persistence/NoopStatePersistorTest.kt
git commit -m "feat(persistence): add saveSiblings/loadSiblings to StatePersistor"
```

---

## Task 2: `FileStatePersistor` siblings file

**Files:**
- Modify: `src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt`
- Test: `src/test/kotlin/com/qkt/persistence/FileStatePersistorSiblingsTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `FileStatePersistorSiblingsTest.kt` — mirror the structure of `FileStatePersistorPendingOrdersTest.kt` (same `@TempDir`, same `FileStatePersistor(tmp)` construction):

```kotlin
package com.qkt.persistence

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileStatePersistorSiblingsTest {
    @Test
    fun `siblings round-trip through the file persistor`(
        @TempDir tmp: Path,
    ) {
        val p = FileStatePersistor(tmp)
        val siblings = mapOf("leg1" to listOf("leg2"), "leg2" to listOf("leg1"))

        p.saveSiblings("alpha", siblings)

        assertThat(p.loadSiblings("alpha")).isEqualTo(siblings)
    }

    @Test
    fun `loadSiblings returns empty when nothing was saved`(
        @TempDir tmp: Path,
    ) {
        assertThat(FileStatePersistor(tmp).loadSiblings("never-saved")).isEmpty()
    }
}
```

- [ ] **Step 2: Run it, expect failure**

Run: `./gradlew test --tests 'com.qkt.persistence.FileStatePersistorSiblingsTest' --console=plain`
Expected: FAIL — `loadSiblings` returns the default empty map (the round-trip assertion fails).

- [ ] **Step 3: Implement in `FileStatePersistor`**

Add a file constant in the `companion object` next to `PENDING_ORDERS_FILE`:

```kotlin
        const val SIBLINGS_FILE = "siblings.json"
```

Add a serializable DTO near the other DTOs at the bottom of the file (after `PendingOrdersDto`):

```kotlin
@Serializable
private data class SiblingsDto(
    val version: Int,
    val strategyId: String,
    val entries: List<SiblingEntryDto>,
)

@Serializable
private data class SiblingEntryDto(
    val orderId: String,
    val siblingIds: List<String>,
)
```

Override the two methods, mirroring `savePendingOrders` / `loadPendingOrders`:

```kotlin
    override fun saveSiblings(
        strategyId: String,
        siblings: Map<String, List<String>>,
    ) {
        val dto =
            SiblingsDto(
                version = SCHEMA_VERSION,
                strategyId = strategyId,
                entries = siblings.map { (id, sibs) -> SiblingEntryDto(id, sibs) },
            )
        runCatching { json.encodeToString(SiblingsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, SIBLINGS_FILE, it) }
            .onFailure { e -> log.warn("saveSiblings encode failed for $strategyId: ${e.message}") }
    }

    override fun loadSiblings(strategyId: String): Map<String, List<String>> {
        val raw = writer.read(strategyId, SIBLINGS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(SiblingsDto.serializer(), raw)
            } catch (e: SerializationException) {
                log.warn("loadSiblings parse failed for $strategyId: ${e.message}")
                return emptyMap()
            }
        if (dto.version != SCHEMA_VERSION) {
            log.warn("loadSiblings schema mismatch for $strategyId: ${dto.version} != $SCHEMA_VERSION")
            return emptyMap()
        }
        return dto.entries.associate { it.orderId to it.siblingIds }
    }
```

- [ ] **Step 4: Run the test, expect pass**

Run: `./gradlew test --tests 'com.qkt.persistence.FileStatePersistorSiblingsTest' --console=plain`
Expected: PASS, both tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt src/test/kotlin/com/qkt/persistence/FileStatePersistorSiblingsTest.kt
git commit -m "feat(persistence): persist OCO siblings to a file"
```

---

## Task 3: `OrderManager.persistAll()` saves siblings

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt` (the `persistAll()` method, ~line 796)

- [ ] **Step 1: Add the save to `persistAll()`**

`persistAll()` already builds `pendingByStrategy`. After the existing `for (sid in strategies)` loop that calls `savePendingOrders` / `saveBracketPairs`, add a per-strategy siblings save. Build a per-strategy partition of the global `siblings` map keyed by the owning order's `strategyId`:

```kotlin
            val siblingsByStrategy: MutableMap<String, MutableMap<String, List<String>>> = mutableMapOf()
            for ((orderId, siblingIds) in siblings) {
                val sid = orders[orderId]?.request?.strategyId ?: continue
                if (sid.isBlank()) continue
                siblingsByStrategy.getOrPut(sid) { mutableMapOf() }[orderId] = siblingIds
            }
            for (sid in (strategies + siblingsByStrategy.keys).toSet()) {
                persistor.saveSiblings(sid, siblingsByStrategy[sid] ?: emptyMap())
            }
```

Place this inside the existing `runCatching { ... }` block so a persistor failure stays swallowed (best-effort, consistent with the rest of `persistAll`).

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`. (Behaviour is covered end-to-end by Task 4's restore test.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "feat(app): persist OCO siblings on every state change"
```

---

## Task 4: `OrderManager.restore(strategyIds)`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Test: `src/test/kotlin/com/qkt/app/OrderManagerRestoreTest.kt` (create)

- [ ] **Step 1: Write the failing test**

Create `OrderManagerRestoreTest.kt`. It persists pending OCO legs + siblings with one `OrderManager`, then constructs a fresh one sharing the same persistor and asserts `restore` rebuilds state. Use `FileStatePersistor` on a `@TempDir` and `LogBroker` (mirror `OrderManagerTest`'s setup):

```kotlin
package com.qkt.app

import com.qkt.broker.LogBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.FileStatePersistor
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerRestoreTest {
    private fun newBus() = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun ocoLeg(id: String, side: Side) =
        OrderRequest.Stop(
            id = id,
            symbol = "XAUUSD",
            side = side,
            quantity = Money.of("1"),
            stopPrice = Money.of("2000"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    @Test
    fun `restore rebuilds pending orders and sibling linkage from the persistor`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        // First manager: submit an OCO so pending orders + siblings get persisted.
        val om1 = OrderManager(LogBroker(newBus(), FixedClock(0L)), newBus(), MarketPriceTracker(), FixedClock(0L), persistor)
        om1.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = ocoLeg("oco1-a", Side.BUY),
                leg2 = ocoLeg("oco1-b", Side.SELL),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        )
        // Fresh manager (simulates restart) sharing the same persistor.
        val om2 = OrderManager(LogBroker(newBus(), FixedClock(0L)), newBus(), MarketPriceTracker(), FixedClock(0L), persistor)
        assertThat(om2.getOrder("oco1-a")).isNull()

        om2.restore(listOf("alpha"))

        assertThat(om2.getOrder("oco1-a")).isNotNull
        assertThat(om2.getOrder("oco1-b")).isNotNull
        assertThat(om2.siblingsOf("oco1-a")).containsExactly("oco1-b")
    }
}
```

Note: the exact `OrderRequest.StandaloneOCO` / `OrderRequest.Stop` constructor parameters must match the real definitions in `src/main/kotlin/com/qkt/execution/OrderRequest.kt` — read that file and adjust field names/order before running. `siblingsOf` is a test-visibility accessor added in Step 3.

- [ ] **Step 2: Run it, expect failure**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerRestoreTest' --console=plain`
Expected: FAIL — `restore` and `siblingsOf` unresolved.

- [ ] **Step 3: Implement `restore` and a test accessor**

In `OrderManager.kt`, add a public method and a small test-visibility accessor near `getOrder`:

```kotlin
    /** Sibling order ids linked to [clientOrderId] — exposed for restart-recovery tests. */
    fun siblingsOf(clientOrderId: String): List<String> = siblings[clientOrderId].orEmpty()

    /**
     * Rebuild pending-order tracking and OCO sibling linkage from the persistor for
     * [strategyIds] — called once at session startup so cancel-on-fill / unwind work
     * across a daemon restart. Best-effort: a persistor failure leaves state empty.
     */
    fun restore(strategyIds: List<String>) {
        for (sid in strategyIds) {
            runCatching {
                val now = clock.now()
                for ((orderId, request) in persistor.loadPendingOrders(sid)) {
                    if (orders.containsKey(orderId)) continue
                    orders[orderId] =
                        ManagedOrder(
                            id = orderId,
                            request = request,
                            state = OrderState.PENDING,
                            createdAt = now,
                            lastUpdatedAt = now,
                        )
                }
                for ((orderId, siblingIds) in persistor.loadSiblings(sid)) {
                    siblings[orderId] = siblingIds
                }
            }.onFailure { e -> log.warn("[restore] failed for $sid: ${e.message}") }
        }
    }
```

`ManagedOrder` is `com.qkt.execution.ManagedOrder` — already imported by `OrderManager`. Confirm `OrderState.PENDING` is the right resting state for a venue-live pending order; if the codebase uses `WORKING` for placed-but-unfilled, use that instead.

- [ ] **Step 4: Run the test, expect pass**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerRestoreTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerRestoreTest.kt
git commit -m "feat(app): restore pending orders and OCO siblings on startup"
```

---

## Task 5: Downtime-fill reconciliation

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/OrderManager.kt`
- Test: `src/test/kotlin/com/qkt/app/OrderManagerRestoreTest.kt` (extend)

- [ ] **Step 1: Write the failing test**

Append to `OrderManagerRestoreTest.kt` — after `restore`, a `startup-recovery` `PositionReconciled` matching one leg's `(symbol, side)` should cancel the other leg:

```kotlin
    @Test
    fun `a startup-recovery position cancels the sibling of the leg that filled`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val om1 = OrderManager(LogBroker(newBus(), FixedClock(0L)), newBus(), MarketPriceTracker(), FixedClock(0L), persistor)
        om1.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = ocoLeg("oco1-a", Side.BUY),
                leg2 = ocoLeg("oco1-b", Side.SELL),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        )
        val bus = newBus()
        val om2 = OrderManager(LogBroker(bus, FixedClock(0L)), bus, MarketPriceTracker(), FixedClock(0L), persistor)
        om2.restore(listOf("alpha"))

        // A long position on XAUUSD recovered at startup => the BUY leg (oco1-a) filled.
        bus.publish(
            BrokerEvent.PositionReconciled(
                symbol = "XAUUSD",
                oldQty = null,
                newQty = Money.of("1"),
                oldAvgPx = null,
                newAvgPx = Money.of("2000"),
                source = "mt5:test",
                reason = "startup-recovery",
            ),
        )

        // The SELL sibling (oco1-b) must be cancelled.
        assertThat(om2.getOrder("oco1-b")!!.state).isEqualTo(OrderState.CANCELLED)
    }
```

- [ ] **Step 2: Run it, expect failure**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerRestoreTest' --console=plain`
Expected: FAIL — `oco1-b` is still `PENDING`; nothing reacts to the `PositionReconciled`.

- [ ] **Step 3: Subscribe to startup-recovery reconciliation**

In `OrderManager`'s `init` block, add a subscription:

```kotlin
        bus.subscribe<BrokerEvent.PositionReconciled> { e -> onStartupRecovery(e) }
```

Add the handler. It only acts on startup-recovery events; it finds a restored pending leg matching the recovered `(symbol, side)` and cancels that leg's siblings:

```kotlin
    private fun onStartupRecovery(e: BrokerEvent.PositionReconciled) {
        if (e.reason != "startup-recovery") return
        val filledSide = if (e.newQty.signum() >= 0) Side.BUY else Side.SELL
        val filledLeg =
            orders.values.firstOrNull { managed ->
                managed.state == OrderState.PENDING &&
                    managed.request.symbol == e.symbol &&
                    managed.request.side == filledSide &&
                    siblings.containsKey(managed.id)
            } ?: return
        siblings[filledLeg.id]?.forEach { sibId ->
            val sib = orders[sibId] ?: return@forEach
            if (!sib.state.isTerminal) {
                log.info("[restore] leg {} filled during downtime — cancelling sibling {}", filledLeg.id, sibId)
                cancel(sibId)
            }
        }
    }
```

`Side`, `BrokerEvent`, `OrderState` are already imported by `OrderManager`. Confirm `OrderState` exposes `isTerminal` (used elsewhere in the file — e.g. `activeOrders()`).

- [ ] **Step 4: Run the test, expect pass**

Run: `./gradlew test --tests 'com.qkt.app.OrderManagerRestoreTest' --console=plain`
Expected: PASS, all three tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerRestoreTest.kt
git commit -m "feat(app): cancel the sibling of an OCO leg filled during downtime"
```

---

## Task 6: Wire `restore` into `LiveSession` startup

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt` (near the `reconcileOrPreload` call, ~line 339)

- [ ] **Step 1: Call `restore` at startup**

`LiveSession.start()` calls `reconcileOrPreload(strategyPositions, broker)` at ~line 339. Immediately after it, restore the order manager from the persistor for this session's strategies:

```kotlin
        reconcileOrPreload(strategyPositions, broker)
        pipeline.orderManager.restore(strategies.map { it.first })
```

`pipeline.orderManager` is the same handle `LiveSession` already uses elsewhere (e.g. `pendingStackLayerInfos`). `strategies` is the `List<Pair<String, Strategy>>` constructor arg; `it.first` is the strategy id.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/app/LiveSession.kt
git commit -m "feat(app): restore the order manager on session startup"
```

---

## Task 7: Phase-doc note

**Files:**
- Modify: `docs/phases/phase-26a-pending-oco-and-clock.md` (or the most relevant pending-OCO phase changelog)

- [ ] **Step 1: Add a follow-up note**

Add a short "Follow-up" entry to the relevant pending-OCO phase changelog recording that OCO sibling linkage and pending orders are now restored on daemon restart (issue #46), with the `(symbol, side)` and venue-cancelled-leg limitations from the spec's "Known limitations".

- [ ] **Step 2: Commit**

```bash
git add docs/phases/
git commit -m "docs: note OCO restart recovery in the pending-OCO changelog"
```

---

## Self-review notes

- **Spec coverage:** §1 persistence → Tasks 1-2; §1 `persistAll` save → Task 3; §2 restore path → Task 4; §3 downtime-fill reconciliation → Task 5; LiveSession wiring → Task 6; known limitations recorded → Task 7. All spec sections covered.
- **Type consistency:** `saveSiblings`/`loadSiblings` signature (`Map<String, List<String>>`) is identical across Tasks 1, 2, 3. `restore(strategyIds: List<String>)` defined in Task 4, called in Task 6. `siblingsOf` defined in Task 4, used in Tasks 4-5.
- **Execution caveat:** Tasks 4-5 test code uses `OrderRequest.StandaloneOCO` / `OrderRequest.Stop` constructors — the executor must read `OrderRequest.kt` and match the real parameter names/order before the RED run. The plan flags this at each use.
