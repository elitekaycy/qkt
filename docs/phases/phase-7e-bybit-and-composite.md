# Phase 7e — Bybit Spot + CompositeBroker

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md)

## Summary

Phase 7e ships the first real broker integration — Bybit Spot via V5 REST + private WebSocket — and the long-deferred `CompositeBroker` so multiple brokers can be composed by symbol-pattern routing. Strategies can now place real (testnet) orders against a live venue. The composition pattern is in place for Phase 7f+ to add `BybitLinearBroker`, `AlpacaStocksBroker`, `OandaFxBroker`, etc. as leaf additions.

## What's new

- `com.qkt.broker.CompositeBroker` — pattern-based broker router. Parallel to `CompositeMarketSource`.
- `com.qkt.broker.bybit.BybitClient` — shared low-level transport (HMAC-signed REST + private WebSocket). Used by all per-product Bybit brokers (only `BybitSpotBroker` ships in 7e; `BybitLinearBroker` etc. follow later).
- `com.qkt.broker.bybit.BybitSpotBroker` — Bybit Spot broker. Native support for Market, Limit, Stop, StopLimit, IfTouched, Modify.
- `com.qkt.broker.bybit.BybitSigner` — HMAC-SHA256 helper.
- `com.qkt.broker.bybit.BybitSymbol` — prefix parser (`BYBIT_SPOT:BTCUSDT` ↔ `(spot, BTCUSDT)`).
- `com.qkt.broker.bybit.BybitOrderTranslator` — pure functions translating `OrderRequest` to/from Bybit V5 fields.
- `com.qkt.broker.bybit.BybitTransport` — interface extracted for testability (real `BybitClient` and test `FakeBybitClient` both implement).
- `Broker.capabilitiesFor(symbol)` — defaulted method on the interface, used by `OrderManager.dispatch()` and overridden by `CompositeBroker`.
- `Broker.supports(symbol)` — defaulted method (informational; routing is via explicit `routes` patterns).
- Symbol convention `EXCHANGE_PRODUCT:SYMBOL` locked in for brokers (`BYBIT_SPOT:BTCUSDT`, `BYBIT_LINEAR:BTCUSDT`, `ALPACA_STOCKS:AAPL`, etc.).
- `e2e-live` JUnit tag for manual real-broker smoke tests; excluded from default `./gradlew test` (alongside the existing `e2e` tag).

## Migration from previous phase

| Phase 7d call | Phase 7e equivalent | Notes |
|---|---|---|
| `broker.capabilities` (in OrderManager.dispatch) | `broker.capabilitiesFor(request.symbol)` | Default impl returns the flat set; behavior identical for non-composite brokers. |

No other breaking changes. Existing strategies, tests, and entry points compile unchanged.

## Usage cookbook

### 1. Construct a Bybit testnet broker

```kotlin
// API key/secret read from BYBIT_API_KEY / BYBIT_API_SECRET env vars; testnet defaults to true
val client = BybitClient()
client.connect()
val bybitSpot = BybitSpotBroker(client, bus, SystemClock())
```

### 2. Live trading explicit opt-in

```kotlin
val client = BybitClient(testnet = false)   // requires explicit false
client.connect()
```

Or via env: `export BYBIT_TESTNET=false`. **Never write live API keys in code.**

### 3. Composing multiple brokers

```kotlin
val composite = CompositeBroker(
    routes = listOf(
        SymbolPattern.prefix("BYBIT_SPOT:") to bybitSpot,
        SymbolPattern.prefix("PAPER:")     to paperBroker,
    ),
    fallback = logBroker,
    bus = bus,
)
```

### 4. Strategy submitting to Bybit Spot

```kotlin
emit(Signal.Submit(
    OrderRequest.Limit(
        id = ids.next(),
        symbol = "BYBIT_SPOT:BTCUSDT",
        side = Side.BUY,
        quantity = Money.of("0.001"),
        limitPrice = Money.of("80000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))
```

`OrderManager` checks `composite.capabilitiesFor("BYBIT_SPOT:BTCUSDT")` (which delegates to `BybitSpotBroker.capabilities`), sees `LIMIT`, hands off. Bybit accepts; WS reports `New`; `BrokerEvent.OrderAccepted` lands on the bus.

### 5. Stop loss on Bybit Spot

```kotlin
emit(Signal.Submit(
    OrderRequest.Stop(
        id = ids.next(),
        symbol = "BYBIT_SPOT:BTCUSDT",
        side = Side.SELL,
        quantity = Money.of("0.001"),
        stopPrice = Money.of("75000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))
```

`BybitSpotBroker` advertises `STOP` natively. The order is sent to Bybit with `triggerPrice=75000` and `triggerDirection=2` (sell on fall). When BTC hits $75k, Bybit's server triggers a Market sell. WS `execution` topic delivers the fill; bus publishes `OrderFilled`.

### 6. Bracket on Bybit Spot (engine fallback)

`BybitSpotBroker` does NOT advertise `BRACKET`. OrderManager decomposes:

```kotlin
emit(Signal.Submit(
    OrderRequest.Bracket(
        id = ids.next(),
        symbol = "BYBIT_SPOT:BTCUSDT",
        side = Side.BUY,
        quantity = Money.of("0.001"),
        entry = OrderRequest.Limit(
            id = "${ids.next()}-e",
            symbol = "BYBIT_SPOT:BTCUSDT",
            side = Side.BUY,
            quantity = Money.of("0.001"),
            limitPrice = Money.of("80000"),
            timeInForce = TimeInForce.GTC,
            timestamp = clock.now(),
        ),
        takeProfit = Money.of("82000"),
        stopLoss = Money.of("78000"),
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
    ),
))
```

OrderManager decomposes to `OTO(entry, [OCO(Limit at 82000, Stop at 78000)])`. Entry posts to Bybit. On entry fill, OCO children activate: TP Limit posts to Bybit, SL Stop posts to Bybit. Whichever fills first cancels the other.

### 7. Adding a new broker product

```kotlin
class BybitLinearBroker(client: BybitClient, bus: EventBus, clock: Clock) : Broker {
    override val name = "BybitLinear"
    override val capabilities = setOf(
        OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT,
        OrderTypeCapability.STOP, OrderTypeCapability.STOP_LIMIT,
        OrderTypeCapability.IF_TOUCHED, OrderTypeCapability.MODIFY,
    )
    override fun supports(symbol: String) = symbol.startsWith("BYBIT_LINEAR:")
    // submit/cancel/modify use category="linear" via shared client
}

// Add one line to the composite:
val composite = CompositeBroker(
    routes = listOf(
        SymbolPattern.prefix("BYBIT_SPOT:")   to bybitSpot,
        SymbolPattern.prefix("BYBIT_LINEAR:") to bybitLinear,    // new
    ),
    fallback = paperBroker,
    bus = bus,
)
```

That's it — no changes to `OrderManager`, `TradingPipeline`, strategies, or other brokers.

### 8. Testing with `FakeBybitClient`

```kotlin
val client = FakeBybitClient()
client.responses["/v5/order/create"] = """{"retCode":0,"result":{"orderId":"abc","orderLinkId":"c1"}}"""
val broker = BybitSpotBroker(client, bus, FixedClock(0L))
val ack = broker.submit(...)
assertThat(client.posts.single().path).isEqualTo("/v5/order/create")

// Drive a fill via WS:
client.emitWsFrame("execution", JsonObject(...))
```

### 9. Running the e2e-live smoke

```bash
export BYBIT_API_KEY=your-testnet-key
export BYBIT_API_SECRET=your-testnet-secret
./gradlew test -PincludeTags=e2e-live --tests "com.qkt.broker.bybit.BybitSpotLiveSmokeTest"
```

The smoke submits a far-from-market Limit BUY (BTCUSDT @ $1), expects `OrderAccepted`, then cancels. Skipped via JUnit `assumeTrue` if credentials are not set.

## Testing patterns

- `FakeBybitClient` for unit tests — programmable REST responses, programmable WS frames.
- `CompositeBroker` tests use `FakeBroker` × N with different patterns (recall `FakeBroker` from Phase 7d-b).
- `BybitSpotLiveSmokeTest` (`@Tag("e2e-live")`) — hits real testnet; runs only via `-PincludeTags=e2e-live`.

## Known limitations

- **No reconnect supervision.** On WS disconnect, fill events stop arriving until JVM restart. Phase 7f.
- **No position reconciliation against Bybit's view.** `PositionTracker` remains canonical via `OrderFilled` events.
- **No account / equity / buying-power reporting.** Phase 7f.
- **No rate-limit enforcement.** HTTP 429 from Bybit propagates as `OrderRejected`.
- **`BybitLinearBroker` (USDT perpetuals) not shipped.** The architecture supports adding it as a leaf.
- **DAY time-in-force on Bybit Spot maps to GTC** (Bybit Spot doesn't natively support DAY).
- **`OrderManager.modify()` not exposed to strategies** — cancel + resubmit only. The broker-level `modify` is plumbed but unused by the engine.
- **Decimal precision.** Orders sent at our `Money.SCALE = 8`; Bybit rejects if precision exceeds per-symbol `qtyStep` / `tickSize`. No client-side rounding.
- **`CompositeBroker.capabilities` (flat) throws** — only `capabilitiesFor(symbol)` is safe.
- **`BybitSpotBroker.symbolByClientOrderId` is unbounded.** Map grows over the broker's lifetime; pruning on terminal events is deferred to Phase 7f.
- **No multi-account.** One `BybitClient` per Bybit account.

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase7e-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase7e.md`](../superpowers/plans/2026-05-06-trading-engine-phase7e.md)
- Bybit V5 API: https://bybit-exchange.github.io/docs/v5/intro
- Phase 7d baseline: [`phase-7d-broker-and-orders.md`](phase-7d-broker-and-orders.md)
