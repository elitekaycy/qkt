# Phase 7f — Broker Connection Resilience

**Status:** Design draft.
**Predecessor:** Phase 7e (Bybit Spot, CompositeBroker).
**Successor:** Phase 7g (position reconciliation, account/equity polling).

---

## 1. Mission

Make the broker layer survive transient network failures without losing data. After this phase, a brief WebSocket disconnect or HTTP failure does not silently drop fill events, orphan submitted orders, or leave the engine's view diverged from the venue's. The fix is layered: a reusable `ReconnectSupervisor` for any WS-based client, broker-internal transport retry with idempotent semantics, and per-broker state recovery that reconciles the engine's view against the venue's REST snapshot whenever the connection comes back.

This phase focuses entirely on **connection resilience** — the prerequisite for letting real money flow. Position reconciliation, account/equity polling, and broader observability come in Phase 7g. JVM-restart persistence is deferred entirely (its own phase).

---

## 2. Goals

- Generic `ReconnectSupervisor` in `com.qkt.common.net` — broker-agnostic backoff scheduler. Reusable by future `AlpacaClient`, `IBKRClient`, etc.
- `BybitClient` uses `ReconnectSupervisor` for its private WS lifecycle. Exposes `isConnected` and `onReconnect(handler)`.
- `BybitClient.postSigned` retries on connection-level failures (HTTP 5xx, IOException) up to 3 times with exponential backoff. Bybit `retCode != 0` errors are NOT retried — they're real venue rejections.
- `BybitStateRecovery` (Bybit-specific): on reconnect, queries `/v5/order/realtime` and `/v5/execution/list` and emits synthetic `BrokerEvent`s for state changes the engine missed during the gap. Deduplicates against live WS events via `execId` set.
- `BybitSpotBroker`:
  - Wires `client.onReconnect` to invoke `BybitStateRecovery.reconcile()`.
  - Prunes `symbolByClientOrderId` on terminal `BrokerEvent`s (memory-leak fix).
- Strategies that want to gate on connection state can read `client.isConnected`. Submits during disconnect are NOT blocked at the broker layer (REST is independent of WS); state recovery catches outcomes on reconnect.
- Backward compatible: existing `BybitClient` consumers compile and behave the same, gaining resilience automatically.

## Non-goals

- **No `OrderManager`-level retry abstraction.** Retry-on-rejection is a strategy concern; surfacing `OrderRejected` immediately preserves strategy authority.
- **No `BrokerStateRecovery` interface.** Premature; we have one impl. When Alpaca/IBKR land we'll observe the actual common shape and extract.
- **No persistence across JVM restarts.** State recovery only handles WS gaps within a single JVM run. JVM crash → manual restart; state recovery picks up from Bybit (the canonical record).
- **No position reconciliation against `/v5/position/list`.** Phase 7g.
- **No account / equity / buying-power polling.** Phase 7g.
- **No rate-limit enforcement / 429 handling.** Phase 7h.
- **No multi-account.** Single account per `BybitClient`.
- **No `OrderManager.modify()` exposed to strategies.** Phase 7h.

---

## 3. Background — current state (Phase 7e, post-merge)

```kotlin
class BybitClient(...) : BybitTransport {
    fun connect()                                      // opens WS, sends auth, subscribes registered topics
    fun close()                                         // shuts down WS, kills ping scheduler
    override fun postSigned(path, body): String        // signed REST POST, throws BybitApiException on retCode != 0
    override fun subscribe(topic, listener)
    override fun onDisconnect(handler)                  // called on WS onClosed / onFailure; no recovery
}
```

Limitations forcing this phase:

- **WS dies → fill events stop.** OkHttp's `onFailure` / `onClosed` fires once; we log and notify listeners but do nothing. The strategy continues running blind.
- **Submit transport failure → instant rejection.** A single 503 from Bybit kills an order even though the venue might have actually accepted it.
- **`symbolByClientOrderId` grows unbounded.** Long-lived broker accumulates entries; memory leak.
- **No "connection observable" surface.** Strategies can't tell if their broker is alive.

---

## 4. Architecture overview

