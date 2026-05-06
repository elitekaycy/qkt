# Phase 7g — Periodic Reconciliation and Balance Polling

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase7g-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7g-design.md)

## Summary

Phase 7g closes the race-condition gap left by 7f and surfaces wallet balances. `BybitSpotBroker` now runs `BybitStateRecovery.reconcile()` on construction and every 30 seconds (configurable), so an order cancelled silently between WS frames is detected within the polling window rather than only on a reconnect. Wallet balances are fetched as a third reconcile path and published as `BrokerEvent.BalancesUpdated` for risk and sizing components. A small structural change to `BrokerEvent` lifts `clientOrderId`/`brokerOrderId` off the parent into a new `OrderEvent` marker so non-order events (`BalancesUpdated`) fit cleanly without sentinel values.

This phase deliberately does NOT ship spot position reconciliation that overwrites `PositionTracker`. Spot's pair-net engine view and Bybit's per-coin wallet view don't reconcile cleanly; faking it would lie. Position reconciliation lands properly in 7h alongside `BybitLinearBroker` where derivatives semantics map 1:1.

## What's new

- `com.qkt.common.net.PeriodicReconciler` — generic timer wrapping `ScheduledExecutorService`. Broker-agnostic; reusable by future Alpaca/IBKR clients.
- `com.qkt.broker.bybit.BybitBalanceTranslator` — pure parser for `/v5/account/wallet-balance` response → `Map<String, BigDecimal>`.
- `BybitStateRecovery.reconcileBalances()` — third reconcile path. POST `/v5/account/wallet-balance`, parse, write to client cache, publish event.
- `BybitStateRecovery.reconcile()` is now `synchronized` — concurrent calls from WS reconnect thread + periodic executor thread are serialized.
- `BybitTransport.balances: Map<String, BigDecimal>` — read-only snapshot.
- `BybitTransport.updateBalances(snapshot)` — written by recovery, read by strategies.
- `BrokerEvent.OrderEvent` — new sealed sub-interface marker. All existing order-bound variants (`OrderAccepted`, `OrderRejected`, `OrderFilled`, `OrderPartiallyFilled`, `OrderCancelled`) implement it. Existing subscribers unaffected.
- `BrokerEvent.BalancesUpdated(balances, source, timestamp, sequenceId)` — new event variant. `source` is the broker prefix (e.g., `"BYBIT_SPOT"`) for multi-broker attribution.
- `BybitSpotBroker(..., pollIntervalMs: Long = 30_000L, pollExecutor: ScheduledExecutorService? = null)` — two new constructor parameters. Defaults preserve existing behavior + add 30 s periodic reconcile.
- `BybitSpotBroker.close()` — stops the reconciler. Called from session shutdown.
- Initial reconcile fires synchronously during `BybitSpotBroker.<init>` — broker startup syncs with venue truth before strategies submit anything.

## Migration from previous phase

| 7f | 7g | Notes |
|---|---|---|
| `BybitSpotBroker(client, bus, clock, recoveryWindowMs)` | `BybitSpotBroker(client, bus, clock, recoveryWindowMs, pollIntervalMs, pollExecutor)` | Both new params have defaults; existing callers compile unchanged. |
| `BybitTransport` had 5 members | gains `balances` (val) and `updateBalances` (fun) | `FakeBybitClient` already updated. Custom transport implementors add the two new members. |
| `BrokerEvent` had 5 flat order variants | adds `OrderEvent` marker (parent of the 5) + `BalancesUpdated` (sibling of `OrderEvent`) | Subscribers on `BrokerEvent.OrderFilled` etc. unaffected. Subscribers on the parent `BrokerEvent` now also receive `BalancesUpdated`. |
| `BybitStateRecovery.reconcile()` ran two paths | runs three (orders, executions, balances) and is `synchronized` | Concurrent reconciles serialize; previously single-threaded by accident. |

No breaking changes for `LogBroker`, `PaperBroker`, `CompositeBroker`, or any strategy/test code that doesn't implement `BybitTransport` directly.

## Usage cookbook

### 1. Default usage — periodic reconcile is automatic

```kotlin
val client = BybitClient()
client.connect()
val broker = BybitSpotBroker(client, bus, SystemClock())
// Initial reconcile fires now (3 REST calls).
// Then every 30 s the reconciler ticks and re-syncs.
// No additional code needed at the strategy level.
```

The race-3 gap from 7f is closed automatically. An order cancelled by Bybit between WS frames is detected within ~30 s.

### 2. Tighter cadence for high-frequency strategies

```kotlin
val broker = BybitSpotBroker(client, bus, clock, pollIntervalMs = 5_000L)
```

5 s reconcile = 12 reconcile rounds/min × 3 REST calls = 36 calls/min. Bybit's read limit is ~120/min; we use ~30%. Within budget for one symbol; watch headroom if combining many brokers on the same account.

### 3. Subscribe to balance updates for sizing

```kotlin
class FundsAwareSizer(private val bus: EventBus) {
    @Volatile private var freeUSDT: BigDecimal = BigDecimal.ZERO

    init {
        bus.subscribe<BrokerEvent.BalancesUpdated> { event ->
            if (event.source == "BYBIT_SPOT") {
                freeUSDT = event.balances["USDT"] ?: BigDecimal.ZERO
            }
        }
    }

    fun maxOrderSize(price: BigDecimal): BigDecimal =
        freeUSDT.divide(price, Money.CONTEXT)
}
```

Strategies query `client.balances` directly for synchronous reads; subscribe to the event for change notifications.

### 4. Subscribe to "any order event" with the new marker

