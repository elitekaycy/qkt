# Phase 26c — Pending-order fill-event lifecycle on MT5

## Summary

Phase 26c closes the last gate between qkt and live MT5 trading for the pending-order family. After this phase, when a pending order on MT5 fills, qkt learns about it within `pollIntervalMs` (1000ms default) and publishes a `BrokerEvent.OrderFilled` carrying the original qkt-side `clientOrderId`. That single event triggers all the downstream wiring that was already in place from earlier phases:

- `OrderManager.onFilled` marks the order FILLED
- For OCO groups (Phase 26a's `OCO_ENTRY`), the existing `siblings[clientOrderId]` map iterates and cancels the surviving leg via `Broker.cancel`
- Strategy-side position state updates so subsequent rules see `POSITION.<stream> != 0`
- Reconciliation continues to work for closes (existing behavior)

Hedge-straddle's pending-OCO loop now runs end-to-end live: place OCO BUY_STOP / SELL_STOP → one fills → other auto-cancels within one poll interval.

**Out of scope, intentionally narrowed:** order-modification surface and dedicated `/orders` endpoint integration. Both depend on mt5-gateway capabilities I could not verify autonomously. Pushed to Phase 26d. PERCENT trailing also remains deferred — the translator needs a `MarketPriceTracker` injection that's invasive at the broker construction site.

## What's new

### Position poller detects opens

`MT5PositionPoller` previously computed `closed = lastSnapshot.keys - current.keys` and emitted `OrderFilled` for each close (modeling a position closing as a synthetic fill on the opposite side). Phase 26c adds the inverse delta:

```kotlin
val opened = current.keys - lastSnapshot.keys
for (ticket in opened) {
    val p = current[ticket] ?: continue
    onPositionOpened?.invoke(p)
}
```

The `onPositionOpened` callback is a new constructor parameter on `MT5PositionPoller`, defaulted to `null` for backward compatibility with any test fixtures that don't need correlation.

### Broker correlates ticket → orderId

`MT5Broker` already tracked `pendingTickets: Map<orderId, ticket>` from Phase 26b (used by `cancel(orderId)`). Phase 26c adds the reverse:

```kotlin
private val pendingByTicket: MutableMap<Long, PendingMeta> = ConcurrentHashMap()

private data class PendingMeta(
    val orderId: String,
    val strategyId: String,
)
```

Populated when a pending order's `placeOrder` response returns a non-zero ticket. Cleared on observed fill or cancel.

The broker passes a method reference (`::onPendingPositionOpened`) into the poller's constructor. When a position appears with a ticket in `pendingByTicket`, the broker emits:

```kotlin
bus.publish(BrokerEvent.OrderFilled(
    clientOrderId = meta.orderId,           // qkt-side id, NOT the ticket
    brokerOrderId = position.ticket.toString(),
    symbol = mt5Symbol.toQkt(position.symbol),
    side = if (position.type == 0) Side.BUY else Side.SELL,
    price = position.priceOpen,
    quantity = position.volume,
    strategyId = meta.strategyId,
    timestamp = clock.now(),
))
```

External positions (ticket not in `pendingByTicket`) are ignored — those are the position poller's reconciliation surface, not this phase's concern.

### OCO sibling cancel-on-fill — already works end-to-end

No new code. The propagation flow:

1. `OCO_ENTRY { BUY_STOP, SELL_STOP }` compiles to `Signal.Submit(StandaloneOCO(buyStop, sellStop))` (Phase 26a)
2. `OrderManager.submitOco` (Phase 26a) populates `siblings[buyStop.id] = [sellStop.id]` and vice versa, then dispatches each leg via `broker.submit`
3. `MT5Broker.submit` translates each as a native `BUY_STOP`/`SELL_STOP` (Phase 26b) and tracks `pendingByTicket[ticket] = PendingMeta(leg.id, ...)` (Phase 26c)
4. MT5 fills one leg → position appears in `/positions` on next poll
5. Position poller delta detects open → broker callback emits `OrderFilled(leg.id)` (Phase 26c)
6. `OrderManager.onFilled` (existing) iterates `siblings[leg.id]` → `cancel(otherLeg.id)`
7. `MT5Broker.cancel` (Phase 26b) calls `client.cancelOrder(otherTicket)`
8. MT5 cancels the surviving pending; broker publishes `OrderCancelled`

End-to-end latency: bounded by `pollIntervalMs` (1s default). For a 5-minute candle strategy like hedge-straddle, this is acceptable.

### Cancel-path housekeeping

`MT5Broker.cancel(orderId)` now also removes from `pendingByTicket` (not just `pendingTickets`). Prevents a stale ticket → orderId mapping leaking memory or, worse, firing a duplicate `OrderFilled` if the venue later returns the ticket as a position for some pathological reason.

## Migration from previous phase

Pure additions. One constructor signature change:

| Before | After |
| --- | --- |
| `MT5PositionPoller(client, profile, symbol, bus, clock)` | `MT5PositionPoller(client, profile, symbol, bus, clock, onPositionOpened = null)` |

The new parameter is optional with a `null` default. `MT5Broker` is the only caller in the production code; it now passes `::onPendingPositionOpened`. Test fixtures that construct the poller directly don't need changes.

No DSL changes. Strategy code identical. Hedge-straddle example becomes live-runnable.

## Usage cookbook

### Hedge-straddle, end-to-end live on Exness

Same `examples/hedge-straddle/hedge-straddle.qkt` from Phase 26a. With Phase 26c on `main`:

```bash
cd ~/Desktop/personal/qkt-strategies-live
./deploy.sh prereq   # verify .env + qkt:latest image
./deploy.sh paper    # docker compose up; deploys hedge-straddle
./deploy.sh logs hedge_straddle_live -f
```

Expected log sequence at session open (07:55 UTC):

```
[INFO] strategy hedge_straddle_live rule fired: OCO_ENTRY placed
[INFO] broker.exness pending order submitted ticket=12345 side=BUY type=BUY_STOP price=2010.5
[INFO] broker.exness pending order submitted ticket=12346 side=SELL type=SELL_STOP price=1999.5
[INFO] strategy hedge_straddle_live waiting for trigger
```

When one leg triggers (~08:02):

```
[INFO] broker.exness mt5-poller detected new position ticket=12345 symbol=XAUUSDm
[INFO] broker.exness OrderFilled clientOrderId=<oco-leg1-id> price=2010.5
[INFO] OrderManager siblings cancel-on-fill: cancel <oco-leg2-id>
[INFO] broker.exness OrderCancelled clientOrderId=<oco-leg2-id> ticket=12346
[INFO] strategy hedge_straddle_live winner=BUY entry=2010.5
```

Latency from venue-fill to qkt-cancel-of-sibling: typically <1s (one poll cycle).

### Tracking pending tickets in the broker

For debugging:

```kotlin
val broker = MT5Broker(profile, bus, clock)
// ... submit a pending stop ...
val ticket = broker.pendingTicketFor(orderId)  // exposed for tests; package-private
```

(Internal API; not for strategy authors.)

## Testing patterns

### Pending fill end-to-end via MockWebServer

```kotlin
@Test
fun `pending fill propagates via position poller`() {
    // 1. broker setup with fast poll interval
    val broker = MT5Broker(profile.copy(pollIntervalMs = 100), bus, clock)

    // 2. enqueue place response
    server.enqueue(MockResponse().setBody(
        """{"result":{"retcode":10009,"order":777,"deal":0,"price":"1.1050","comment":"ok"}}"""
    ))
    broker.submit(stopReq.copy(id = "stop-1"))

    // 3. enqueue /positions response with the new position
    server.enqueue(MockResponse().setBody(
        """[{"ticket":777,"symbol":"EURUSDm","type":0,...}]"""
    ))

    // 4. wait for poller to tick and broker to publish OrderFilled
    eventually(deadline = 3_000L) {
        captured.filterIsInstance<BrokerEvent.OrderFilled>()
            .any { it.clientOrderId == "stop-1" }
    }
}
```

See `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt` for the full test.

## Known limitations

### GTD-expired pending orders stay in qkt's local map

If a pending order's GTD expires on MT5 without filling, the venue cancels it. qkt has no way to detect this without a dedicated `/orders` endpoint on mt5-gateway. The ticket stays in `pendingByTicket` until:
- The user calls `cancel(orderId)` manually
- The daemon restarts (state recovery clears these)

**Operational mitigation:** for hedge-straddle, the GTD expiry is 10 minutes. Stale entries accumulate at a rate of ~1 per session-hour-attempt that doesn't fill. Over a day, ~6 stale entries — not a memory problem, but does mean qkt's view of "active pending orders" can diverge from the venue's. Phase 26d adds the `/orders` endpoint integration to fix.

### External cancellations in MetaTrader undetected

Same root cause — no `/orders` endpoint means we can't see when a tracked ticket leaves the pending set without becoming a position. Phase 26d.

### PERCENT trailing still rejected on MT5

Phase 26b rejected `OrderRequest.TrailingStop` with `trailMode = PERCENT`. Phase 26c keeps this rejection. The fix needs `MarketPriceTracker` injection into `MT5OrderTranslator` and a small change at the broker construction site. Reasonable Phase 26d work; intentionally not bundled to keep Phase 26c focused on the OCO lifecycle path.

### Order modification surface deferred

`Broker.modify(orderId, OrderModification)` exists with a default `UnsupportedOperationException`. `MT5Broker.modify` is not implemented. Phase 26d covers this together with `/orders` and PERCENT trailing.

## References

- Spec: `docs/superpowers/specs/2026-05-12-phase26c-pending-fill-lifecycle-design.md`
- Code:
  - `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt` — opened-position detection
  - `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — `pendingByTicket` map and `onPendingPositionOpened` handler
- Tests:
  - `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt` — `pending fill propagates via position poller`
- Engine OCO wiring (Phase 26a): `src/main/kotlin/com/qkt/app/OrderManager.kt:693` (submitOco), `:818` (sibling cancel-on-fill)
- Phase 26a changelog: `docs/phases/phase-26a-pending-oco-and-clock.md`
- Phase 26b changelog: `docs/phases/phase-26b-mt5-pending-family.md`
- Phase 26d (next): `/orders` endpoint, PERCENT trailing, order modification