Three-layer design:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Layer A — generic, broker-agnostic                                  │
│                                                                       │
│  ReconnectSupervisor (com.qkt.common.net)                            │
│    • BackoffPolicy interface; ExponentialBackoff impl                │
│    • schedule(), abort(), state observable                           │
│    • takes a `attemptReconnect: () -> Boolean` lambda                │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
            ▲                                          ▲
            │ reused by                                │ reused by
┌───────────┴──────────────────────────────────────────┴───────────────┐
│  Layer B — broker-specific connection (one per broker)               │
│                                                                       │
│  BybitClient (modified)                                               │
│    • owns ReconnectSupervisor                                        │
│    • WS onFailure/onClosed → supervisor.schedule()                   │
│    • supervisor reconnects → client re-auths, re-subscribes          │
│    • on reconnect success: fires onReconnect() listeners             │
│    • postSigned() wraps OkHttp with 3-attempt retry on 5xx/IOException│
│    • exposes isConnected: Boolean                                     │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
            ▲
            │ used by
┌───────────┴──────────────────────────────────────────────────────────┐
│  Layer C — broker-specific recovery logic (one per broker product)   │
│                                                                       │
│  BybitStateRecovery                                                  │
│    • reconcile(): GET /v5/order/realtime + /v5/execution/list        │
│    • emits synthetic BrokerEvent.OrderAccepted / Filled / Cancelled   │
│      for state changes that happened while disconnected              │
│    • dedup via Set<String> seenExecIds                               │
│                                                                       │
│  BybitSpotBroker (modified)                                          │
│    • registers state recovery via client.onReconnect                 │
│    • prunes symbolByClientOrderId on terminal events                 │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

Layer A is the only piece of new infrastructure that's broker-agnostic. Layer B/C are Bybit-specific in 7f; the patterns are documented for future broker work.

---

## 5. `ReconnectSupervisor` (Layer A)

### 5.1 Public API

```kotlin
package com.qkt.common.net

interface BackoffPolicy {
    /** Returns the delay in ms for the given attempt number (1-indexed). */
    fun nextDelayMs(attempt: Int): Long
}

class ExponentialBackoff(
    private val initialMs: Long = 1_000L,
    private val capMs: Long = 60_000L,
    private val multiplier: Double = 2.0,
) : BackoffPolicy {
    override fun nextDelayMs(attempt: Int): Long {
        val raw = (initialMs.toDouble() * Math.pow(multiplier, (attempt - 1).toDouble())).toLong()
        return raw.coerceAtMost(capMs)
    }
}

class FixedDelayBackoff(private val delayMs: Long) : BackoffPolicy {
    override fun nextDelayMs(attempt: Int): Long = delayMs
}

class ReconnectSupervisor(
    private val backoff: BackoffPolicy = ExponentialBackoff(),
    private val attemptReconnect: () -> Boolean,
    private val onReconnected: () -> Unit = {},
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "reconnect-supervisor").apply { isDaemon = true }
        },
) {
    fun scheduleReconnect()
    fun abort()
    val isReconnecting: Boolean
}
```

### 5.2 Behavior

- `scheduleReconnect()` — schedules `attemptReconnect()` after `backoff.nextDelayMs(attempt)`.
  - If `attemptReconnect()` returns `true`: reset attempt counter, fire `onReconnected`, supervisor goes idle.
  - If `attemptReconnect()` returns `false`: increment attempt, schedule next try. **Infinite retries.**
  - `attempt` resets to 1 on every successful reconnect.
- `abort()` — cancels any pending retry. Used at `client.close()`.
- `isReconnecting` — true between the first failure and the first successful reconnect (or `abort()`).
- Thread model: single daemon executor. All supervisor work runs on that one thread. Caller's lambdas may block briefly; they should not block forever.

### 5.3 Why generic

`ReconnectSupervisor` knows nothing about Bybit, OkHttp, or WebSockets. It schedules a callable, checks the return, retries. Future `AlpacaClient`, `IBKRClient`, `BinanceClient`, etc. each construct their own supervisor with broker-specific `attemptReconnect` and `onReconnected` lambdas.

