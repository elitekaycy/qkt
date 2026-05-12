# Phase 26c — Pending-order fill-event lifecycle on MT5

> Closes the live-capital gate between qkt and MT5. Phase 26b made pending-order *placement* native; Phase 26c makes the *fill events* propagate back to qkt's `OrderManager` so OCO sibling cancel-on-fill, position tracking, and reconciliation work end-to-end on live.

## Goal

Detect when a pending order on MT5 fills, gets cancelled, or expires — and propagate those events to qkt's `OrderManager` so:

1. Filled OCO legs trigger the existing `siblings[]` cancel-on-fill flow (Phase 26a engine wiring)
2. Filled pending orders show up as positions in qkt's local state (matching the venue)
3. User-initiated cancels via `qkt cancel` propagate to MT5
4. PERCENT trailing stops on MT5 work (Phase 26b deferred this for lack of a current-price seed at submit time)
5. Pending orders can be modified server-side (move the SL, change the trigger price)

After this phase, hedge-straddle's pending-OCO loop runs end-to-end live, with sub-poll-interval latency for OCO cancellation.

## Motivation

Phase 26b shipped native MT5 translation for `Stop`, `Limit`, `StopLimit`, `StandaloneOCO`, `TrailingStop`. Pending orders place on the venue successfully, but qkt has no way to learn that they've filled — `MT5Broker.submit` publishes `OrderAccepted` only, and the existing `MT5PositionPoller` only detects *closed* positions (`lastSnapshot.keys - current.keys`).

This means:
- An OCO BUY_STOP / SELL_STOP gets placed; one fills on the venue; qkt's `OrderManager` never learns about the fill; the sibling never gets cancelled; the strategy continues holding TWO conflicting pending stops indefinitely.
- A pending limit fills; qkt's `Position` state doesn't update; subsequent rules check `POSITION.<stream>` and see zero when there's actually an open position on the venue.

The result is qkt-side state drift from the live venue — exactly the failure mode the reconciliation phase (7g) was built to surface, but reconciliation runs at coarse intervals and only catches the drift, not the moment the fill happens.

