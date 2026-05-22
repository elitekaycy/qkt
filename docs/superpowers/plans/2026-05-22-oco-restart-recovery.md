# OCO Restart Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On daemon restart, qkt restores its live OCO legs + sibling linkage from the persistor, re-seeds `MT5Broker`'s pending-order tracking from venue truth, and cancels any sibling whose pair filled during downtime — by feeding the missed `OrderFilled` into the existing cancel-on-fill path.

**Architecture:** Persistence carries OCO leg *identity* (`PersistedOcoLeg`: clientOrderId, broker ticket, strategyId, request, siblingIds). `OrderManager.restore` rebuilds `orders` (state `WORKING`) + `siblings`, then hands the legs to `Broker.recoverPendingOrders`. `MT5Broker` joins them to venue truth *by ticket*: still-pending legs re-seed `pendingByTicket`; filled legs get a real `OrderFilled` republished, which the existing `onFilled` handler turns into the sibling cancel.

**Tech Stack:** Kotlin, kotlinx.serialization, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-05-22-oco-restart-recovery-design.md`

**Working branch:** `fix-oco-restart-linkage`. One PR into `dev` at the end.

> **Note on prior work:** an earlier `saveSiblings`/`loadSiblings` attempt is uncommitted in the persistence files. Tasks 1–2 supersede it — overwrite those methods with `saveOcoLegs`/`loadOcoLegs`.

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `persistence/PersistedOcoLeg.kt` | OCO leg record value type. | Create |
| `persistence/StatePersistor.kt` | Persistence contract. | Modify — `saveOcoLegs`/`loadOcoLegs` |
| `persistence/NoopStatePersistor.kt` | In-memory persistor. | Modify |
| `persistence/AsyncStatePersistor.kt` | Async-delegating persistor. | Modify |
| `persistence/FileStatePersistor.kt` | On-disk persistor. | Modify — `oco-legs.json` |
| `app/OrderManager.kt` | Order lifecycle + OCO linkage. | Modify — `persistAll` + `restore` |
| `broker/Broker.kt` | Broker contract. | Modify — `recoverPendingOrders` default |
| `broker/mt5/OcoRecovery.kt` | Pure venue-join classifier. | Create |
| `broker/mt5/MT5Broker.kt` | MT5 connector. | Modify — `recoverPendingOrders` |
| `app/LiveSession.kt` | Session startup. | Modify — call `restore` |

---

## Task 1: `PersistedOcoLeg` + `saveOcoLegs`/`loadOcoLegs` contract

**Files:** create `persistence/PersistedOcoLeg.kt`; modify `StatePersistor.kt`, `NoopStatePersistor.kt`, `AsyncStatePersistor.kt`; test `NoopStatePersistorTest.kt`.

- [ ] **Step 1: Create the value type**

`src/main/kotlin/com/qkt/persistence/PersistedOcoLeg.kt`:

```kotlin
package com.qkt.persistence

import com.qkt.execution.OrderRequest

/**
 * A live OCO leg as persisted for restart recovery — its qkt identity, its venue
 * ticket ([brokerOrderId]), and the linkage needed to rebuild cancel-on-fill.
 */
data class PersistedOcoLeg(
    val clientOrderId: String,
    val brokerOrderId: String,
    val strategyId: String,
    val request: OrderRequest,
    val siblingIds: List<String>,
)
```

- [ ] **Step 2: Write the failing test** — append to `NoopStatePersistorTest.kt` (replacing any uncommitted `siblings` tests):

```kotlin
    @Test
    fun `oco legs round-trip`() {
        val persistor = NoopStatePersistor()
        val leg =
            PersistedOcoLeg(
                clientOrderId = "oco1-a",
                brokerOrderId = "12345",
                strategyId = "hedge",
                request = legRequest("oco1-a", Side.BUY),
                siblingIds = listOf("oco1-b"),
            )
        persistor.saveOcoLegs("hedge", listOf(leg))
        assertThat(persistor.loadOcoLegs("hedge")).containsExactly(leg)
    }

    @Test
    fun `loadOcoLegs returns empty when nothing persisted`() {
        assertThat(NoopStatePersistor().loadOcoLegs("absent")).isEmpty()
    }