---

## 6. `BybitClient` modifications (Layer B)

### 6.1 New surface

```kotlin
class BybitClient(...) : BybitTransport {
    // existing public API unchanged

    // NEW: observable state + reconnect callbacks
    val isConnected: Boolean        // true between successful auth and disconnect
    fun onReconnect(handler: () -> Unit)  // called after WS reconnect + auth + re-subscribe complete

    // existing onDisconnect(handler) unchanged
}
```

### 6.2 WS lifecycle (revised)

```
connect() called
  → open WS, send auth, send subscribe ops for registered topics
  → on successful auth (op response): isConnected = true; fire any onReconnect listeners
                                      ── (only if this is a RE-connect, not initial connect)
  → on WS onFailure / onClosed:
      isConnected = false
      fire onDisconnect listeners  (existing behavior)
      supervisor.scheduleReconnect()
        → after backoff: open new WS, re-send auth, re-send subscribe ops
        → on success: isConnected = true, fire onReconnect listeners
        → on failure: supervisor schedules next attempt (infinite)
```

Internally, the supervisor's `attemptReconnect` lambda performs:
1. Open a new `WebSocket` via OkHttp.
2. Wait for `onOpen` (or timeout via supervisor).
3. Send auth message.
4. Wait for auth ack (op response with `op="auth"`, `success=true`) — synchronous via a `CountDownLatch`.
5. Send subscribe ops for all registered topics (from `topicListeners` map).
6. Return `true` on success, `false` on any step failure.

`onReconnect` listeners fire AFTER all subscriptions are sent, so brokers know the WS is fully ready when they kick off state recovery.

### 6.3 Initial connect vs reconnect

`onReconnect` listeners fire only on re-connections (the WS dropped and came back). The first call to `connect()` does NOT fire them — that's a fresh start, no recovery needed because the engine has no prior state.

Internal flag `hasEverConnected: Boolean` distinguishes the two.

### 6.4 `postSigned` transport retry

```kotlin
fun postSigned(path: String, jsonBody: String): String {
    var attempt = 0
    val maxAttempts = 3
    var lastEx: Exception? = null
    while (attempt < maxAttempts) {
        attempt++
        try {
            return doPostSignedOnce(path, jsonBody)
        } catch (e: BybitApiException) {
            // Bybit said no. Real error. Don't retry.
            throw e
        } catch (e: Exception) {
            // IOException, SocketTimeoutException, etc. Connection-level failure. Retry.
            lastEx = e
            if (attempt < maxAttempts) {
                Thread.sleep(transportRetryDelayMs(attempt))
            }
        }
    }
    throw lastEx ?: error("postSigned exhausted retries with no captured exception")
}

private fun transportRetryDelayMs(attempt: Int): Long =
    when (attempt) {
        1 -> 500L
        2 -> 1_000L
        else -> 2_000L
    }
```

Idempotency: Bybit's `orderLinkId` ensures resubmission with the same id is safe. If our first attempt actually went through but we missed the response, the retry returns the existing order's status. No duplicate orders.

For non-create endpoints (cancel, amend), Bybit treats the operation as idempotent on `orderLinkId` — re-cancelling an already-cancelled order returns a "not found" error which we tolerate.

### 6.5 `isConnected` semantics

- `false` when:
  - `connect()` has never succeeded
  - WS just dropped (onFailure/onClosed); supervisor hasn't reconnected yet
  - During backoff
  - After `close()`
- `true` when:
  - WS is open AND auth has been confirmed AND topics subscribed
- The flag is updated atomically (`AtomicBoolean`) and is safe to read from any thread.

---

## 7. `BybitStateRecovery` (Layer C)

### 7.1 Class

```kotlin
package com.qkt.broker.bybit

class BybitStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val getKnownOrders: () -> Map<String, ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
) {
    /** Represents what the broker knows about an order (subset of ManagedOrder for testability). */
    data class ManagedOrderView(
        val clientOrderId: String,
        val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val isWorking: Boolean,    // state in {SUBMITTED, WORKING, PARTIALLY_FILLED}
    )

    /** Called by BybitSpotBroker on every reconnect. */
    fun reconcile() { ... }

    /**
     * Set of execIds we've already emitted as fills. Updated by both live WS events
     * and recovery, ensuring we don't double-emit if a recovery query and live event race.
     */
    private val seenExecIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
}
```

