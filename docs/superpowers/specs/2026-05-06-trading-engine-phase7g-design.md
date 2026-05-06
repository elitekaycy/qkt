# Phase 7g — Periodic Reconciliation and Balance Polling

**Status:** Design draft.
**Predecessor:** Phase 7f (broker connection resilience).
**Successor:** Phase 7h (rate limiting + derivatives broker + properly-scoped position reconciliation).

---

## 1. Mission

Close the race-condition gap left by Phase 7f and surface broker wallet balances to the engine. After this phase, the engine periodically polls Bybit's REST snapshot — not only on reconnect — so an order cancelled or filled silently between WebSocket frames is detected within the configured polling window. Wallet balances become observable so future risk and sizing components can gate orders on actual buying power.

This phase deliberately stays small. It does **not** ship full position reconciliation: spot's pair-net `PositionTracker` doesn't map cleanly onto Bybit's per-coin wallet balances. Proper position reconciliation arrives in Phase 7h alongside `BybitLinearBroker`, where derivatives positions have well-defined directional semantics and `/v5/position/list` returns a 1:1 match for `PositionTracker` rows.

---

## 2. Goals

- Generic `PeriodicReconciler` in `com.qkt.common.net` — broker-agnostic timer that invokes a reconcile callable on a fixed cadence. Reusable by future `AlpacaClient`, `IBKRClient`, etc.
- `BybitSpotBroker` constructs a `PeriodicReconciler` that calls `BybitStateRecovery.reconcile()` every 30 seconds (configurable via `pollIntervalMs`).
- **Initial reconcile on broker startup**: `reconcile()` runs once after `client.connect()` completes successfully. The engine starts in sync with broker truth before strategies submit anything.
- `BybitStateRecovery.reconcile()` extended with a third path: `reconcileBalances()` — POST `/v5/account/wallet-balance`, parse coin balances, expose via `BybitClient.balances`. Emits `BrokerEvent.BalancesUpdated`.
- New event type `BrokerEvent.BalancesUpdated(balances: Map<String, BigDecimal>, timestamp, sequenceId)`. Not order-bound — see §6 for the structural change to `BrokerEvent`.
- `BybitClient.balances: Map<String, BigDecimal>` — read-only snapshot, refreshed every poll. Strategies and risk components read this for sizing.
- `PeriodicReconciler.stop()` called from `BybitSpotBroker.close()` — clean shutdown.
- Backward compatible: existing strategies and tests compile unchanged. Broker construction gains an optional `pollIntervalMs` parameter with a sensible default.

## Non-goals

- **No spot position reconciliation that overwrites `PositionTracker`.** Pair-net (engine) and per-coin (wallet) are different shapes — overwriting would lie. Defer to 7h derivatives where the mapping is exact.
- **No `BrokerStateRecovery` interface yet.** One implementation; wait for the second.
- **No PnL component.** PnL is its own phase (8 or 9). When it ships it will be tagged `(strategyId, symbol) → realizedPnL` so per-strategy / per-broker / cross-cutting views all aggregate at query time.
- **No rate-limit (429) handling.** Phase 7h.
- **No account-state polling beyond balances** — equity, margin level, buying power are derivatives concerns. Spot has no margin (in the cross/isolated sense Phase 7h cares about). Wallet balance is enough for spot risk gating.
- **No persistence across JVM restarts.** Same as 7f.
- **No multi-account.** One `BybitClient` per Bybit account.
- **No backfill of historical events** — we don't try to recover events older than the most recent `lastFillTime` window.

---

## 3. Background — current state (Phase 7f, post-merge)

```kotlin
class BybitStateRecovery(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val getKnownOrders: () -> Map<String, ManagedOrderView>,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
) {
    fun reconcile() {
        reconcileOpenOrders()   // POST /v5/order/realtime
        reconcileExecutions()   // POST /v5/execution/list with startTime = lastFillTime - 60s
    }
}

class BybitSpotBroker(...) : Broker {
    init {
        transport.onReconnect { recovery.reconcile() }   // ONLY runs after a WS reconnect
    }
}
```

Limitations forcing this phase:

- **Race-3 from 7f §10.** An order cancelled between two WS reconnects is missed forever. The engine keeps it in `knownOrders`; reconcile only runs when WS bounces. If the WS stays up but the venue silently changes the order state (e.g., Bybit-side cancellation due to a corporate action, server-side timeout, manual cancel by another client on the same account), the engine never finds out.
- **No initial reconcile.** Broker startup with stale state from a previous run is unsupported. (Phase 7f expects a fresh JVM, but in practice users restart while orders are open.)
- **No balance visibility.** Strategies sizing into trades have no way to ask "how much USDT do I have available?" — they have to guess or hardcode.