```

Add a `legRequest` helper to the test class:

```kotlin
    private fun legRequest(id: String, side: Side) =
        OrderRequest.Stop(
            id = id,
            symbol = "XAUUSDm",
            side = side,
            quantity = BigDecimal("0.20"),
            stopPrice = BigDecimal("4700.0"),
            timeInForce = com.qkt.execution.TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "hedge",
        )
```

Adjust to the real `OrderRequest.Stop` signature (read `execution/OrderRequest.kt`).

- [ ] **Step 3: Run, expect failure** — `./gradlew test --tests 'com.qkt.persistence.NoopStatePersistorTest' --console=plain` — FAIL, unresolved `saveOcoLegs`.

- [ ] **Step 4: Add abstract methods to `StatePersistor`** (after `loadPendingStacks`, before `clearStrategy`):

```kotlin
    /** Persist the live OCO legs for [strategyId] — identity + linkage for restart recovery. */
    fun saveOcoLegs(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    )

    /** Restore the live OCO legs for [strategyId]; empty when none persisted. */
    fun loadOcoLegs(strategyId: String): List<PersistedOcoLeg>
```

- [ ] **Step 5: Implement in `NoopStatePersistor`** — add `var ocoLegs: List<PersistedOcoLeg> = emptyList()` to `StrategyState`; `saveOcoLegs` sets `stateFor(sid).ocoLegs = legs`; `loadOcoLegs` returns `state[sid]?.ocoLegs ?: emptyList()`.

- [ ] **Step 6: Implement in `AsyncStatePersistor`** — mirror `savePendingOrders`: `saveOcoLegs` snapshots (`legs.toList()`) and `submit("saveOcoLegs $strategyId") { delegate.saveOcoLegs(...) }`; `loadOcoLegs` delegates directly.

- [ ] **Step 7: Run, expect pass.** Commit:

```bash
git add src/main/kotlin/com/qkt/persistence/PersistedOcoLeg.kt src/main/kotlin/com/qkt/persistence/StatePersistor.kt src/main/kotlin/com/qkt/persistence/NoopStatePersistor.kt src/main/kotlin/com/qkt/persistence/AsyncStatePersistor.kt src/test/kotlin/com/qkt/persistence/NoopStatePersistorTest.kt
git commit -m "feat(persistence): add saveOcoLegs/loadOcoLegs to StatePersistor"
```

---

## Task 2: `FileStatePersistor` oco-legs file

**Files:** modify `FileStatePersistor.kt`; test `FileStatePersistorOcoLegsTest.kt` (create).

- [ ] **Step 1: Write the failing test** — `src/test/kotlin/com/qkt/persistence/FileStatePersistorOcoLegsTest.kt`, a `@TempDir` round-trip mirroring the existing pending-orders test: build a `PersistedOcoLeg` with an `OrderRequest.Stop`, `saveOcoLegs`, assert `loadOcoLegs` equals it; plus an empty-on-missing test.

- [ ] **Step 2: Run, expect failure** (round-trip assertion fails — default would not apply since the method is abstract; the test simply has no file yet).

- [ ] **Step 3: Implement.** Add `const val OCO_LEGS_FILE = "oco-legs.json"` to the companion. Add DTOs near `PendingOrderEntryDto`, reusing the existing `OrderRequestDto` + its `fromDomain`/`toDomain` (read `FileStatePersistor.kt` for the exact converter names used by `savePendingOrders`):

```kotlin
@Serializable
private data class OcoLegsDto(
    val version: Int,
    val strategyId: String,
    val legs: List<OcoLegDto>,
)