### 7.2 `reconcile()` algorithm

```
1. Fetch open orders from Bybit:
     GET /v5/order/realtime?category=spot&openOnly=0&limit=50
   Returns: list of orders currently working at the venue.

2. Compare against engine view:
   For each engine-known order in {SUBMITTED, WORKING, PARTIALLY_FILLED}:
     • If Bybit's open list contains the orderLinkId → no-op (still working)
     • If Bybit doesn't contain it →
         the order has terminalized. We don't know how (filled? cancelled?).
         → resolve via /v5/order/realtime?orderLinkId=X (single-order lookup)
           → if status=Filled → recovery emits OrderFilled
                                 (but exact price/qty come from execution feed, not order feed)
           → if status=Cancelled → emit OrderCancelled(reason="recovered")

3. Fetch executions since last known fill time:
     GET /v5/execution/list?category=spot&startTime=lastFillTime - 60_000
   Returns: list of recent executions.
   For each execution:
     • If execId in seenExecIds → skip
     • Else → emit OrderFilled, add execId to seenExecIds

4. Recovery is best-effort. Failures (HTTP errors, parse errors) log a warning;
   the engine retains its view, which may be slightly stale until next reconnect.
```

### 7.3 Race conditions handled

**Race 1: live WS event arrives during recovery.**
- Recovery emits OrderFilled for execId X.
- Concurrently, WS execution frame arrives with execId X.
- Both pass through `BybitSpotBroker.onExecutionFrame` (or `BybitStateRecovery.emit`) which checks `seenExecIds`. First one wins; second is deduped.

**Race 2: order terminalized between open-orders query and execution query.**
- Open orders query says order Z is still working.
- Order Z fills.
- Execution query catches the fill via execution list.
- Engine sees: order is working (from step 2 no-op) → fill arrives → engine processes normally.

**Race 3: order cancelled between two queries.**
- Open orders says Z is working.
- Z cancels.
- Execution query has nothing for Z (no fill).
- Engine still thinks Z is working. **Recovery doesn't catch this.** On next reconnect, the same logic catches it because Z will be missing from the open-orders query at that point. Window of error: until next reconnect. Acceptable for 7f.

**Race 4: orderLinkId on a brand-new order that just got submitted.**
- Submit returns ack with `accepted=true, brokerOrderId=X`.
- WS dies before OrderAccepted arrives.
- Engine still has order in SUBMITTED state.
- Recovery: order IS in Bybit's open list → engine syncs to WORKING via the open-order metadata.

### 7.4 `lastFillTime` tracking

`BybitSpotBroker` maintains an `AtomicLong lastFillTime` set on every `OrderFilled` it emits (live or recovery). On startup it's `clock.now() - SOMETHING_REASONABLE` — say 5 minutes — which means the first reconcile pulls executions from 5 minutes ago, then later reconciles pull from the most recent fill. This bounds the execution query window.

---

## 8. `BybitSpotBroker` modifications

### 8.1 Wiring

```kotlin
class BybitSpotBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    // existing fields ...

    private val lastFillTime = AtomicLong(clock.now() - 5 * 60_000L)
    private val seenExecIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val recovery = BybitStateRecovery(
        transport = transport,
        bus = bus,
        clock = clock,
        getKnownOrders = ::buildKnownOrdersView,
        lastFillTimeProvider = lastFillTime::get,
        seenExecIds = seenExecIds,
    )

    init {
        transport.subscribe("order") { frame -> onOrderFrame(frame) }
        transport.subscribe("execution") { frame -> onExecutionFrame(frame) }

        // NEW: state recovery on reconnect
        transport.onReconnect { recovery.reconcile() }

        // NEW: pruning of symbolByClientOrderId on terminal events
        bus.subscribe<BrokerEvent.OrderFilled>      { e -> symbolByClientOrderId.remove(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderCancelled>   { e -> symbolByClientOrderId.remove(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderRejected>    { e -> symbolByClientOrderId.remove(e.clientOrderId) }
    }

    // existing submit/cancel/modify ...

    private fun buildKnownOrdersView(): Map<String, ManagedOrderView> {
        // The broker doesn't track ManagedOrder directly — that's OrderManager's job.
        // For state recovery, we maintain a small per-broker map: clientOrderId → minimal view.
        // Updated on submit (add as SUBMITTED) and on every event that transitions state.
        return knownOrders.toMap()
    }

    // private val knownOrders: MutableMap<String, ManagedOrderView> = ConcurrentHashMap()
    // updated in submit() (adds), onOrderFrame() (status transitions), onExecutionFrame() (fills)
}
```

