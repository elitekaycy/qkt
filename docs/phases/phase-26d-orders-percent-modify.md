# Phase 26d — `/orders` poller, PERCENT trailing, order modification

## Summary

Three independent capabilities that complete the MT5 broker:

1. **`/orders` endpoint integration** — a dedicated `MT5PendingOrderPoller` detects when tracked pending tickets disappear from the venue. Combined with a TTL-cached "recently filled" set, the broker disambiguates "pending filled" (Phase 26c position-poller path, emits `OrderFilled`) from "pending externally cancelled or GTD-expired" (this phase's path, emits `OrderCancelled` with reason).

2. **PERCENT trailing stops on MT5** — `MT5OrderTranslator` now accepts an optional `MarketPriceProvider`. When `OrderRequest.TrailingStop.trailMode == PERCENT`, the translator computes `currentPrice × frac` to resolve the absolute trail distance at submit time, then converts to MT5 points via the instrument's `pointSize`.

3. **Order modification surface** — `MT5Broker.modify(orderId, OrderModification)` overrides the default `UnsupportedOperationException`. It looks up the venue ticket from `pendingTickets`, translates `OrderModification` (qkt-side) to `MT5OrderModification` (wire), calls `client.modifyOrder(ticket, ...)`, and publishes a new `BrokerEvent.OrderModified` event on success. Capability declared via `OrderTypeCapability.MODIFY` in `MT5Protocol`.

After this phase the MT5 broker handles every `OrderRequest` shape qkt's DSL can emit, with full lifecycle event propagation. Hedge-straddle's pending-OCO loop runs end-to-end with sub-poll-interval latency for cancel-on-fill and dedicated cancellation detection for GTD-expiry.

## What's new

### `/orders` poller

- **`MT5Client.getPendingOrders(magic): List<MT5PendingOrder>`** — new method, mirrors `getPositions` shape. Returns empty if the gateway doesn't expose `/orders` (404 → retry-with-null path).
- **`MT5PendingOrder`** wire type — `ticket`, `symbol`, `type` (BUY_STOP/SELL_LIMIT/...), `volume`, `priceOpen`, `sl`, `tp`, `magic`, `timeSetup`, `timeExpiration`, `comment`.
- **`MT5PendingOrderPoller`** — independent thread-based poller, same lifecycle pattern as `MT5PositionPoller`. Calls back to the broker via `onPendingDisappeared(ticket)`.
- **TTL-cached fill-vs-cancel disambiguation** — `MT5Broker.recentlyFilledTickets: Map<Long, Long>` (ticket → fill epoch ms). Populated by `onPendingPositionOpened` (Phase 26c path); consumed by `onPendingDisappeared`. The TTL is `pollIntervalMs × 3` — enough headroom for the position poller to tick after the pending poller does.

### PERCENT trailing

- **`MT5OrderTranslator(... priceTracker: MarketPriceProvider? = null)`** — new optional constructor parameter. Tests construct the translator without it; production wires it through from `BrokerFactory`.
- **`translateTrailingStop` PERCENT branch** — computes `mid × trailAmount / 100` at submit time, then `÷ pointSize` for the MT5 wire's `sl_distance` field.
- **Actionable error paths**:
    - No `priceTracker` configured → `"PERCENT trailing requires a MarketPriceProvider"`
    - Tracker present but no `lastPrice` for symbol → `"PERCENT trailing requires lastPrice for $symbol; ensure the tick stream is active"`

### Order modification

- **`BrokerEvent.OrderModified`** — new event sibling of `OrderCancelled`. Carries `clientOrderId`, `brokerOrderId`, `strategyId`, `timestamp`. The event itself doesn't carry the new SL/TP values — qkt-side `OrderManager` updates state from the `OrderModification` the caller supplied.
- **`MT5Client.modifyOrder(ticket, MT5OrderModification)`** — POSTs to `/modify-order/{ticket}`. Encodes only the non-null fields (price, sl, tp, sl_distance, expiration).
- **`MT5OrderModification`** wire type — five nullable BigDecimal/Long fields.
- **`MT5Broker.modify(orderId, changes)`** — looks up ticket in `pendingTickets`; rejects fast if not found; calls client; emits `OrderModified` on success or returns a `SubmitAck(accepted=false)` with the venue's retcode/message.
- **`OrderTypeCapability.MODIFY`** added to `MT5Protocol.capabilities`.

## Migration from previous phase

Two breaking changes for downstream consumers:

| Before (Phase 26c) | After (Phase 26d) |
| --- | --- |
| `MT5OrderTranslator(profile, symbol)` | `MT5OrderTranslator(profile, symbol, priceTracker: MarketPriceProvider? = null)` |
| `MT5Broker(profile, bus, clock, client?)` | `MT5Broker(profile, bus, clock, priceTracker: MarketPriceProvider? = null, client?)` |

Both new parameters default to `null` and preserve existing test fixtures. Production wiring (`DaemonCommand.kt`) updates the factory to pass the `MarketPriceTracker` it already receives.

`BrokerEvent` gains a new variant (`OrderModified`). `EventBus.stamp` adds the case. No existing event subscriptions need to change — they only receive the variants they subscribe to.

`MT5BrokerIntegrationTest` setup now enqueues 3 `[]` responses instead of 2 (the third for the pending poller's startup seed). Existing tests' `server.takeRequest()` chains updated to consume the extra setup call.

`MT5Protocol.capabilities` gains `MODIFY`. Strategy authors can now rely on the capability check at submit time when calling `broker.modify` from Kotlin-DSL strategies.

## Usage cookbook

### PERCENT trailing stop on EURUSD

```kotlin
val req = OrderRequest.TrailingStop(
    id = "trail-pct",
    symbol = "EURUSD",
    side = Side.BUY,
    quantity = BigDecimal("0.1"),
    trailAmount = BigDecimal("0.5"),  // 0.5% of current price
    trailMode = TrailMode.PERCENT,
    timeInForce = TimeInForce.GTC,
    timestamp = clock.now(),
)
broker.submit(req)
// At current price 1.10000 and pointSize 0.00001:
//   abs distance = 1.10000 × 0.5 / 100 = 0.00550
//   slDistance   = 0.00550 ÷ 0.00001 = 550 points
// Wire request: { type: "BUY", sl_distance: 550, ... }
```

### Detecting an externally cancelled pending order

Place a pending order from any strategy. If the user cancels it in MetaTrader (or its GTD expires):

```
broker.exness pending poller: ticket=999 left /orders snapshot
broker.exness OrderCancelled clientOrderId=stop-26d-cancel reason="external or gtd-expired (pending disappeared from venue)"
OrderManager removes stop-26d-cancel from tracked orders
```

The strategy's `POSITION.<stream>` accessor immediately reflects "no pending" without waiting for the next strategy tick.

### Modifying a working pending order

```kotlin
// Move a pending stop's trigger price from 1.1050 to 1.1075
val ack = broker.modify(
    orderId = "stop-1",
    changes = OrderModification(newStopPrice = BigDecimal("1.1075")),
)
if (ack.accepted) {
    // OrderModified event already on bus; OrderManager has updated its tracked trigger price
}
```

For changing SL/TP on a position post-fill, use the existing `Signal.Modify(orderId = positionTicket, ...)` path — same broker method, different orderId source.

## Testing patterns

### Path-routing dispatcher

When testing brokers with multiple pollers against MockWebServer, use a `Dispatcher` to route by path instead of a FIFO queue — otherwise the position poller and pending poller race for the next queued response:

```kotlin
server.dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse = when {
        request.path.orEmpty().startsWith("/positions") -> MockResponse().setBody("[]")
        request.path.orEmpty().startsWith("/orders") -> MockResponse().setBody("[]")
        request.path.orEmpty().startsWith("/order") -> MockResponse().setBody(/* placeOrder response */)
        request.path.orEmpty().startsWith("/modify-order") -> MockResponse().setBody(/* modify response */)
        else -> MockResponse().setResponseCode(404)
    }
}
```

This pattern is now used by both the Phase 26c and Phase 26d integration tests. Strongly recommend it for any new broker integration tests.

### Flip-flag for snapshot transitions

When the test needs the venue's snapshot to change mid-flight (e.g. "pending appears then disappears"), use a mutable flag captured by the dispatcher:

```kotlin
var ordersHasTicket = false
server.dispatcher = object : Dispatcher() { /* reads ordersHasTicket */ }

broker.submit(req)
ordersHasTicket = true
Thread.sleep(300)  // give the poller time to observe the pending
ordersHasTicket = false
// poller's NEXT tick sees disappearance → OrderCancelled
```

## Known limitations

- **mt5-gateway endpoint dependency.** The `/orders` and `/modify-order` endpoints must exist on the gateway side for full Phase 26d functionality. If `/orders` doesn't exist (returns 404), `MT5Client.getPendingOrders` returns empty — the poller stays harmless but doesn't detect cancellations. If `/modify-order` doesn't exist, `broker.modify` returns rejected with HTTP error. Verify gateway capabilities before relying on these in production.

- **`OrderModification` carries `newQuantity`, `newLimitPrice`, `newStopPrice` only.** No `newTakeProfit` or `newStopLoss` (separate from trigger price) today. `MT5Broker.modify` only sends the `price` field. Extend `OrderModification` when a use case appears.

- **No DSL surface for `MODIFY`.** Strategy authors writing Kotlin-DSL strategies can call `Signal.Modify(orderId, changes)` directly. Text-DSL `MODIFY <stream> SET ...` syntax is a future phase.

- **TTL is per-broker-instance.** If the daemon restarts, the `recentlyFilledTickets` map is empty. A pending order that filled just before the restart and whose disappearance from `/orders` arrives just after would be mis-classified as "external or gtd-expired" — an edge case but worth documenting. State recovery (Phase 7g) does its own reconciliation pass on startup that should catch this.

- **Position modification (modifying SL/TP on an open position, not a pending order)** is supported by MT5's `OrderModify` but not yet wired in qkt. The broker's `modify` path uses `pendingTickets` for ticket lookup — extending it to position tickets is a small change but deferred.

## References

- Spec: `docs/superpowers/specs/2026-05-12-phase26d-orders-percent-modify-design.md`
- Plan: `docs/superpowers/plans/2026-05-12-phase26d-orders-percent-modify.md`
- Code:
  - `src/main/kotlin/com/qkt/broker/mt5/MT5PendingOrderPoller.kt` — new
  - `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — modify override, pending poller wiring, TTL cache
  - `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt` — PERCENT branch
  - `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt` — getPendingOrders, modifyOrder
  - `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt` — MT5PendingOrder, MT5OrderModification
  - `src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt` — MODIFY capability
  - `src/main/kotlin/com/qkt/events/BrokerEvent.kt` — OrderModified variant
- Tests: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`, `MT5OrderTranslatorTest.kt`
- Phase 26c (the fill-detection path this phase complements): `docs/phases/phase-26c-pending-fill-lifecycle.md`