@Serializable
private data class OcoLegDto(
    val clientOrderId: String,
    val brokerOrderId: String,
    val strategyId: String,
    val request: OrderRequestDto,
    val siblingIds: List<String>,
)
```

`saveOcoLegs`/`loadOcoLegs` mirror `savePendingOrders`/`loadPendingOrders` exactly — schema-versioned, `runCatching` encode + `writer.write`, `SerializationException`-guarded decode, version-mismatch warning.

- [ ] **Step 4: Run, expect pass.** Commit:

```bash
git add src/main/kotlin/com/qkt/persistence/FileStatePersistor.kt src/test/kotlin/com/qkt/persistence/FileStatePersistorOcoLegsTest.kt
git commit -m "feat(persistence): persist OCO legs to a file"
```

---

## Task 3: `OrderManager.persistAll()` saves OCO legs

**Files:** modify `app/OrderManager.kt` (`persistAll()`, ~line 796).

- [ ] **Step 1: Add the save.** Inside the existing `runCatching` block, after the `siblings`→`BracketPair` loop, build a per-strategy partition of OCO legs and save it:

```kotlin
            val ocoLegsByStrategy: MutableMap<String, MutableList<com.qkt.persistence.PersistedOcoLeg>> =
                mutableMapOf()
            for ((legId, siblingIds) in siblings) {
                val managed = orders[legId] ?: continue
                if (managed.state.isTerminal) continue
                val ticket = managed.brokerOrderId ?: continue
                val sid = managed.request.strategyId
                if (sid.isBlank()) continue
                ocoLegsByStrategy.getOrPut(sid) { mutableListOf() }.add(
                    com.qkt.persistence.PersistedOcoLeg(
                        clientOrderId = legId,
                        brokerOrderId = ticket,
                        strategyId = sid,
                        request = managed.request,
                        siblingIds = siblingIds,
                    ),
                )
            }
            for (sid in (strategies + ocoLegsByStrategy.keys).toSet()) {
                persistor.saveOcoLegs(sid, ocoLegsByStrategy[sid] ?: emptyList())
            }
```

- [ ] **Step 2: Verify it compiles** — `./gradlew compileKotlin --console=plain`. Behaviour is covered by Task 4's restore test.

- [ ] **Step 3: Commit:**

```bash
git add src/main/kotlin/com/qkt/app/OrderManager.kt
git commit -m "feat(app): persist OCO legs on every state change"
```

---

## Task 4: `Broker.recoverPendingOrders` + `OrderManager.restore`

**Files:** modify `broker/Broker.kt`, `app/OrderManager.kt`; test `OrderManagerRestoreTest.kt` (create).

- [ ] **Step 1: Add the `Broker` method** — after `getOpenPositions()`, default no-op:

```kotlin
    /**
     * Re-establish venue-side tracking for OCO legs recovered from the persistor on
     * restart. Brokers join each [ManagedOrder] to live venue state by ticket and, for
     * a leg that filled while the daemon was down, republish its [OrderFilled].
     * Default no-op — only stateful venue connectors override.
     */
    fun recoverPendingOrders(orders: List<com.qkt.execution.ManagedOrder>) {}
```

- [ ] **Step 2: Write the failing test** — `OrderManagerRestoreTest.kt`. Persist an OCO via one `OrderManager`, then a fresh one `restore`s it. Use `FileStatePersistor` on `@TempDir`, `LogBroker`. The fresh manager must rebuild `orders` in state `WORKING` and `siblings`, and pass the legs to the broker. Capture the broker call with an `object : Broker by LogBroker(...)` wrapper or a recording stub. Assert: `getOrder("oco1-a")!!.state == WORKING`, `siblingsOf("oco1-a") == ["oco1-b"]`, broker received 2 legs.

Read `execution/OrderRequest.kt` for the real `StandaloneOCO`/`Stop` constructors. Note: for legs to persist with a `brokerOrderId`, the first manager's broker must accept them — `LogBroker` accepts and the legs reach `WORKING`; confirm by reading `LogBroker`. If `LogBroker` does not assign a `brokerOrderId`, persist the legs directly via `FileStatePersistor.saveOcoLegs` in the test setup instead of routing through `submit`.

- [ ] **Step 3: Run, expect failure** — `restore`/`siblingsOf` unresolved.

- [ ] **Step 4: Implement `restore` + `siblingsOf`** in `OrderManager`, near `getOrder`:

```kotlin
    /** Sibling order ids linked to [clientOrderId] — exposed for restart-recovery tests. */
    fun siblingsOf(clientOrderId: String): List<String> = siblings[clientOrderId].orEmpty()

    /**
     * Rebuild OCO leg tracking + sibling linkage from the persistor for [strategyIds],
     * then hand the legs to the broker so it can reconcile them against venue truth.
     * Called once at session startup. Best-effort: a persistor failure leaves state empty.
     */
    fun restore(strategyIds: List<String>) {
        val recovered = mutableListOf<ManagedOrder>()
        for (sid in strategyIds) {
            runCatching {
                for (leg in persistor.loadOcoLegs(sid)) {
                    if (orders.containsKey(leg.clientOrderId)) continue
                    val now = clock.now()
                    val managed =
                        ManagedOrder(
                            id = leg.clientOrderId,
                            request = leg.request,
                            state = OrderState.WORKING,
                            brokerOrderId = leg.brokerOrderId,
                            createdAt = now,
                            lastUpdatedAt = now,
                        )
                    orders[leg.clientOrderId] = managed
                    siblings[leg.clientOrderId] = leg.siblingIds
                    recovered += managed
                }
            }.onFailure { e -> log.warn("[restore] failed for {}: {}", sid, e.message) }
        }
        if (recovered.isNotEmpty()) {
            runCatching { broker.recoverPendingOrders(recovered) }
                .onFailure { e -> log.warn("[restore] broker recovery failed: {}", e.message) }
        }
    }