### 8.2 New internal `knownOrders` map

`BybitSpotBroker` already tracks `symbolByClientOrderId`. We extend to a richer record (`ManagedOrderView`) keyed on clientOrderId. Updates:
- `submit()` → on success, add a new entry (state SUBMITTED).
- `onOrderFrame()` New status → state WORKING. Cancelled/Rejected → terminal, REMOVED.
- `onExecutionFrame()` → if cumulative qty matches order qty, mark FILLED, REMOVED. Else PARTIALLY_FILLED.

`BybitStateRecovery.reconcile()` reads this map at recovery time to know what the broker thinks is working.

### 8.3 Pruning

Already shown in 8.1. Three bus subscriptions remove the entry from `symbolByClientOrderId` on terminal events. (`knownOrders` is already pruned by the per-state logic.)

---

## 9. Component flow on reconnect (worked example)

```
[T0]  WS alive. Order c1 SUBMITTED. Bybit acks via WS → engine state WORKING.
[T1]  WS dies. supervisor.scheduleReconnect() at T1+1s.
      isConnected = false. onDisconnect listeners fire.
[T2]  Bybit fills c1. WS dead, no event. Engine still has c1 WORKING.
[T3]  Reconnect attempt at T1+1s succeeds:
      - new WS opens
      - auth sent + acked
      - subscribe ops sent
      - isConnected = true; onReconnect listeners fire
[T4]  BybitStateRecovery.reconcile():
      a. GET /v5/order/realtime?category=spot → returns active orders. c1 not in list.
      b. Engine view says c1 is WORKING. Mismatch.
      c. GET /v5/order/realtime?orderLinkId=c1 → returns single record with status=Filled
         (don't emit OrderFilled here — the execution list is the source of truth for price/qty)
      d. GET /v5/execution/list?category=spot&startTime=lastFillTime-60s →
         returns executions including the c1 fill at price P, qty Q.
      e. execId not in seenExecIds → emit BrokerEvent.OrderFilled(c1, P, Q).
      f. seenExecIds.add(execId). lastFillTime = clock.now().
[T5]  OrderManager subscribes to OrderFilled → c1 → FILLED. PositionTracker updates.
      knownOrders pruned (c1 removed).
```

