# Phase 26d — `/orders` endpoint, PERCENT trailing, order modification

> Closes the three remaining gaps Phase 26c intentionally narrowed out. Each sub-feature has its own mt5-gateway dependency — they ship piecewise as the gateway-side capabilities arrive, but live in one phase because they share the same audit and integration test surface.

## Goal

Three independent capabilities that together complete the MT5 broker:

1. **`/orders` endpoint integration** — detect GTD-expired pending orders and externally-cancelled pendings (user cancels in MetaTrader) so qkt's `pendingByTicket` map doesn't accumulate stale entries
2. **PERCENT mode trailing stops on MT5** — Phase 26b deferred this for lack of a current-price seed at translation time. Inject `MarketPriceTracker` into the translator
3. **Order modification surface** — `MT5Broker.modify(orderId, OrderModification)` implementation. Changes trigger prices, SL, TP on a working pending or position without cancel + re-submit

Each sub-feature is small (1-2 days of qkt-side work). The shared cost is mt5-gateway endpoint verification + integration test scaffolding. Combining them keeps the gateway round-trip count down and gives a single mergeable "MT5 is fully feature-complete" milestone.

## Motivation

After Phase 26c, three honest gaps remain on the MT5 broker surface:

### Gap 1: Stale pending tickets

`MT5Broker.pendingByTicket: Map<Long, PendingMeta>` populates on pending placement, clears on observed fill (Phase 26c) or explicit `cancel(orderId)` call. **But:** the venue can also remove a pending without qkt knowing — GTD expiry, external cancellation in MetaTrader, broker-side rejection. Today these leak.