For a 5-minute candle strategy like hedge-straddle, the position-poller-as-detector approach (Phase 26b's default) catches fills within `pollIntervalMs` (1s default) — workable but not great. For faster strategies, sub-second is required. Phase 26c closes both gaps with a single, focused poller extension.

## Scope

### In scope

**A. Position poller detects opens** (~30 LOC + tests)

`MT5PositionPoller.tick()` already computes `closed = lastSnapshot.keys - current.keys`. Add the inverse:

```kotlin
val opened = current.keys - lastSnapshot.keys
for (ticket in opened) {
    val p = current[ticket] ?: continue
    onPositionOpened?.invoke(p)
}
```

The `onPositionOpened` callback is supplied by the broker so it can correlate venue tickets back to qkt-side orderIds.

**B. Broker ticket → orderId correlation** (~80 LOC + tests)

`MT5Broker` already has `pendingTickets: Map<orderId, ticket>` (Phase 26b). Add the reverse:

```kotlin
private val ticketToOrderId: MutableMap<Long, PendingMeta> = ConcurrentHashMap()

private data class PendingMeta(
    val orderId: String,
    val request: OrderRequest,  // need symbol/side/quantity to publish OrderFilled
)
```

Populate on pending placement (in `submitSingle` and `submitComposite`). Remove on cancel or on observed fill.

Register a callback with the position poller. On opened-position:
- If `ticketToOrderId[position.ticket]` exists → that pending filled.
- Emit `BrokerEvent.OrderFilled` with the original `clientOrderId`, `strategyId`, `symbol`, `side`, `quantity`, `price = position.priceOpen`.
- Remove from both `pendingTickets` and `ticketToOrderId`.
- Engine-side: `OrderManager.onFilled` sees this event, marks the order FILLED, iterates `siblings[clientOrderId]` and cancels each non-terminal sibling. This propagates the OCO cancel without any new engine work.

If the opened position has a ticket NOT in our map, it's an external position (user opened it in MetaTrader manually, or another qkt instance with the same magic). Don't emit — that's the position poller's "reconcile" surface, separate concern.

**C. Cancel-event sourcing** (~20 LOC + tests)

Today `MT5Broker.cancel(orderId)` publishes `OrderCancelled` synchronously and removes the entry from `pendingTickets`. That's already correct. No change needed.

BUT — the user can also cancel from MetaTrader directly. To detect this, we'd need a pending-orders endpoint on mt5-gateway to see when a tracked ticket leaves the pending set without becoming a position. Without that endpoint, externally-cancelled pendings show up as "stuck in qkt's pendingTickets map forever."

Mitigation for Phase 26c: emit a periodic `MT5Broker.reconcilePendingTickets()` that fetches the venue's pending orders (if /orders endpoint exists) and reconciles. If the endpoint doesn't exist, document as a known gap and lean on a TTL — pending tickets older than the GTD expiry get evicted from the map and treated as cancelled.

**D. PERCENT trailing on MT5** (~40 LOC + tests)

Phase 26b rejected `OrderRequest.TrailingStop` with `trailMode = PERCENT` because the translator had no access to a current price. Phase 26c provides one via `MarketPriceTracker`:

```kotlin
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val priceTracker: MarketPriceTracker? = null,  // new optional dep
)
```

For PERCENT trailing, compute:
```kotlin
val currentPrice = priceTracker?.lastPrice(req.symbol)
    ?: error("PERCENT trailing requires MarketPriceTracker; configure broker with priceTracker")
val absoluteDistance = currentPrice.multiply(req.trailAmount.divide(BigDecimal(100)))
val distancePoints = absoluteDistance.divide(point, MathContext.DECIMAL64).toLong()
```

`MarketPriceTracker` is already used throughout the engine; injecting it into the translator is a small wiring change.

**E. Order modification surface** (~120 LOC + tests)

MT5 supports `OrderModify` for changing SL, TP, or trigger price on an existing order or position. qkt's `OrderManager` calls `Broker.modify(...)` in some paths today, but `MT5Broker` doesn't implement it.

Add:

```kotlin
// Broker.kt — new interface method (already exists in some form?)
fun modify(orderId: String, modification: OrderModification)

// MT5Broker.kt — implementation
override fun modify(orderId: String, modification: OrderModification) {
    val ticket = pendingTickets[orderId] ?: positionTickets[orderId] ?: return
    client.modifyOrder(ticket, modification.toMt5())
    // Reconciliation will pick up the change; no immediate event publishing needed
}
```

Verify `OrderModification` shape — likely has fields for new SL, new TP, new trigger price. Wire through MT5Client.

**F. Comprehensive integration tests via `FakeMt5Client`** (~250 LOC)

Cover:
- Place pending Stop → emit OrderAccepted (existing)
- Position poller observes the pending become a position → emit OrderFilled with original clientOrderId
- Place OCO with two stops → both pending, both tracked
- One leg fills → OrderFilled for that leg → OrderManager.siblings → cancel for the other leg → MT5Broker.cancel → mock receives cancelOrder call
- Place TrailingStop with PERCENT mode and a priceTracker → translates with correct points
- Modify a pending order → mock receives modifyOrder call

### Out of scope

**Dedicated `/orders` endpoint on mt5-gateway.** Without it, GTD-expired pending orders and externally-cancelled-in-MT4 pendings remain in qkt's `pendingTickets` map until TTL eviction. Adding the endpoint is mt5-gateway-side work, separate from qkt. Document the gap; rely on TTL for now.

**DSL surface for order modification.** This phase adds the engine-level `Broker.modify(orderId, ...)` plumbing. A DSL action like `MODIFY <stream> SET STOP_LOSS AT ...` requires brainstorming the syntax + state-tracking semantics. Future phase.

**Position-side modification (post-fill).** Today's design modifies the *pending order*. After a pending fills and becomes a position, modifying its SL/TP is a different MT5 wire call (`OrderModify` with a position ticket, not order ticket). Add if time permits; otherwise defer.

**Bybit, ICMarkets-specific quirks, or Pepperstone profile variations.** Phase 26c keeps the surface generic; per-broker tuning is a future phase.

## Architecture

### Position poller extension

```kotlin
class MT5PositionPoller(
    // ... existing fields ...
    private val onPositionOpened: ((MT5Position) -> Unit)? = null,
) {
    private fun tick() {
        val current = client.getPositions(magic = profile.magic).associateBy { it.ticket }
        val closed = lastSnapshot.keys - current.keys
        for (ticket in closed) { /* existing OrderFilled-on-close */ }

        val opened = current.keys - lastSnapshot.keys
        for (ticket in opened) {
            val p = current[ticket] ?: continue
            onPositionOpened?.invoke(p)
        }

        lastSnapshot = current
    }
}
```

### MT5Broker integration

```kotlin
class MT5Broker(...) {
    private val pendingTickets: MutableMap<String, Long> = ConcurrentHashMap()
    private val ticketToOrderMeta: MutableMap<Long, PendingMeta> = ConcurrentHashMap()

    private val poller = MT5PositionPoller(
        client, profile, mt5Symbol, bus, clock,
        onPositionOpened = ::onPendingFilled,
    )

    private fun onPendingFilled(position: MT5Position) {
        val meta = ticketToOrderMeta.remove(position.ticket) ?: return
        pendingTickets.remove(meta.orderId)
        bus.publish(BrokerEvent.OrderFilled(
            clientOrderId = meta.orderId,
            brokerOrderId = position.ticket.toString(),
            symbol = mt5Symbol.toQkt(position.symbol),
            side = if (position.type == 0) Side.BUY else Side.SELL,
            price = position.priceOpen,
            quantity = position.volume,
            strategyId = meta.strategyId,
            timestamp = clock.now(),
        ))
    }

    private fun trackPending(orderId: String, ticket: Long, request: OrderRequest) {
        pendingTickets[orderId] = ticket
        ticketToOrderMeta[ticket] = PendingMeta(orderId, request)
    }

    override fun cancel(orderId: String) {
        val ticket = pendingTickets.remove(orderId) ?: return
        ticketToOrderMeta.remove(ticket)
        // ... existing client.cancelOrder + OrderCancelled emission ...
    }
}

private data class PendingMeta(
    val orderId: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val strategyId: String,
)
```

### OCO sibling cancel-on-fill — no new code

`OrderManager.submitOco` (Phase 26a) already populates `siblings[leg1.id] = [leg2.id]`. `OrderManager.onFilled` (existing) iterates siblings and calls `cancel(sibId)`. The new Phase 26c work makes `BrokerEvent.OrderFilled` arrive with the correct `clientOrderId` — that's all the sibling-cancel flow needs.

### PERCENT trailing

Inject `MarketPriceTracker?` into `MT5OrderTranslator`. In `translateTrailingStop`, when `trailMode == PERCENT`:

```kotlin
val tracker = priceTracker
    ?: error("PERCENT trailing requires priceTracker; configure broker with it or use ABSOLUTE mode")
val mid = tracker.lastPrice(req.symbol)
    ?: error("PERCENT trailing requires lastPrice for ${req.symbol}; ensure tick stream is active")
val absDistance = mid.multiply(req.trailAmount).divide(BigDecimal(100), MathContext.DECIMAL64)
val distancePoints = absDistance.divide(pointFor(req.symbol), MathContext.DECIMAL64)
    .setScale(0, RoundingMode.HALF_UP).toLong()
// ... rest of translation ...
```

### Order modification

The `Broker` interface gains a `modify` method (if it doesn't already exist):

```kotlin
interface Broker {
    fun modify(orderId: String, modification: OrderModification): SubmitAck
}

data class OrderModification(
    val newStopLoss: BigDecimal? = null,
    val newTakeProfit: BigDecimal? = null,
    val newTriggerPrice: BigDecimal? = null,  // for Stop/Limit/StopLimit
    val newSlDistance: Long? = null,  // for TrailingStop
)
```

MT5Client gains:

```kotlin
fun modifyOrder(ticket: Long, modification: MT5OrderModification): MT5OrderResponse {
    // POST /modify-order or PUT /order/{ticket} — verify gateway endpoint
}
```

If the gateway doesn't have a modify endpoint, document the gap and skip this sub-feature.

## Test plan

### Unit tests

- `MT5PositionPoller.tick()` detects opened positions and invokes the callback
- `MT5Broker.onPendingFilled` looks up the orderId by ticket, removes from both maps, publishes OrderFilled
- `MT5Broker.cancel(orderId)` removes from both maps even on exception in client.cancelOrder
- `MT5OrderTranslator.translateTrailingStop` PERCENT mode with priceTracker

### Integration tests (FakeMt5Client + MockWebServer)

- **Pending fill end-to-end**: place Stop → 200ms later mock server returns the position in /positions → assert OrderFilled emitted with correct clientOrderId
- **OCO sibling cancel**: place OCO with two stops → mock fills leg1 (returns in /positions) → assert OrderFilled for leg1 AND cancelOrder call for leg2's ticket
- **Cancel-then-poller-sees-empty**: user calls cancel → OrderCancelled emitted synchronously → on next poll, ticket is gone from /positions (and was never a position) → no duplicate event
- **External cancellation**: user cancels in MetaTrader → ticket disappears from venue but never appeared as position → currently undetected (document as Phase 26d / /orders endpoint requirement)
- **Modify order**: place Stop → call modify(orderId, new SL) → assert mock receives PUT to /modify-order

### End-to-end smoke (manual)

Add to `tests/smoke-install.sh`: deploy hedge-straddle against `FakeMt5Client` via `qkt daemon` with a fake-broker profile. Simulate a session open. Assert one leg fills and the other cancels. *Note:* this requires a paper broker mode that uses FakeMt5Client; may need new test fixture beyond Phase 26c. Document as Phase 26d if too invasive.

## Migration considerations

**Constructor change for `MT5PositionPoller`** — new optional `onPositionOpened` callback. Existing callers (just `MT5Broker`) need updating. The parameter has a default of `null` for backward-compat with any test fixtures that mock it.

**`Broker.modify` interface addition** — if the interface doesn't have this method today, adding it is a breaking change for any other Broker implementations (LogBroker, PaperBroker, BybitBroker, etc.). Default the implementations to a no-op or "not supported" rejection.

**No breaking changes for users.** Strategy code is unchanged. The hedge-straddle example becomes truly live-runnable.

## Acceptance criteria

- All new unit + integration tests pass.
- `tests/smoke-install.sh` continues green.
- Existing `MT5BrokerIntegrationTest` continues green (the pending-stop placement test now also asserts OrderFilled emission once the position appears).
- `docs/phases/phase-26c-pending-fill-lifecycle.md` exists with usage cookbook.
- `docs/planned.md` Phase 26c entry removed; gates updated for live capital.
- `examples/hedge-straddle/README.md` no longer says "until Phase 26c lands" — once 26c merges, the strategy is paper-tradable end-to-end on Exness.

## Open questions

1. **mt5-gateway `/orders` endpoint** — does it exist? If yes, we can add a dedicated pending poller and avoid TTL-based eviction for external cancellations. If no, this is a clean cut for Phase 26d.
2. **Comment field length on MT5** — verify the `oco:<id>/<leg-id>` tag stays within MT5's 31-char comment limit, or design a shorter scheme.
3. **Ticket continuity through fill** — does MT5 keep the same ticket when a pending order fills (the ticket becomes a position ticket), or does it assign a new position ticket? Affects whether `ticketToOrderMeta` lookup works. Assumed yes; verify in integration test.
4. **`Broker.modify` interface** — does it already exist? If not, what's the right shape? Look at how `OrderManager` currently handles modifications (does it?).

## References

- Phase 26b changelog: `docs/phases/phase-26b-mt5-pending-family.md`
- MT5 position poller: `src/main/kotlin/com/qkt/broker/mt5/MT5PositionPoller.kt`
- MT5 broker (Phase 26b): `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- OCO sibling map: `src/main/kotlin/com/qkt/app/OrderManager.kt:818`
- pa-quant production reference: `../fxquant/pa-quant/src/engine/straddle-engine.ts:1145`
