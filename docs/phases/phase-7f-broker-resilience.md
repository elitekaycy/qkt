# Phase 7f — Broker Connection Resilience

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase7f-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7f-design.md)

## Summary

Phase 7f makes the broker layer survive transient network failures. A new generic `ReconnectSupervisor` (`com.qkt.common.net`) wraps any reconnectable client; `BybitClient` uses it for WS lifecycle. `postSigned` retries on connection-level failures (3 attempts with backoff) but never on Bybit `retCode != 0` errors. `BybitStateRecovery` reconciles the engine's view against Bybit's REST snapshot on every reconnect, emitting synthetic `BrokerEvent`s for state changes that happened during the gap. Internal map pruning fixes the memory leak in `BybitSpotBroker.symbolByClientOrderId`. Initial connect failures throw immediately so bad credentials surface to the operator instead of entering an infinite retry loop.

## What's new

- `com.qkt.common.net.BackoffPolicy` — interface plus `ExponentialBackoff` and `FixedDelayBackoff` impls.
- `com.qkt.common.net.ReconnectSupervisor` — generic backoff scheduler, broker-agnostic. Reusable by future brokers (Alpaca, IBKR, etc.).
- `com.qkt.broker.bybit.BybitStateRecovery` — Bybit-specific REST reconcile (`/v5/order/realtime` + `/v5/execution/list`). Dedup via `seenExecIds`.
- `com.qkt.broker.bybit.BybitConnectException` — thrown when the initial connect fails (e.g., bad credentials, host unreachable).
- `BybitClient.isConnected: Boolean` — observable WS state for strategies that want to gate on connection health.
- `BybitClient.onReconnect(handler)` — callback fires after successful re-auth + re-subscribe (NOT on initial connect).
- `BybitClient.postSigned` retries on connection-level failures: 3 attempts with 0.5s/1s/2s backoff. Bybit `retCode != 0` errors are NOT retried.
- `BybitTransport` interface gains `isConnected` and `onReconnect`.
- `BybitOrderTranslator.parseOpenOrder` and `parseExecution` — pure parsers extracted from broker for reuse by recovery.
- `BybitSpotBroker.knownOrders` map — internal view used by recovery to know what the broker thinks is working. Pruned on terminal events.
- `BybitSpotBroker(... recoveryWindowMs: Long = 5 * 60_000L)` — configurable execution-list lookback window. Default 5 min.
- Pruning of `symbolByClientOrderId` and `knownOrders` on `BrokerEvent.OrderFilled`/`OrderCancelled`/`OrderRejected`.

## Migration from previous phase

| Phase 7e | Phase 7f | Notes |
|---|---|---|
| `BybitTransport` interface had 3 methods | now has 5 (`+isConnected`, `+onReconnect`) | `FakeBybitClient` updated; users implementing `BybitTransport` directly need to add the new members. |
| `BybitClient.connect()` non-blocking on failure | now throws `BybitConnectException` if initial auth fails within 10s | Catch this in entry points if you want to retry initial connects manually. |