```

`ManagedOrder`, `OrderState` are imported by `OrderManager`. Confirm the `broker`, `persistor`, `clock`, `log` field names match the file.

- [ ] **Step 5: Run, expect pass.** Commit:

```bash
git add src/main/kotlin/com/qkt/broker/Broker.kt src/main/kotlin/com/qkt/app/OrderManager.kt src/test/kotlin/com/qkt/app/OrderManagerRestoreTest.kt
git commit -m "feat(app): restore OCO legs and linkage on startup"
```

---

## Task 5: `MT5Broker.recoverPendingOrders` — venue join

**Files:** create `broker/mt5/OcoRecovery.kt`; modify `broker/mt5/MT5Broker.kt`; test `broker/mt5/OcoRecoveryTest.kt` (create).

The venue join logic is extracted as a pure function so it is testable without faking `MT5Client` (a concrete HTTP class). `MT5Broker.recoverPendingOrders` is thin glue: fetch venue snapshots, call the classifier, apply actions.

- [ ] **Step 1: Write the failing test** — `OcoRecoveryTest.kt`. Given three `ManagedOrder`s with `brokerOrderId` "1", "2", "3" and venue snapshots where ticket 1 is still pending, ticket 2 is now a position, ticket 3 is in neither: assert `classifyOcoRecovery` returns one `Reseed` for the order with ticket 1 and one `EmitFill` (carrying the matching position) for ticket 2, and nothing for ticket 3.

- [ ] **Step 2: Run, expect failure** — `classifyOcoRecovery` unresolved.

- [ ] **Step 3: Create `OcoRecovery.kt`:**

```kotlin
package com.qkt.broker.mt5

import com.qkt.execution.ManagedOrder

/** What restart recovery must do for one OCO leg, given venue truth. */
internal sealed interface OcoRecoveryAction {
    /** Leg is still pending on the venue — re-seed broker tracking. */
    data class Reseed(
        val order: ManagedOrder,
        val ticket: Long,
    ) : OcoRecoveryAction

    /** Leg's ticket is now a position — it filled while down; republish the fill. */
    data class EmitFill(
        val order: ManagedOrder,
        val position: MT5Position,
    ) : OcoRecoveryAction
}

/**
 * Join recovered [orders] to venue truth by ticket. A leg still in [pendingTickets] is
 * re-seeded; a leg whose ticket is an open [positions] entry filled during downtime; a
 * leg in neither was cancelled/expired and is left for the pending poller.
 */