In a running daemon, leaks accumulate at ~6/day for hedge-straddle (one stale entry per session-hour-attempt that doesn't fill). Not a memory issue at this rate, but the divergence between qkt's "I have these pending orders" view and the venue's actual state is a reconciliation risk. Operationally: if the daemon restarts, qkt sees an empty map; state recovery only reads /positions, not /orders, so stale awareness is lost. If qkt then submits a *new* pending order with a freshly-recycled MT5 ticket id, the old `pendingByTicket` entry collides — possible cause of cross-strategy fill misattribution.

**Fix:** mt5-gateway exposes `GET /orders?magic=X` returning pending orders. qkt adds a poller that detects pending tickets disappearing from the venue:
- If the ticket later appears as a position → already handled by `MT5PositionPoller` (Phase 26c)
- If the ticket simply vanishes from /orders → emit `OrderCancelled` with reason "external" or "gtd-expired"

### Gap 2: PERCENT trailing on MT5

`MT5OrderTranslator.translateTrailingStop` (Phase 26b) hard-fails on `TrailMode.PERCENT`:

```kotlin
require(req.trailMode == TrailMode.ABSOLUTE) {
    "MT5 trailing stop currently supports ABSOLUTE trailAmount only; got ${req.trailMode}. " +
        "PERCENT mode needs a current-price seed at submit time (deferred)."
}
```

PERCENT needs `currentPrice × frac` to resolve to an absolute distance, which needs the current price at submit time. The translator is a pure function with no state. Two options:

1. Pass `MarketPriceTracker` into the translator constructor (the engine already wires this in for everything else; just thread it through the broker → translator chain)
2. Resolve PERCENT distances earlier — in the OrderTypeCompiler or somewhere with engine context — so by the time the broker sees the request, it's already ABSOLUTE

Recommended: option 1. Keeps the translation logic local, doesn't push price-resolution upstream into the engine where it has nothing to do with order routing.

### Gap 3: Order modification

`Broker.modify(orderId, OrderModification)` interface exists with `UnsupportedOperationException` default. `MT5Broker.modify` doesn't override it. Operationally, this means:

- A strategy that wants to move its stop-loss has to cancel + re-submit (two wire calls + a brief window where the position is unprotected)
- A trailing-stop strategy can't dynamically adjust its trail distance based on signal quality

mt5-gateway side: a `POST /modify-order` (or `PUT /order/{ticket}`) endpoint. qkt-side: `MT5Client.modifyOrder(ticket, fields)` method and `MT5Broker.modify(orderId, changes)` impl.

The DSL surface for modify is **deferred to a future phase** — engine-level support is enough. Strategy authors who want modify can submit a `Signal.Modify(orderId, changes)` from Kotlin-DSL strategies; the text-DSL grammar gets the `MODIFY` action in a later phase.

## Scope

### In scope

**A. `/orders` poller** (~150 LOC + tests)

- `MT5Client.getPendingOrders(magic: Int?): List<MT5PendingOrder>` — new method
- `MT5PendingOrderPoller` class — similar shape to `MT5PositionPoller`, polls at `pollIntervalMs`, detects deltas
- `MT5Broker.pendingByTicket` integration — when a ticket leaves /orders without appearing in /positions, emit `OrderCancelled` with reason
- Wire types: `MT5PendingOrder` data class (ticket, symbol, type, volume, price, sl_distance, comment, time_setup, time_expiration)

**B. PERCENT trailing** (~80 LOC + tests)

- `MT5OrderTranslator` constructor gains `priceTracker: MarketPriceTracker? = null` parameter (default `null` keeps tests/mocks working)
- `translateTrailingStop` PERCENT branch:
  ```kotlin
  TrailMode.PERCENT -> {
      val tracker = priceTracker ?: error("PERCENT trailing requires priceTracker on MT5OrderTranslator")
      val price = tracker.lastPrice(req.symbol)
          ?: error("PERCENT trailing requires lastPrice for ${req.symbol}; ensure tick stream is active")
      val absDistance = price.multiply(req.trailAmount).divide(BigDecimal(100), MathContext.DECIMAL64)
      absDistance.divide(pointFor(req.symbol), MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP).toLong()
  }
  ```
- `MT5Broker` plumbs `priceTracker` from the `BrokerFactory` signature (already includes `MarketPriceTracker` as the third parameter) into the translator
- Translator unit test for PERCENT with a fake `MarketPriceTracker`
- Integration test: PERCENT trailing strategy → submit → wire request has correct `sl_distance` computed from current tracker price

**C. Order modification** (~120 LOC + tests)

- `MT5Client.modifyOrder(ticket: Long, modification: MT5OrderModification): MT5OrderResponse` — new method
- Wire types: `MT5OrderModification(stopLoss, takeProfit, price, slDistance)` — all nullable, only non-null fields apply
- `MT5Broker.modify(orderId: String, changes: OrderModification): SubmitAck`:
  - Look up `pendingByTicket[orderId]` or position-ticket-map (new — tracks positions opened via this broker)
  - Translate `changes` to `MT5OrderModification`
  - Call `client.modifyOrder(ticket, mt5Mods)`
  - On success, emit `BrokerEvent.OrderModified` (does this event exist? if not, add it)
- Test: place pending → modify trigger price → wire request asserted; place pending → modify SL → wire request asserted
- Capability: `OrderTypeCapability.MODIFY` already exists in the enum; add to MT5's declared set

### Out of scope

**DSL surface for `MODIFY` action.** Strategy authors who want runtime modification can drop to Kotlin-DSL and emit `Signal.Modify(orderId, changes)`. Adding `MODIFY <stream> SET SL AT <expr>` to the text DSL needs brainstorming around state-tracking (which orderId? track in LET? new accessor?) — separate phase.

**`/orders` endpoint for non-MT5 brokers.** Bybit has push WebSocket for pending order events — no polling needed there. This phase is MT5-specific.

**Bracket modification on existing positions.** Once a pending fills and becomes a position, the position has its own SL/TP. Modifying *that* SL/TP is a different MT5 API call (`PositionModify` vs `OrderModify`). For now, focus on pending-order modification; position modification is naturally a Phase 28 if it becomes a real need.

**State-recovery for /orders.** When the daemon restarts with pending orders still alive on the venue, the recovery flow (Phase 17, `MT5StateRecovery`) only re-reads /positions today. Re-reading /orders to repopulate `pendingByTicket` is logically Phase 26d work but doubles the test matrix. Recommend: include if time permits; defer to Phase 26e if not.

## Architecture

### `/orders` polling

```kotlin
class MT5PendingOrderPoller(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
    private val clock: Clock,
    private val onPendingDisappeared: ((Long) -> Unit)? = null,
) {
    private var lastSnapshot: Map<Long, MT5PendingOrder> = emptyMap()

    private fun tick() {
        val current = client.getPendingOrders(magic = profile.magic).associateBy { it.ticket }
        val disappeared = lastSnapshot.keys - current.keys
        for (ticket in disappeared) {
            onPendingDisappeared?.invoke(ticket)
        }
        lastSnapshot = current
    }
}
```

`MT5Broker` registers a callback. When a pending disappears, the broker checks:
1. Is the ticket in `pendingByTicket`? → it was ours
2. Has the position-poller seen a corresponding position open in the last poll cycle? → it filled, ignore (Phase 26c already handled it)
3. Otherwise → cancelled externally or GTD-expired. Remove from `pendingByTicket`, emit `OrderCancelled` with reason.

**Race condition:** the order-poller might tick *before* the position-poller has had a chance to detect the new position. The broker's "did this fill or was it cancelled" decision needs to handle this. Simplest approach: cache "recently-disappeared pending tickets" with a TTL of ~3× `pollIntervalMs`. If a position with the same ticket appears within the TTL, treat as fill (Phase 26c path); if the TTL expires first, treat as cancel.

### PERCENT trailing

```kotlin
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val priceTracker: MarketPriceTracker? = null,
) {
    private fun translateTrailingStop(req: OrderRequest.TrailingStop): MT5OrderRequest {
        val distance = when (req.trailMode) {
            TrailMode.ABSOLUTE -> absoluteToPoints(req.trailAmount, req.symbol)
            TrailMode.PERCENT -> {
                val tracker = priceTracker ?: error("PERCENT trailing requires priceTracker")
                val price = tracker.lastPrice(req.symbol) ?: error("no tracker price for ${req.symbol}")
                val abs = price.multiply(req.trailAmount).divide(BigDecimal(100), MathContext.DECIMAL64)
                absoluteToPoints(abs, req.symbol)
            }
        }
        return MT5OrderRequest(/* ... */, slDistance = distance)
    }
}
```

`MT5Broker` is constructed in `BrokerFactory` with the `MarketPriceTracker` already in scope — passing it through to the translator is a 1-line plumbing change.

### Order modification

```kotlin
override fun modify(orderId: String, changes: OrderModification): SubmitAck {
    val ticket = pendingTickets[orderId]
        ?: return reject(orderId, "modify: no pending order with id=$orderId")
    val mt5Mods = MT5OrderModification(
        stopLoss = changes.newStopPrice,           // OrderModification.newStopPrice → MT5 sl
        takeProfit = null,                          // not in OrderModification today
        price = changes.newLimitPrice ?: changes.newStopPrice,
        slDistance = null,                          // distinct from sl for trailing
    )
    val resp = client.modifyOrder(ticket, mt5Mods)
    return if (isOrderSuccessful(resp.result.retcode)) {
        bus.publish(BrokerEvent.OrderModified(/* ... */))
        SubmitAck(orderId, ticket.toString(), accepted = true)
    } else {
        reject(orderId, resp.errorMessage ?: "modify rejected: retcode=${resp.result.retcode}")
    }
}
```

Note: `OrderModification` doesn't have `newTakeProfit` today. If the modify surface needs it, extend `OrderModification` in this phase — small enough.

## Test plan

### `/orders` poller
- Unit: poller deltas detect "disappeared" pending tickets
- Integration: place pending → mock /orders empty on next poll → assert `OrderCancelled` with reason
- Race-condition test: place pending → mock /orders empty AND /positions shows the new position → assert single `OrderFilled` event, no spurious cancel

### PERCENT trailing
- Unit: `translateTrailingStop` with PERCENT and a fake `MarketPriceTracker` returns expected `slDistance` in points
- Integration: full strategy → submit → mock server receives wire request with the right `sl_distance`

### Order modification
- Unit: `MT5Broker.modify` looks up ticket from `pendingTickets`, calls `client.modifyOrder`
- Integration: submit → modify → mock server receives PUT/POST to modify endpoint with correct body
- Edge: modify with unknown orderId returns `SubmitAck(accepted=false)` without calling client

## Migration considerations

**`OrderModification` extension** — if we add `newTakeProfit`, existing implementations of `Broker.modify` (only `MT5Broker` actually overrides; `PaperBroker` and others use the default throw) get one more field they don't have to handle.

**`MT5OrderTranslator` constructor change** — adds `priceTracker: MarketPriceTracker? = null` parameter. Default null keeps Phase 26b/c translator tests working. Production `MT5Broker` passes the tracker; tests can omit.

**`MT5PendingOrderPoller` is a new lifecycle component** — same pattern as `MT5PositionPoller` (init, start, stop). Wires into `MT5Broker.init` and `MT5Broker.shutdown`.

**mt5-gateway dependency** — if `/orders` and `/modify-order` endpoints don't exist gateway-side, we ship the qkt-side code but the wire calls return 404. Document this clearly: features become available when gateway support lands.

## Acceptance criteria

- `MT5PendingOrderPoller` integration tests pass against `MockWebServer`
- `translateTrailingStop` PERCENT mode test passes
- `MT5Broker.modify` integration test passes
- Capability check at strategy compile time: a strategy using `OrderRequest.TrailingStop` with PERCENT on a broker whose `MarketPriceTracker` reference is null fails fast at submit time, not silently wrong
- `docs/phases/phase-26d-orders-percent-modify.md` exists with worked examples
- `docs/planned.md` Phase 26d entry removed
- mt5-gateway version compatibility documented in the changelog: minimum gateway version required for each sub-feature

## Open questions

1. **mt5-gateway endpoint availability.** Does the gateway expose `/orders` today? `/modify-order`? Verify before starting implementation — there's a non-zero chance gateway-side work is needed first. If gateway doesn't support modify natively (some MT5 gateway flavors don't expose it), we need to add the endpoint there.
2. **`BrokerEvent.OrderModified`** — does this event exist today? If not, add it. Shape: `(clientOrderId, brokerOrderId, fields: OrderModification, timestamp)`.
3. **Position modification.** Phase 26d scopes pending-order modification. If hedge-straddle's winner-side bracket trailing needs *position* modification too (and pa-quant likely does this), scope that into Phase 26d or split to Phase 26e?
4. **`/orders` poll interval vs position poll interval.** Should they share a thread? Run independently? Independent is simpler and matches Phase 17's model; shared has less overhead. Start with independent.

## References

- Phase 26c spec: `docs/superpowers/specs/2026-05-12-phase26c-pending-fill-lifecycle-design.md`
- Phase 26c changelog: `docs/phases/phase-26c-pending-fill-lifecycle.md`
- MT5 broker: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- MT5 client: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt`
- MT5 translator: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- mt5-gateway repo: https://github.com/elitekaycy/mt5-gateway (verify endpoint coverage)