```kotlin
// Useful for audit logging, observability dashboards, etc.
bus.subscribe<BrokerEvent.OrderEvent> { event ->
    log.info("order event: clientId={} brokerId={} type={}",
        event.clientOrderId, event.brokerOrderId, event::class.simpleName)
}
```

The marker matches `OrderAccepted`, `OrderRejected`, `OrderFilled`, `OrderPartiallyFilled`, `OrderCancelled` — but NOT `BalancesUpdated`. Use the parent `BrokerEvent` if you want everything.

### 5. Reusing `PeriodicReconciler` in a future broker

```kotlin
class AlpacaStocksBroker(
    private val client: AlpacaClient,
    private val bus: EventBus,
    private val clock: Clock,
    private val pollIntervalMs: Long = 30_000L,
) : Broker {
    private val recovery = AlpacaStateRecovery(client, bus, clock, ...)
    private val reconciler = PeriodicReconciler(
        intervalMs = pollIntervalMs,
        action = { recovery.reconcile() },
    )

    init {
        recovery.reconcile()
        reconciler.start()
    }

    fun close() = reconciler.stop()
}
```

The reconciler class is broker-agnostic. Each broker brings its own `recovery.reconcile()` shape and REST endpoints.

### 6. Custom executor for shared scheduling

```kotlin
val sharedExecutor = Executors.newScheduledThreadPool(2) { runnable ->
    Thread(runnable, "qkt-shared-reconciler").apply { isDaemon = true }
}
val broker = BybitSpotBroker(
    client, bus, clock,
    pollExecutor = sharedExecutor,
)
// Multiple brokers can share one executor pool to limit thread count at scale.
```

For a single-broker setup, the default per-reconciler thread is fine. At 5+ brokers per process, a shared pool starts to matter.

### 7. Graceful shutdown

```kotlin
val broker = BybitSpotBroker(client, bus, clock)
// ... trade ...
broker.close()        // stops the periodic reconciler
client.close()        // closes WS, kills ping scheduler (from 7e)
```

## Testing patterns

- **`PeriodicReconcilerTest`** uses a private `TestScheduler` that records `scheduleAtFixedRate` calls and lets you drive ticks synchronously via `scheduler.fireTick()`. Pattern matches 7f's `ReconnectSupervisorTest`.
- **`BybitSpotBrokerReconcilerIntegrationTest`** uses a similar `TickScheduler` to drive the broker's reconcile loop. Asserts on `client.posts.size` to verify reconcile actually happened.
- **`FakeBybitClient`** now exposes `balances: Map<String, BigDecimal>` and `updateBalances(snapshot)`. Tests can preset balances OR assert the recovery wrote them.
- **Existing broker tests** must seed reconcile responses (or accept the default `{"result":{}}`) AND clear `client.posts` after broker construction to isolate test-action posts from init-reconcile posts. Common pattern: `BybitSpotBroker(...).also { client.posts.clear() }`.

```kotlin
@Test
fun `submit Market posts only the create call`() {
    val client = FakeBybitClient()
    client.responses["/v5/order/create"] = """{"retCode":0,"result":{"orderId":"abc","orderLinkId":"c1"}}"""
    val broker = BybitSpotBroker(client, newBus(), FixedClock(0L))
        .also { client.posts.clear() }    // discard init-reconcile posts

    broker.submit(...)

    assertThat(client.posts).hasSize(1)
    assertThat(client.posts.single().path).isEqualTo("/v5/order/create")
}
```

## Known limitations

- **No spot position reconciliation against `PositionTracker`.** Pair-net vs per-coin shapes don't align; would lie if forced. Defer to 7h derivatives.
- **No PnL component.** When it ships (likely Phase 8), it'll tag fills `(strategyId, symbol)` so per-strategy / per-broker / cross-cutting views all aggregate at query time.
- **No retry budget on poll failures.** A repeatedly-failing poll logs WARN per failure and continues forever. No alerting; `BrokerEvent.HealthDegraded` is a future addition (likely 7h).
- **`BybitClient.balances` cache TTL = poll interval.** Between polls, `balances` is up to `pollIntervalMs` stale. Strategies that need real-time balance must subscribe to `BalancesUpdated` AND read live (snapshot semantics, not push).
- **`accountType=UNIFIED` hardcoded.** Bybit V5 also supports `CONTRACT` and `SPOT` (legacy) account types. Live accounts on those types return empty wallet lists. Configurable via env (`BYBIT_ACCOUNT_TYPE`) is on the 7h backlog; today the value is fixed in `reconcileBalances()`.
- **No pagination on `/v5/execution/list`.** First 50 records only (carryover from 7f). Strategies submitting >50 fills in a single 30 s window may miss oldest fills until the next reconcile catches them.
- **No `BrokerStateRecovery` interface yet.** Premature with one impl. Extract when 2nd broker (Alpaca/IBKR) lands and the common shape is observable.
- **No JVM-restart persistence.** State recovery handles WS gaps within a single run. JVM crash → manual restart; recovery picks up from Bybit (canonical record).
- **No multi-account.** One `BybitClient` per Bybit account. To trade two accounts, instantiate two clients + two brokers (each with its own reconciler).
- **Reconcile cadence is uniform across order/execution/balance paths.** Splitting cadences (e.g., orders 5 s, balances 60 s) is possible but adds complexity. Keep single cadence until profiling shows it matters.

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase7g-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7g-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase7g.md`](../superpowers/plans/2026-05-06-trading-engine-phase7g.md)
- Phase 7f baseline: [`phase-7f-broker-resilience.md`](phase-7f-broker-resilience.md)
- Bybit V5 wallet-balance API: https://bybit-exchange.github.io/docs/v5/account/wallet-balance