No behavior changes for existing `LogBroker`/`PaperBroker`/`CompositeBroker` (they don't implement `BybitTransport`).

## Usage cookbook

### 1. Construct BybitClient and let it auto-reconnect

```kotlin
val client = BybitClient()    // testnet, env-driven keys
client.connect()              // throws BybitConnectException on bad credentials
// WS goes down? Supervisor reconnects with backoff. State recovery fires
// when WS comes back. Strategies see uninterrupted fill events
// (synthesized for the gap, then live).
```

No new code at the strategy or pipeline level — resilience is automatic.

### 2. Strategy gating on connection state

```kotlin
val broker = BybitSpotBroker(client, bus, clock)
// ... in a strategy or supervisory layer:
if (!client.isConnected) {
    log.info("Bybit disconnected; pausing new submissions")
    return
}
```

REST-side submits still work during disconnect (they go through `postSigned` independently). The `isConnected` flag tracks WS health specifically.

### 3. Custom recovery window

```kotlin
// Tighten for high-frequency strategies (less startup history; lower load):
val broker = BybitSpotBroker(client, bus, clock, recoveryWindowMs = 30_000L)

// Or widen for sparse strategies:
val broker = BybitSpotBroker(client, bus, clock, recoveryWindowMs = 60 * 60_000L)
```

### 4. Observe reconnects

```kotlin
client.onReconnect {
    log.info("Bybit WS reconnected; engine state synced via recovery")
}
```

### 5. Future broker reuse of `ReconnectSupervisor`

```kotlin
class AlpacaClient(...) {
    private val supervisor = ReconnectSupervisor(
        backoff = ExponentialBackoff(initialMs = 500L, capMs = 30_000L),
        attemptReconnect = { reconnectImpl() },
        onReconnected = { fireRecovery() },
    )
}
```

The supervisor is broker-agnostic. Each broker brings its own `attemptReconnect` closure.

### 6. Catching initial connect failures

```kotlin
val client = BybitClient()
try {
    client.connect()
} catch (e: BybitConnectException) {
    log.error("Bybit initial connect failed: ${e.message}")
    // Retry manually with operator intervention, or surface to monitoring.
}
```

## Testing patterns

- **`ReconnectSupervisorTest`**: hand-rolled synchronous `ScheduledExecutorService` (`TestScheduler`) records scheduled delays without real wall-clock waits.
- **`FakeBybitClient.fireOnReconnect()`**: drives state recovery in tests without a real WS.
- **`BybitClient` reconnect testing**: inject a fake `wsFactory` lambda via the constructor; tests use a fake `WebSocket` that never delivers an auth ack to verify the timeout path.
- **Transport retry testing**: inject a custom `OkHttpClient` with an interceptor that fails N times then succeeds; assert attempt count and final response.
- **State recovery testing**: programmable REST responses on `FakeBybitClient` for `/v5/order/realtime` and `/v5/execution/list`; assert emitted `BrokerEvent`s.

## Known limitations

- **No persistence across JVM restarts.** State recovery handles WS gaps within a single run; JVM crash → manual restart, recovery picks up from Bybit (canonical record).
- **No position reconciliation.** Phase 7g.
- **No account / equity / buying-power polling.** Phase 7g.
- **No rate-limit (429) enforcement.** Bybit's 429 propagates as `OrderRejected`. Phase 7h.
- **Auth failure on subsequent reconnects retries indefinitely.** If your API key is revoked while running, the supervisor logs a warning per attempt but never gives up. Operator must SIGTERM. (Initial connect failure DOES fail loud — this is only the running-broker case.)
- **Recovery uses POST for `/v5/order/realtime` and `/v5/execution/list`.** Bybit V5 docs typically show GET; POST works in practice. If a future Bybit version requires GET, add `BybitClient.getSigned()`.
- **Pagination on `/v5/execution/list` is not handled.** First 50 records only. If a strategy submits >50 fills during a single WS gap, oldest fills are missed.
- **Race-condition gap (spec §10 case 3):** an order cancelled between the open-orders query and the next reconcile is missed until the subsequent reconnect catches it. Phase 7g closes this gap with periodic REST polling (using the same `BybitStateRecovery.reconcile()` machinery on a timer).
- **Fixed retry counts and delays.** `postSigned` retries are hardcoded at 3 attempts / 0.5s/1s/2s. No tuning knob.
- **No `BrokerStateRecovery` interface yet.** Per-broker recovery shapes will likely be similar across Alpaca/IBKR; we'll extract the common interface when 2-3 implementations exist (premature-abstraction trap avoided).

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase7f-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7f-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase7f.md`](../superpowers/plans/2026-05-06-trading-engine-phase7f.md)
- Phase 7e baseline: [`phase-7e-bybit-and-composite.md`](phase-7e-bybit-and-composite.md)
- Bybit V5 API: https://bybit-exchange.github.io/docs/v5/intro