---

## 4. Architecture overview

Two-layer addition:

```
┌──────────────────────────────────────────────────────────────────────┐
│  Layer A — generic, broker-agnostic                                  │
│                                                                       │
│  PeriodicReconciler (com.qkt.common.net)                             │
│    • takes a reconcile callable: () -> Unit                          │
│    • takes pollIntervalMs                                            │
│    • start() / stop()                                                │
│    • catches and logs exceptions, never lets the loop die            │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
            ▲                                          ▲
            │ used by                                  │ used by
┌───────────┴──────────────────────────────────────────┴───────────────┐
│  Layer B — broker-specific (one per broker)                          │
│                                                                       │
│  BybitSpotBroker                                                      │
│    private val reconciler = PeriodicReconciler(                      │
│        intervalMs = pollIntervalMs,                                  │
│        action = { recovery.reconcile() },                            │
│    )                                                                 │
│    init {                                                             │
│        transport.onReconnect { recovery.reconcile() }   // 7f        │
│        recovery.reconcile()                              // initial  │
│        reconciler.start()                                // periodic │
│    }                                                                 │
│    override fun close() { reconciler.stop(); ... }                   │
│                                                                       │
│  BybitStateRecovery (extended)                                        │
│    fun reconcile() {                                                  │
│        reconcileOpenOrders()   // 7f                                  │
│        reconcileExecutions()   // 7f                                  │
│        reconcileBalances()     // 7g — new                            │
│    }                                                                  │
│                                                                       │
│  BybitClient (extended)                                               │
│    var balances: Map<String, BigDecimal>   // read-only snapshot     │
│        internal set                                                  │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

The shape mirrors 7f: generic timer in `com.qkt.common.net`, broker-specific reconcile logic in `com.qkt.broker.bybit`. Future brokers reuse the timer; each owns its REST shape.

---

## 5. `PeriodicReconciler` (com.qkt.common.net)

### Interface

```kotlin
class PeriodicReconciler(
    private val intervalMs: Long,
    private val action: () -> Unit,
    private val executor: ScheduledExecutorService = defaultExecutor(),
    private val onError: (Throwable) -> Unit = { /* logged via SLF4J */ },
) {
    private val started = AtomicBoolean(false)
    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        future = executor.scheduleAtFixedRate(::tick, intervalMs, intervalMs, MILLISECONDS)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        future?.cancel(false)
        future = null
    }

    val isRunning: Boolean get() = started.get()

    private fun tick() {
        try {
            action()
        } catch (e: Throwable) {
            onError(e)
            // do NOT re-throw — letting it propagate would kill the loop
        }
    }

    companion object {
        private fun defaultExecutor(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "qkt-periodic-reconciler").apply { isDaemon = true }
            }
    }
}
```

### Design choices

- **`scheduleAtFixedRate` vs `scheduleWithFixedDelay`.** Use `scheduleAtFixedRate` for predictable cadence. If a tick takes longer than the interval, the next tick runs immediately (no overlap, since the executor is single-threaded). Bybit reconcile typically takes 100–500 ms for three REST calls.
- **Exception handling.** `scheduleAtFixedRate` cancels the recurring task if any tick throws — that would silently kill the reconciler. The `try/catch` in `tick()` is load-bearing, not defensive cruft.
- **Daemon thread.** Reconciler should not block JVM shutdown. Single-thread executor with daemon `Thread` matches `ReconnectSupervisor`'s pattern.
- **No initial run.** First execution happens after `intervalMs` (the `initialDelay` arg matches `intervalMs`). Initial sync is the broker's job, called explicitly before `reconciler.start()`. Keeps the timer dumb.
- **Idempotent start/stop.** `compareAndSet` guards against double-start and double-stop. Useful for tests and for brokers that swap reconcilers on credentials change.

### Why a separate class from `ReconnectSupervisor`

They look superficially similar — both wrap `ScheduledExecutorService`, both deal with broker recovery — but the semantics differ:

| `ReconnectSupervisor` | `PeriodicReconciler` |
|---|---|
| Backoff (exponential, capped) | Fixed cadence |
| One-shot until success | Recurring forever |
| Triggered by external event (disconnect) | Self-driven (timer) |
| Aborts on success | Stops only on `close()` |

Merging them into "GenericRetrier" would smear two clean concepts into one muddy one. Keep separate.

---

## 6. New event type: `BrokerEvent.BalancesUpdated`

### Structural problem with `BrokerEvent`

```kotlin
sealed interface BrokerEvent : Event {
    val clientOrderId: String        // every variant requires this
    val brokerOrderId: String?
    // ... order-bound variants ...
}
```

A balances-updated event has no order. Three options:

**Option A: Add a sentinel `clientOrderId = ""`.** Gross. Lies about the type.

**Option B: New sibling `BalanceEvent` under `Event`.** Clean separation but splits broker-side events across two hierarchies. Subscribers that handle "anything from the broker" now have to listen on both.

**Option C: Relax `BrokerEvent` — lift `clientOrderId`/`brokerOrderId` off the parent.** Move them to a new sub-interface `BrokerEvent.OrderEvent`. Order-bound variants implement `OrderEvent`; non-order variants (like `BalancesUpdated`) implement `BrokerEvent` directly.

**Pick C.** It's the only one that's both honest and keeps a single subscription point for "broker-originated events." The refactor is small — five existing variants gain an `OrderEvent` marker; nothing else changes.

### Proposed shape

```kotlin
sealed interface BrokerEvent : Event {

