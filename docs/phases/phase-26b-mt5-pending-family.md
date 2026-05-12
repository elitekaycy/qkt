# Phase 26b — MT5 native pending family + OCO + trailing

## Summary

Phase 26b closes the broker gap between the qkt DSL and live MT5. Before this phase, `MT5OrderTranslator` translated only `Market` and `Bracket` orders; every other shape — `Stop`, `Limit`, `StopLimit`, `StandaloneOCO`, `TrailingStop` — was rejected with `error("MT5 v1 does not natively translate ...")`. After this phase, all five pending shapes route natively to MT5 as `BUY_STOP` / `SELL_STOP` / `BUY_LIMIT` / `SELL_LIMIT` / `BUY_STOP_LIMIT` / `SELL_STOP_LIMIT` / market-with-server-side-trail wire requests.

OCO groups submit both legs to MT5 with a shared `oco:<id>` comment tag so the broker layer can correlate sibling tickets without relying on a server-side group concept (MT5 has none — each ticket is independent).

**What's not in this phase:** end-to-end pending-order fill-event lifecycle. MT5 does not push notifications when a pending order fills; qkt has to poll. The existing position poller (Phase 17) detects fills eventually when a new position appears on the venue, but a dedicated pending-order poller — closing the latency gap, propagating cancels through the OrderManager's `siblings[]` map promptly, and handling explicit pending-order modifications — is Phase 26c.

## What's new

### Translation layer

- **`MT5Translation` sealed type** — `Single(MT5OrderRequest)` for atomic shapes, `Composite(requests, groupId)` for OCO. Translator return type changed from `MT5OrderRequest` to `MT5Translation`.
- **`translateStop`** — `BUY_STOP` / `SELL_STOP` with `price = stopPrice`
- **`translateLimit`** — `BUY_LIMIT` / `SELL_LIMIT` with `price = limitPrice`
- **`translateStopLimit`** — `BUY_STOP_LIMIT` / `SELL_STOP_LIMIT` with `price = stopPrice` and new `stoplimit = limitPrice` field
- **`translateTrailingStop`** — market entry with new `sl_distance` field; ABSOLUTE mode converts price-units to MT5 points via `MT5BrokerProfile.instrumentOverrides[symbol].pointSize`. PERCENT mode is rejected with a clear error until Phase 26c provides a current-price seed at submit time.
- **`translateStandaloneOCO`** — translates each leg recursively, tags both legs' `comment` field with `oco:<id>/...`, returns `Composite(requests, groupId)`.

### Wire types

- **`MT5OrderRequest.stopLimit`** (new field) — limit price for `*_STOP_LIMIT` orders, serialized as `"stoplimit"` in the gateway payload
- **`MT5OrderRequest.slDistance`** (new field) — trailing distance in MT5 points, serialized as `"sl_distance"`

### Broker capability

- **`OrderTypeCapability.OCO`** (new enum value) — declares pending-pair one-cancels-other support
- **`OrderTypeCapability.TRAILING_STOP`** (new) — declares server-side trailing-stop support
- **`MT5Protocol.capabilities`** now declares all seven shapes: `MARKET`, `BRACKET`, `STOP`, `LIMIT`, `STOP_LIMIT`, `OCO`, `TRAILING_STOP`

### Broker submission

- `MT5Broker.submit` switches on `MT5Translation.Single` vs `Composite`
- Pending placements publish `OrderAccepted` but do **not** synthesize `OrderFilled` — the fill comes later via the position poller (Phase 26c will dedicate a pending-order poller)
- `MT5Broker.cancel(orderId)` now wires through to `client.cancelOrder(ticket)` using the broker's `pendingTickets` map (populated on placement) — was a no-op before

## Migration from previous phase

Almost entirely additive. Two test surface changes:

| Before (Phase 26a) | After (Phase 26b) |
| --- | --- |
| `translator.translate(req): MT5OrderRequest` | `translator.translate(req): MT5Translation` — call sites unwrap `Single` or handle `Composite` |
| `MT5Broker.submit(Limit)` returned `accepted = false` with "not natively" reason | `MT5Broker.submit(Limit)` translates + places successfully |

`MT5BrokerIntegrationTest` updated to reflect the new behavior: a test that asserted Limit rejection is replaced with a test that asserts pending Stop placement succeeds with `OrderAccepted` but defers `OrderFilled`. The `IfTouched` shape (still without DSL surface and translator) keeps the rejection contract.

No existing user strategies are affected. No existing migrations break.

## Usage cookbook

### Submit a pending stop on MT5

```kotlin
val req = OrderRequest.Stop(
    id = "stop-eur-up",
    symbol = "EURUSD",
    side = Side.BUY,
    quantity = BigDecimal("0.1"),
    stopPrice = BigDecimal("1.1050"),
    timeInForce = TimeInForce.GTC,
    timestamp = clock.now(),
)
val ack = mt5Broker.submit(req)
// ack.accepted = true; wire payload has type=BUY_STOP, price=1.1050
// OrderAccepted event published; OrderFilled arrives later via position poller
```