internal fun classifyOcoRecovery(
    orders: List<ManagedOrder>,
    pendingTickets: Set<Long>,
    positions: List<MT5Position>,
): List<OcoRecoveryAction> {
    val positionByTicket = positions.associateBy { it.ticket }
    return orders.mapNotNull { order ->
        val ticket = order.brokerOrderId?.toLongOrNull() ?: return@mapNotNull null
        when {
            ticket in pendingTickets -> OcoRecoveryAction.Reseed(order, ticket)
            positionByTicket.containsKey(ticket) ->
                OcoRecoveryAction.EmitFill(order, positionByTicket.getValue(ticket))
            else -> null
        }
    }
}
```

Confirm `MT5Position` exposes `ticket: Long` (used throughout `MT5Broker`).

- [ ] **Step 4: Run, expect pass.**

- [ ] **Step 5: Implement `MT5Broker.recoverPendingOrders`.** Add the override (near `getOpenPositions`):

```kotlin
    override fun recoverPendingOrders(orders: List<com.qkt.execution.ManagedOrder>) {
        if (orders.isEmpty()) return
        val pending =
            runCatching { client.getPendingOrders(magic = profile.magic) }
                .getOrElse {
                    log.warn("MT5Broker {} recovery: getPendingOrders failed: {}", profile.name, it.message)
                    return
                }
        val positions =
            runCatching { client.getPositions(magic = profile.magic) }
                .getOrElse {
                    log.warn("MT5Broker {} recovery: getPositions failed: {}", profile.name, it.message)
                    return
                }
        val actions = classifyOcoRecovery(orders, pending.map { it.ticket }.toSet(), positions)
        // Pass 1: re-seed all still-pending legs before any fill is emitted, so a
        // cancel triggered by pass 2 can resolve its sibling's ticket.
        for (a in actions) {
            if (a is OcoRecoveryAction.Reseed) {
                pendingTickets[a.order.id] = a.ticket
                pendingByTicket[a.ticket] = PendingMeta(a.order.id, a.order.request.strategyId)
                log.info("MT5Broker {} recovery: re-seeded pending leg {} ticket={}", profile.name, a.order.id, a.ticket)
            }
        }
        // Pass 2: republish the fill for any leg that filled while the daemon was down.
        for (a in actions) {
            if (a is OcoRecoveryAction.EmitFill) {
                pendingByTicket[a.position.ticket] = PendingMeta(a.order.id, a.order.request.strategyId)
                log.info(
                    "MT5Broker {} recovery: leg {} filled during downtime ticket={}",
                    profile.name, a.order.id, a.position.ticket,
                )
                onPendingPositionOpened(a.position)
            }
        }
    }
```

`onPendingPositionOpened` consumes the `pendingByTicket` entry and publishes `BrokerEvent.OrderFilled` with `meta.orderId` — the real `clientOrderId`. Confirm `getPendingOrders` returns objects with `.ticket`.

- [ ] **Step 6: Verify compile + run the mt5 test package.** Commit:

```bash
git add src/main/kotlin/com/qkt/broker/mt5/OcoRecovery.kt src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt src/test/kotlin/com/qkt/broker/mt5/OcoRecoveryTest.kt
git commit -m "feat(broker): recover MT5 pending OCO legs against venue truth"
```

---

## Task 6: Wire `restore` into `LiveSession` startup

**Files:** modify `app/LiveSession.kt` (near the `reconcileOrPreload` call, ~line 339).

- [ ] **Step 1: Call `restore` after `reconcileOrPreload`:**

```kotlin
        reconcileOrPreload(strategyPositions, broker)
        pipeline.orderManager.restore(strategies.map { it.first })
```

Confirm `pipeline.orderManager` and `strategies` (the `List<Pair<String, Strategy>>`) names against the file.

- [ ] **Step 2: Verify compile** — `./gradlew compileKotlin --console=plain`.

- [ ] **Step 3: Commit:**

```bash
git add src/main/kotlin/com/qkt/app/LiveSession.kt
git commit -m "feat(app): restore the order manager on session startup"
```

---

## Task 7: Phase-doc note

**Files:** modify the most relevant pending-OCO phase changelog under `docs/phases/`.

- [ ] **Step 1:** Add a short "Follow-up" entry recording that OCO legs + linkage are now restored on daemon restart (issue #46), with the spec's known limitations (one-tick persist window; OCO-only).

- [ ] **Step 2: Commit** — `git commit -m "docs: note OCO restart recovery in the phase changelog"`.

---

## Final: full build + PR

- [ ] `./gradlew build --console=plain` green.
- [ ] PR `fix-oco-restart-linkage` → `dev`, description per the qkt skill template, links spec + plan + issue #46.

---

## Self-review notes

- **Spec coverage:** §1 persist → Tasks 1–3; §2 restore → Task 4; §3 broker recovery + emit fills → Task 5; §4 LiveSession wiring → Task 6; limitations → Task 7.
- **Type consistency:** `saveOcoLegs`/`loadOcoLegs(List<PersistedOcoLeg>)` identical across Tasks 1–3. `PersistedOcoLeg` fields = `OcoLegDto` fields. `recoverPendingOrders(List<ManagedOrder>)` defined Task 4, implemented Task 5, called via `restore` Task 4. `classifyOcoRecovery` defined + used Task 5.
- **Execution caveat:** test code uses `OrderRequest.StandaloneOCO`/`Stop` and `MT5Position`/`MT5PendingOrder` — read the real definitions and match field names before each RED run.