If the WS reconnects BEFORE the fill, the live execution frame delivers the fill. Recovery then no-ops (order's not in open list, but seenExecIds already has it).

If the order is still working when reconnect happens (the typical case: WS dropped briefly), open-orders query confirms it's still working. No synthetic events. `lastFillTime` not advanced (no fills missed).

---

## 10. Edge cases

1. **Recovery during recovery.** Possible if the WS drops again during reconcile. Solution: `recovery.reconcile()` is fast (~2 REST calls); we don't try to make it interruptible. If it fails partway, the next reconnect retries it.

2. **Reconnect attempt times out mid-flight.** OkHttp WS timeout is 10s; if we don't get auth ack within 10s, treat as failure, supervisor reschedules.

3. **Auth fails on reconnect.** API key was revoked? `attemptReconnect` returns false, supervisor retries indefinitely. Worth a louder warning log on every Nth attempt. (Phase 7g could surface this as a `BrokerEvent.AuthFailed` for strategy awareness.)

4. **Bybit return codes during recovery.** `/v5/order/realtime` returns retCode=0 + result.list = []  if no open orders. If retCode != 0, log warning and skip step 2; still run step 3 (execution list).

5. **Clock skew between engine and Bybit.** `lastFillTime` is engine's clock; Bybit's executions have their own timestamps. The 60s safety margin covers minor skew. If skew exceeds 60s, we miss fills from the gap — log a warning if recovery's startTime is older than it should be.

6. **Pagination on `/v5/execution/list`.** Bybit defaults to 50 records per page, max 100. For 7f we pull a single page (50). If there were >50 fills during the gap, we miss the oldest. Document; Phase 7g could add pagination or shorten poll intervals.

7. **Live WS event arriving during reconcile.** `seenExecIds` is a `ConcurrentHashMap.newKeySet()`. Both paths (live + recovery) check-and-add atomically. First wins.

---

## 11. Testing strategy

- `ReconnectSupervisorTest` — drives `attemptReconnect` lambda with a counter; verifies backoff schedule, reset on success, infinite retries, abort cancels pending.
- `ExponentialBackoffTest` — pure math: 1s, 2s, 4s, ..., capped at 60s.
- `BybitClientReconnectTest` — uses a `FakeWebSocket` that can simulate `onClosed`, then verifies the client schedules a reconnect, opens a new WS, re-auths, re-subscribes. Requires extracting the WS instantiation behind a factory for testability (small refactor).
- `BybitClientPostSignedRetryTest` — uses a `FakeOkHttpClient` (or counts via reflection) to verify retry on 5xx + idempotency assumption.
- `BybitStateRecoveryTest` — uses `FakeBybitClient` with programmable `/v5/order/realtime` and `/v5/execution/list` responses. Asserts:
  - Synthetic `OrderFilled` emitted for an execution missed during gap
  - No double-emission when execId is also delivered live
  - `OrderCancelled` emitted for engine-WORKING that's missing from Bybit's open list
  - Empty open list + empty execution list → no events
- `BybitSpotBrokerReconnectTest` — wires real `BybitSpotBroker` with `FakeBybitClient`, fires `client.fireOnReconnect()`, asserts state recovery runs and emits the right events.
- `BybitSpotBrokerPruningTest` — submits order, emits terminal event, verifies `symbolByClientOrderId` is pruned.
- The existing `BybitSpotLiveSmokeTest` (`@Tag("e2e-live")`) is updated to optionally restart the WS mid-test (manual run only).

`FakeBybitClient` extensions for 7f:
- `var connected: Boolean` (so tests can simulate disconnects)
- `fun fireOnReconnect()` triggers all registered onReconnect listeners
- responses for `/v5/order/realtime` and `/v5/execution/list` keyed on path

---

## 12. Migration plan

Affected production files:

```
src/main/kotlin/com/qkt/common/net/
  BackoffPolicy.kt              # NEW
  ReconnectSupervisor.kt        # NEW

src/main/kotlin/com/qkt/broker/bybit/
  BybitClient.kt                # add ReconnectSupervisor + isConnected + onReconnect + postSigned retry
  BybitStateRecovery.kt         # NEW
  BybitSpotBroker.kt            # wire onReconnect to recovery; add knownOrders + pruning
```

Affected test files:

```
src/test/kotlin/com/qkt/common/net/
  BackoffPolicyTest.kt          # NEW
  ReconnectSupervisorTest.kt    # NEW

src/test/kotlin/com/qkt/broker/bybit/
  BybitStateRecoveryTest.kt              # NEW
  BybitSpotBrokerReconnectTest.kt        # NEW
  BybitSpotBrokerPruningTest.kt          # NEW
  BybitClientReconnectTest.kt            # NEW (small)
  BybitClientPostSignedRetryTest.kt      # NEW (small)
  FakeBybitClient.kt                      # extend with isConnected, fireOnReconnect
```

Estimated scope: ~700 LOC production new, ~600 LOC tests. About the size of Phase 7e minus the main broker class.

---

## 13. Acceptance criteria

- [ ] `ReconnectSupervisor` ships in `com.qkt.common.net` with `ExponentialBackoff` + `FixedDelayBackoff`. Generic; no Bybit knowledge.
- [ ] `BybitClient` uses `ReconnectSupervisor` for WS lifecycle.
- [ ] `BybitClient.isConnected` exposes live connection state; `BybitClient.onReconnect(handler)` registers reconnect callbacks.
- [ ] `BybitClient.postSigned` retries on connection-level failures up to 3 times with backoff. Bybit `retCode != 0` errors are NOT retried.
- [ ] `BybitStateRecovery.reconcile()` queries open orders + executions, emits synthetic `BrokerEvent`s, dedups via `seenExecIds`.
- [ ] `BybitSpotBroker` runs `recovery.reconcile()` on reconnect.
- [ ] `BybitSpotBroker.symbolByClientOrderId` is pruned on terminal events.
- [ ] All Phase 7e tests still pass.
- [ ] New tests cover: reconnect supervisor backoff/abort, transport retry, state recovery (each race case), pruning.
- [ ] `./gradlew run` still produces 10 FILLED+REJECTED.
- [ ] Phase 7f changelog at `docs/phases/phase-7f-broker-resilience.md`.

---

## 14. Open questions / spec ambiguities

1. **WS factory extraction.** Testing reconnect requires injecting a fake `WebSocket` factory into `BybitClient`. The plan adds a `internal val wsFactory: (Request, WebSocketListener) -> WebSocket = httpClient::newWebSocket` parameter. Tests override; production uses default.

2. **Auth ack vs initial WS frame.** Bybit auth response shape: `{"op":"auth","success":true,"ret_msg":"","conn_id":"..."}`. We block on a `CountDownLatch` with a 10s timeout. If timeout, treat as auth failure and reschedule.

3. **`hasEverConnected` flag location.** Inside `BybitClient`, atomic boolean. First successful connect transitions false → true and does NOT fire `onReconnect`. Subsequent transitions (false → true after a disconnect) DO fire `onReconnect`.

4. **`knownOrders` map shape.** Plan uses a `MutableMap<String, ManagedOrderView>`. Could pull from `OrderManager.getOrder(id)` instead — but that creates a backward dependency from broker to OrderManager. Cleaner to maintain a local view.

5. **Recovery query failure handling.** If `/v5/order/realtime` itself fails (e.g., Bybit API outage), we log and skip step 2. Step 3 (execution list) still runs. If both fail, recovery is a no-op for this reconnect cycle. Next reconnect retries; until then engine view is stale. Document.

6. **`lastFillTime` initial value.** On broker construction, set to `clock.now() - 5 * 60_000` (5 minutes). Means the first recovery query (if it happens before any live fills) pulls 5 minutes of history. Reasonable bound; won't pull years of data.

7. **Bybit's execution query time-range max.** Bybit allows querying executions up to 7 days back, max 50/page. We pull 50 max per recovery — for normal use this is plenty (a strategy submitting 50 fills during a brief WS gap is unusual). Phase 7g adds pagination if needed.

8. **`abort()` during pending reconnect.** Cancels the scheduled task; supervisor goes idle. `client.close()` calls `abort()`.

9. **Initial connect failure.** If `client.connect()` fails on the very first try (e.g., bad credentials), should we retry? The current design says: yes, supervisor schedules retries indefinitely. Alternative: throw on initial connect failure, only retry on disconnects from a successful state. The design prefers indefinite retries — bad credentials are usually noticed when no events flow, and the retry log gives a clear signal.

10. **No interaction with `OrderManager`.** OrderManager is unchanged. State recovery emits events into the bus; OrderManager's existing subscriptions handle them as if they were live broker events. The contract is the same.

---

## References

- Phase 7e spec: [`2026-05-06-trading-engine-phase7e-design.md`](./2026-05-06-trading-engine-phase7e-design.md)
- Phase 7e changelog: [`../phases/phase-7e-bybit-and-composite.md`](../phases/phase-7e-bybit-and-composite.md)
- Bybit V5 REST docs: https://bybit-exchange.github.io/docs/v5/intro
- qkt skill: [`.claude/skills/qkt/SKILL.md`](../../.claude/skills/qkt/SKILL.md)