### Submit pending OCO via DSL (Phase 26a + 26b)

```qkt
WHEN NOW.hour_utc = 14
 AND POSITION.gold = 0
THEN OCO_ENTRY {
    BUY  gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 },
    SELL gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close - 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
}
```

Compiles to `Signal.Submit(StandaloneOCO)`. Routes through `OrderManager.submitOco` (Phase 26a) which dispatches each leg via `MT5Broker.submit` (Phase 26b) which translates each as a native MT5 `BUY_STOP` / `SELL_STOP` and tags both with the qkt OCO group id in the wire `comment` field.

### Server-side trailing stop on MT5

```kotlin
val req = OrderRequest.TrailingStop(
    id = "trail-1",
    symbol = "EURUSD",
    side = Side.BUY,
    quantity = BigDecimal("0.1"),
    trailAmount = BigDecimal("0.00200"),  // 200 points at 0.00001 point size
    trailMode = TrailMode.ABSOLUTE,
    timeInForce = TimeInForce.GTC,
    timestamp = clock.now(),
)
mt5Broker.submit(req)
// Wire request: type=BUY, sl_distance=200. MT5 manages the trail server-side.
```

The broker profile must declare `instrumentOverrides[symbol].pointSize` so the translator can convert qkt-side price units to MT5 points. Trailing without an override fails with a clear actionable error.

### Configure a broker that doesn't support OCO

```kotlin
// In a broker profile YAML or programmatic config
MT5BrokerProfile.exness.copy(
    capabilityRestrictions = setOf(OrderTypeCapability.OCO),
)
```

Strategies that try `OCO_ENTRY` against this profile get rejected at the capability check.

## Testing patterns

### Translation tests

```kotlin
val req = OrderRequest.Stop(id = "s-1", symbol = "EURUSD", side = Side.BUY, ...)
val out = (translator.translate(req) as MT5Translation.Single).request
assertThat(out.type).isEqualTo("BUY_STOP")
assertThat(out.price).isEqualByComparingTo("1.1050")
```

### OCO Composite assertion

```kotlin
val oco = OrderRequest.StandaloneOCO(id = "oco-1", leg1 = stopBuy, leg2 = stopSell, ...)
val out = translator.translate(oco) as MT5Translation.Composite
assertThat(out.groupId).isEqualTo("oco-1")
assertThat(out.requests).hasSize(2)
assertThat(out.requests[0].comment).startsWith("oco:oco-1/")
```

### Broker submit + accept-only fill semantics

```kotlin
// Server returns successful placement
server.enqueue(MockResponse().setBody(
    """{"result":{"retcode":10009,"order":42,"deal":0,"price":"1.1050","comment":"ok"}}"""
))
val ack = broker.submit(stopReq)
assertThat(ack.accepted).isTrue
assertThat(captured).hasSize(1)
assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
// No OrderFilled — that comes via the position poller in Phase 26c
```

## Known limitations

- **No dedicated pending-order poller (Phase 26c).** Fills for pending shapes are detected by the existing `MT5PositionPoller` when a new position appears on the venue. This is correct but may lag — fills should arrive within `pollIntervalMs` (default 1000ms) of the venue's fill event. Cancel-on-fill across OCO siblings has the same latency.
- **PERCENT trailing not supported on MT5.** `OrderRequest.TrailingStop` with `trailMode = TrailMode.PERCENT` fails at translation time with a clear error. The translator doesn't have access to a current-price seed; supplying one is Phase 26c work.
- **`IfTouched` / `OTO` / `TrailingStopLimit` still unsupported on MT5.** These shapes also lack DSL surface, so there's no point routing them through MT5 yet. Future phase adds DSL + translation together.
- **No order-modification surface.** Pending orders today can be cancelled but not modified (move the SL, change the trigger). MT5 supports this via `OrderModify`; qkt doesn't expose it from the DSL. Future phase.
- **OCO group is encoded in `comment` field.** MT5 has no native group concept. The qkt-side `oco:<id>/...` prefix in the comment field is parsed by Phase 26c's pending poller. If a user manually edits the comment in MetaTrader, OCO correlation breaks — defensive programming should consider this case in Phase 26c.

## References

- Spec: `docs/superpowers/specs/2026-05-12-phase26b-mt5-pending-family-design.md`
- Plan: `docs/superpowers/plans/2026-05-12-phase26b-mt5-pending-family.md`
- Translator: `src/main/kotlin/com/qkt/broker/mt5/MT5OrderTranslator.kt`
- Wire types: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt`
- Broker: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Capability: `src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt`, `MT5Protocol.kt`
- Unit tests: `src/test/kotlin/com/qkt/broker/mt5/MT5OrderTranslatorTest.kt`
- Integration tests: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`
- Phase 26a (the DSL surface this phase routes to live): `docs/phases/phase-26a-pending-oco-and-clock.md`
- Phase 26c (the lifecycle gap this phase doesn't close): `docs/planned.md`