    // Marker for order-bound variants. All existing variants implement this.
    sealed interface OrderEvent : BrokerEvent {
        val clientOrderId: String
        val brokerOrderId: String?
    }

    data class OrderAccepted(...) : OrderEvent
    data class OrderRejected(...) : OrderEvent
    data class OrderFilled(...) : OrderEvent
    data class OrderPartiallyFilled(...) : OrderEvent
    data class OrderCancelled(...) : OrderEvent

    // New, non-order-bound:
    data class BalancesUpdated(
        val balances: Map<String, BigDecimal>,   // coin -> total balance
        val source: String,                      // "BYBIT_SPOT" — used to attribute when multi-broker
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent
}
```

### Subscriber compatibility

Existing code subscribing to `BrokerEvent.OrderFilled` continues to work — the subtype hierarchy lets `OrderFilled` flow up to `OrderEvent` and `BrokerEvent`. Code that needs "any order event" gains a clean type to subscribe on (`BrokerEvent.OrderEvent`). Code that wants "any broker-originated event" subscribes on `BrokerEvent` and now correctly receives `BalancesUpdated` too.

`PositionTracker.applyFill(event: BrokerEvent.OrderFilled)` is unaffected.

---

## 7. `BybitStateRecovery.reconcileBalances()`

### REST call

```
POST /v5/account/wallet-balance
Body: {"accountType":"UNIFIED"}
```

Bybit testnet uses Unified accounts by default. Live accounts are configurable via env (`BYBIT_ACCOUNT_TYPE`, default `UNIFIED`). The current `BybitClient.postSigned` wrapper handles signing; no transport change needed.

### Response shape (abridged)

```json
{
  "retCode": 0,
  "result": {
    "list": [{
      "accountType": "UNIFIED",
      "coin": [
        {"coin": "BTC",  "walletBalance": "0.5",   "availableToWithdraw": "0.5"},
        {"coin": "USDT", "walletBalance": "30000", "availableToWithdraw": "30000"}
      ]
    }]
  }
}
```

We extract `walletBalance` per coin into `Map<String, BigDecimal>`. `availableToWithdraw` is informational; Phase 7g uses `walletBalance` because that's the figure that matches "what's in the account."

### Implementation

```kotlin
private fun reconcileBalances() {
    val response = transport.postSigned("/v5/account/wallet-balance", """{"accountType":"UNIFIED"}""")
    val parsed = BybitBalanceTranslator.parseWalletBalance(response)   // new pure-function file, parallel to BybitOrderTranslator
    transport.updateBalances(parsed)            // cached on the client; see §8
    bus.publish(BrokerEvent.BalancesUpdated(
        balances = parsed,
        source = "BYBIT_SPOT",
        timestamp = clock.now(),
    ))
}
```

The transport gets a new method `updateBalances(Map<String, BigDecimal>)` — symmetric to how 7f added `onReconnect`. Recovery is the writer; client is the cache.

### Why publish via the bus?

Two reasons:

1. **Strategies and risk components subscribe**, just like they do for fills. The bus is already the integration point; no need for a side channel.
2. **Multi-broker future.** When Alpaca lands and emits its own `BalancesUpdated`, a single risk component can `subscribe<BrokerEvent.BalancesUpdated>` and group by `source`. No registry, no glue code.

---

## 8. `BybitClient.balances` and `BybitTransport.updateBalances`

### Interface change

```kotlin
interface BybitTransport {
    val isConnected: Boolean             // 7f
    fun onReconnect(handler: () -> Unit) // 7f
    fun postSigned(path: String, body: String): String
    fun subscribe(topic: String, listener: (JsonObject) -> Unit)
    fun onDisconnect(handler: (String) -> Unit)

    // 7g additions:
    val balances: Map<String, BigDecimal>
    fun updateBalances(snapshot: Map<String, BigDecimal>)
}
```

Real `BybitClient` stores in `AtomicReference<Map<String, BigDecimal>>`. Read returns the immutable snapshot. Write replaces atomically.

### Why on the client and not on the broker

The client is per-account; the broker is per-product. One Bybit account can have multiple brokers (`BybitSpotBroker`, future `BybitLinearBroker`) sharing one client. The wallet balance is per-account, so it lives on the client. Per-product brokers read from the shared cache.

This also matches the layering: the client owns I/O; brokers translate between `OrderRequest` and venue calls. Cached state from REST belongs to the I/O layer.

### `FakeBybitClient` update

Mirrors the real one — exposes `balances` and `updateBalances`. Tests can preset balances or assert on `updateBalances` calls.

---

## 9. `BybitSpotBroker` lifecycle changes

### Constructor

```kotlin
class BybitSpotBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val recoveryWindowMs: Long = 5 * 60_000L,    // 7f
    private val pollIntervalMs: Long = 30_000L,           // 7g — new
) : Broker { ... }
```

### Init block (consolidated from 7f + 7g)

```kotlin
init {
    val recovery = BybitStateRecovery(
        transport = transport,
        bus = bus,
        clock = clock,
        getKnownOrders = { knownOrders.toMap() },
        lastFillTimeProvider = { lastFillTime.get() },
        seenExecIds = seenExecIds,
    )

    transport.onReconnect { recovery.reconcile() }   // 7f — on WS reconnect
    recovery.reconcile()                              // 7g — initial sync at startup

    reconciler = PeriodicReconciler(
        intervalMs = pollIntervalMs,
        action = { recovery.reconcile() },
    )
    reconciler.start()                                // 7g — periodic loop

    // 7e: WS subscriptions
    transport.subscribe("order") { onOrderFrame(it) }
    transport.subscribe("execution") { onExecutionFrame(it) }
}
```

### close()

```kotlin
override fun close() {
    reconciler.stop()
    // existing 7e cleanup follows
}
```

### Initial reconcile failure handling

If the initial reconcile throws (network down, bad credentials), the broker constructor propagates it. Strategies don't get a half-initialized broker. This matches 7f's fail-fast on initial connect: if the broker can't see the venue at startup, fail loud, not silent.

The periodic loop is more lenient — `PeriodicReconciler.tick()` swallows and logs, keeping the loop alive. A transient REST outage shouldn't kill the broker.

---

## 10. Race conditions and edge cases

### Race-1: tick fires while WS reconnect-driven reconcile is mid-flight

Both call `BybitStateRecovery.reconcile()` on different threads (executor vs OkHttp WS callback thread). Reconcile is **not** thread-safe today — it reads `getKnownOrders()` and `seenExecIds` and writes to `seenExecIds`.

**Fix:** wrap `reconcile()` in a synchronized block on a dedicated lock object. Cheap, correct, no contention in practice (reconcile finishes in <1s; ticks are 30s apart). Document that `reconcile()` is serialized.

### Race-2: balance update arrives between submit and fill

Strategy reads `balances["USDT"] = 30000`, decides to buy 0.3 BTC at $80k = $24k notional, submits. Order accepted. WS fill arrives. Then the next periodic tick refreshes balances to `25000` (post-fill).

This is fine. The balance the strategy used was current at submission time. Post-fill the balance reflects truth. No correction needed.

### Race-3: cancellation between tick T and tick T+1

Order cancelled by venue at T+5s; engine detects at T+30s (next tick). 25s of lag is acceptable for the use case (much better than 7f's "until next reconnect, possibly hours"). If sub-second lag becomes important, the user can drop `pollIntervalMs` — but Bybit's rate limits cap practical cadence at ~3-5s.

### Race-4: poll runs during initial reconcile

`PeriodicReconciler.start()` is called *after* `recovery.reconcile()` in `init`. The first periodic tick fires `intervalMs` later, never overlapping with init. Safe.

### Race-5: balances arrive before any orders submitted

`BalancesUpdated` published at startup — strategies subscribed before the first poll see the initial state. Strategies subscribed after the initial poll will miss the first event but will see the second poll within 30s. Document that subscribers wanting immediate state should read `client.balances` directly.

### Edge: user has no Unified account

Live accounts may use Spot (legacy) or Contract (legacy) account types. `accountType=UNIFIED` returns empty for those. Make `accountType` configurable via env (`BYBIT_ACCOUNT_TYPE`, default `UNIFIED`). Document the operator's responsibility to set it.

### Edge: poll fails repeatedly

Logged per failure via SLF4J `WARN`. After 10 consecutive failures, log `ERROR` and emit a `BrokerEvent.BalancesUpdated` with the last-known balances and `source = "BYBIT_SPOT (stale)"`? **No.** That's a lie. Stale data with a "stale" tag is a footgun. Just log and let consumers detect drift via `clock.now() - lastBalanceTime`.

Phase 7h might add a `BrokerEvent.HealthDegraded` for explicit signaling. Out of scope here.

### Edge: wallet contains 100+ coins

Bybit Unified can hold many altcoins. We store the entire map. Memory is not a concern (a few KB). Strategies filter by coin of interest.

---

## 11. Multi-broker scaling notes

The architecture extends naturally to N brokers. Locked-in invariants:

1. **One `PeriodicReconciler` per broker.** Each broker constructs its own. The class is broker-agnostic.
2. **One `XxxStateRecovery` per broker.** Bybit's, Alpaca's, IBKR's REST shapes differ; each owns its parsing. Common interface extracted only after 2nd broker exists.
3. **`BalancesUpdated.source` is the attribution key.** Risk and PnL components group/filter by it.
4. **No central balance registry.** Each `XxxClient` owns its own `balances` map. Strategies wanting cross-broker views aggregate in user code or in a dedicated `MultiBrokerView` component (future).

PnL (future phase): tag at write time with `(strategyId, symbol)`. Aggregation happens at query time. Symbol prefix (`BYBIT_SPOT:`, `ALPACA_STOCKS:`, ...) gives broker attribution for free. This works because every fill carries a symbol, every symbol carries a prefix, and prefixes are unique per broker product.

Concrete example: `OrderManager.dispatch` already routes by symbol prefix to the matching broker via `CompositeBroker`. The same prefix flows through `OrderFilled` events to `PositionTracker`. PnL computed per fill inherits both `strategyId` (from order context) and `prefix` (from symbol). Slicing later by either field is a `groupBy` away.

---

## 12. Configuration

### Constructor parameters (BybitSpotBroker)

| Parameter | Default | Purpose |
|---|---|---|
| `recoveryWindowMs` | `5 * 60_000` (5 min) | 7f — execution-list lookback on reconcile |
| `pollIntervalMs` | `30_000` (30 s) | 7g — periodic reconcile cadence |

### Environment variables

| Var | Default | Purpose |
|---|---|---|
| `BYBIT_API_KEY` / `BYBIT_API_SECRET` | (required) | 7e |
| `BYBIT_TESTNET` | `true` | 7e |
| `BYBIT_ACCOUNT_TYPE` | `UNIFIED` | 7g — wallet-balance request |

### Tuning advice

- **Default 30s** balances staleness vs. REST load. Three calls per tick × 2 ticks/min = 6 calls/min. Bybit allows ~120 reads/min; we use 5%.
- **Tighten to 5s** if the strategy is highly sensitive to cancellation latency. Verify rate limit headroom first.
- **Loosen to 5 min** for low-frequency strategies (daily rebalancing). Reduces REST load to negligible.

---

## 13. Testing approach

### Unit tests

| Class | Scope |
|---|---|
| `PeriodicReconcilerTest` | Reuse 7f's `TestScheduler` (in `src/test/kotlin/com/qkt/common/net/`); assert tick count, `start`/`stop` idempotence, exception isolation (one throwing tick doesn't stop the loop) |
| `BybitStateRecoveryTest` | Extended with balance-reconcile cases: empty balances, populated balances, malformed response (logs error, doesn't crash), `BalancesUpdated` published with correct `source` |
| `BybitSpotBrokerTest` | Initial reconcile fires once at construction; reconciler starts after init; `close()` stops reconciler |
| `BybitSpotBrokerReconcilerIntegrationTest` | Drive `TestScheduler` through 3 ticks, verify 3 reconcile calls; one failure mid-loop, verify loop continues |

### Fakes

`FakeBybitClient`:
- New field: `balances: Map<String, BigDecimal>` (atomic ref).
- New method: `updateBalances(...)` — settable from tests OR from recovery.
- Programmable response for `/v5/account/wallet-balance`.
- Existing `posts` list captures the balance request body for assertion.

### `e2e-live`

Extend `BybitSpotLiveSmokeTest` (or add `BybitBalancesLiveSmokeTest`):
- Construct broker against testnet.
- Wait one poll cycle (or trigger manual `reconcile()`).
- Assert `client.balances["USDT"] != null`.

Skipped without credentials, gated by `@Tag("e2e-live")`.

### Coverage targets

- All new code paths: 100% line coverage in unit tests.
- Edge cases from §10 each have at least one test (race-1 lock, race-4 ordering, race-5 startup subscription).
- Integration test verifies the full chain: tick → reconcile → REST call → parse → `updateBalances` → bus emission.

---

## 14. Out of scope (deferred)

| Feature | Phase | Rationale |
|---|---|---|
| Spot position reconciliation overwriting `PositionTracker` | (never as designed) | Pair-net vs per-coin shapes don't align. Use derivatives in 7h instead. |
| Derivatives position reconciliation (`/v5/position/list`) | 7h | Requires `BybitLinearBroker`. |
| Account state (equity, margin level, buying power, unrealised PnL) | 7h | Derivatives concerns. |
| Rate-limit (429) detection and queueing | 7h | Independent from reconciliation. |
| `BrokerStateRecovery` interface extraction | 8 (after 2nd broker) | Wait for real second impl. |
| `OrderManager.modify()` exposed to strategies | 7h | Independent. |
| PnL tracking | 8 or 9 | Tagged-event design; fits architecture but is its own scope. |
| JVM-restart persistence | (its own phase) | Requires a state-store; large effort. |
| `BrokerEvent.HealthDegraded` (broker-side health signaling) | 7h | Together with rate-limit handling. |

---

## 15. Migration

### From Phase 7f → 7g

| 7f | 7g | Notes |
|---|---|---|
| `BybitSpotBroker(client, bus, clock, recoveryWindowMs)` | `BybitSpotBroker(client, bus, clock, recoveryWindowMs, pollIntervalMs)` | New parameter has a default; existing callers compile. |
| `BybitTransport` had 5 methods | gains `balances` getter and `updateBalances` | `FakeBybitClient` updated; users implementing transport directly add the new members. |
| `BrokerEvent` had 5 order variants | gains `OrderEvent` marker interface + `BalancesUpdated` variant | All existing variants implement `OrderEvent`. Subscribers on `BrokerEvent.OrderFilled` etc. unaffected. Subscribers wanting "any order event" can now use `BrokerEvent.OrderEvent`. |

### Subscriber migration

```kotlin
// 7f — only option
bus.subscribe<BrokerEvent.OrderFilled> { ... }

// 7g — same code works
bus.subscribe<BrokerEvent.OrderFilled> { ... }

// 7g — new option for "any order event"
bus.subscribe<BrokerEvent.OrderEvent> { ... }

// 7g — new option for balance updates
bus.subscribe<BrokerEvent.BalancesUpdated> { ... }
```

No breaking changes. The `OrderEvent` marker is purely additive.

---

## 16. Summary

Phase 7g closes the race-condition gap from 7f §10 with a periodic REST poll (race-3), surfaces wallet balances as a first-class observable, and lays the multi-broker pattern for future `XxxClient.balances`. It deliberately defers spot position reconciliation (squishy mapping) and derivatives reconciliation (no broker yet) to 7h.

Surface area is small — one new generic class (`PeriodicReconciler`), one new method (`reconcileBalances`), one new event (`BalancesUpdated`), one structural refinement (`BrokerEvent.OrderEvent` marker). Risk is low. Tests parallel the 7f patterns. The phase is correct and ships in a single PR.

After 7g, the broker layer has connection resilience (7f) + ongoing state sync (7g). The remaining gap before "live ready" is rate-limit handling and derivatives — both 7h.
