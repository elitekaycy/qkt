# Phase 7e — First Real Broker Integration (Bybit Spot) + Composite Broker

**Status:** Design draft.
**Predecessor:** Phase 7d (broker abstraction + OrderManager + composites).
**Successor:** Phase 7f (reconnect supervision, position reconciliation, account/equity polling).

---

## 1. Mission

Slot the first real broker — **Bybit Spot** — into Phase 7d's async `Broker` abstraction, and ship the long-deferred `CompositeBroker` so multiple brokers can be composed by symbol-pattern routing. After this phase, strategies can place real (testnet) orders against a live venue, and the composition pattern is in place for Phase 7f+ to add `BybitLinearBroker`, `AlpacaStocksBroker`, `OandaFxBroker`, etc. as leaf additions.

The architecture must be **trivially extensible**: adding a new exchange or product is a new `Broker` class plus one line in the `CompositeBroker` route list. Adding a new symbol within an existing broker requires zero code changes.

---

## 2. Goals

- One real broker integration end-to-end: Bybit Spot (testnet) supporting Market, Limit, Stop, StopLimit, IfTouched natively.
- `CompositeBroker` ships as a generic symbol-pattern router, parallel to `CompositeMarketSource`.
- Symbol naming convention `EXCHANGE_PRODUCT:SYMBOL` is locked in across the codebase. Existing TradingView convention `EXCHANGE:SYMBOL` (e.g., `OANDA:EURUSD`, `BINANCE:BTCUSDT`) continues to work for *data* (MarketSource); brokers use the longer `EXCHANGE_PRODUCT:SYMBOL` form to disambiguate spot/derivatives within one exchange.
- Per-product broker classes share a low-level `BybitClient` so `BybitLinearBroker`, `BybitInverseBroker`, etc. can be added later without re-implementing auth, REST, or WebSocket.
- `Broker.capabilitiesFor(symbol)` defaulted method extends the interface so `CompositeBroker` can report capabilities per-symbol.
- Authentication via API key + API secret, defaulting to **testnet** with explicit opt-in for live.
- API keys never appear in code or version-controlled config; sourced from environment variables.
- A manual `@Tag("e2e-live")` smoke test verifies real Bybit connectivity. Excluded from default `./gradlew test`.

## Non-goals

- **No reconnect supervision.** WS disconnects propagate to listeners as today; user manually restarts the JVM. Phase 7f.
- **No position reconciliation.** Engine `PositionTracker` remains canonical; Bybit's view is not consulted at submit-time. Phase 7f.
- **No account / equity / buying-power reporting.** `BybitClient` does not expose `/v5/account/wallet-balance` etc. Phase 7f.
- **No rate-limit enforcement.** HTTP 429 propagates as `OrderRejected`. Phase 7f.
- **No `BybitLinearBroker`** (USDT perpetuals) — derivatives need leverage, position-mode, funding-rate handling. Phase 7f or 7g.
- **No multi-account.** One `BybitClient` per account.
- **No smart-order-routing.** A symbol routes to exactly one broker via `CompositeBroker`. Cross-venue execution aggregation is not in scope.
- **No `OrderManager.modify(...)`** exposure to strategies (Bybit supports `/v5/order/amend` but the engine surface remains cancel + resubmit). Phase 7f or later.

---

## 3. Background — current state (Phase 7d, post-merge)

```kotlin
interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>
    fun submit(request: OrderRequest): SubmitAck
    fun cancel(orderId: String)
    fun modify(orderId: String, changes: OrderModification): SubmitAck
}
```

Two concrete brokers ship:
- `LogBroker` — logs only, never fills.
- `PaperBroker` — in-process simulation, fills via tick-driven matching.

`OrderManager` checks `OrderTypeCapability.X in broker.capabilities` to decide whether to hand off vs synthesize Tier 2 orders. This works for the current single-broker world but breaks under composition: a `CompositeBroker.capabilities` cannot meaningfully describe what's supported, because the answer depends on which sub-broker handles a given symbol.

Phase 7e closes that gap and adds the first real venue.

---

## 4. Architecture overview

### 4.1 Component map

```
                                    ┌─────────────────────────────────────┐
                                    │  CompositeBroker (NEW in 7e)        │
                                    │  routes per-symbol via patterns     │
                                    │  capabilitiesFor(symbol) delegates  │
                                    │  tracks orderId → Broker for cancel │
                                    └────┬───────────────────────────┬────┘
                                         │ BYBIT_SPOT:*               │ <fallback>
                                         ▼                            ▼
            ┌─────────────────────────────────────────────┐    ┌────────────────┐
            │  BybitSpotBroker         (NEW in 7e)        │    │ PaperBroker /  │
            │  • category="spot"                          │    │ LogBroker /    │
            │  • capabilities = MARKET LIMIT STOP         │    │ another vendor │
            │    STOP_LIMIT IF_TOUCHED MODIFY             │    └────────────────┘
            │  • supports(symbol) = BYBIT_SPOT:*          │
            │  • subscribes to private WS topics          │
            │    "order", "execution"                     │
            └────────┬────────────────────────────────────┘
                     │ uses
                     ▼
            ┌─────────────────────────────────────────────┐
            │  BybitClient             (NEW in 7e)        │
            │  • OkHttp REST + WebSocket                  │
            │  • HMAC-SHA256 signing                      │
            │  • testnet | live via constructor flag      │
            │    (env-driven; defaults testnet)           │
            │  • shared by ALL per-product brokers        │
            └─────────────────────────────────────────────┘
```

### 4.2 Why per-product brokers (not one big Bybit broker)

Bybit V5 unifies its REST API across spot/linear/inverse/option via a `category` parameter. Same auth, same envelope. Tempting to ship one `BybitBroker` that handles all categories.

The reason we split:
- **Capabilities differ.** Linear has `reduceOnly`, `closeOnTrigger`, `positionIdx`. Inverse has coin-margined PnL. Options has Greeks. Spot has none of those. A flat `BybitBroker` either advertises a union (lying to OrderManager) or branches internally on every method (a god class).
- **Symbol routing.** `CompositeBroker` routes by `Broker.supports(symbol)`. With per-product brokers, the routing is unambiguous. With one `BybitBroker`, you'd need a separate routing layer inside it — duplicating CompositeBroker.
- **Testability.** Each per-product broker has narrow responsibilities, smaller test surface.

Cost: small amount of glue per new product (~150 LOC each). Worth it.

---

## 5. Symbol naming convention

The convention `EXCHANGE_PRODUCT:SYMBOL` is the load-bearing thing for scalability. Lock it in 7e:

```
BYBIT_SPOT:BTCUSDT          (Bybit spot)
BYBIT_LINEAR:BTCUSDT        (Bybit USDT perpetual; Phase 7f+)
BYBIT_INVERSE:BTCUSD        (Bybit coin-margined perpetual; Phase 7g+)
BYBIT_OPTION:BTC-26DEC25-50000-C   (Bybit option; far future)
ALPACA_STOCKS:AAPL          (Alpaca equities; future)
ALPACA_CRYPTO:BTC/USD       (Alpaca crypto; future)
OANDA_FX:EUR_USD            (OANDA FX; future)
COINBASE_SPOT:BTC-USD       (Coinbase Advanced Trade spot; future)
IBKR_OPTIONS:AAPL241220C00150000  (IBKR option; future)
```

Notes:
- Uppercase exchange and product. Underscore separator within exchange-product. Colon separator before symbol.
- Symbol part follows the venue's native form (Bybit uses `BTCUSDT`, Coinbase uses `BTC-USD`, etc.). We don't translate — venues see the symbol as-is.
- TradingView's existing data-side convention `EXCHANGE:SYMBOL` (`OANDA:EURUSD`, `BINANCE:BTCUSDT`) **stays** — it's used by `MarketSource`, not by `Broker`. Strategies that consume both market data and broker actions will use both forms (TV form for `source.bars(...)`, broker form for `engine.submit(OrderRequest.Market("BYBIT_SPOT:BTCUSDT", ...))`).
- A future symbol-mapping layer (Phase 8 / DSL) may unify these. Out of scope here.

`Broker.supports(symbol)` for each per-product broker is a prefix match:

```kotlin
override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_SPOT:")
```

---

## 6. `Broker` interface refinement

Add one defaulted method to support `CompositeBroker`:

```kotlin
interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>

    // NEW: per-symbol capability query. Default returns the flat set.
    fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> = capabilities

    fun supports(symbol: String): Boolean = true   // most brokers handle anything; composite filters
    fun submit(request: OrderRequest): SubmitAck
    fun cancel(orderId: String)
    fun modify(orderId: String, changes: OrderModification): SubmitAck =
        throw UnsupportedOperationException("$name does not support modify")
}
```

`OrderManager.dispatch()` switches from `broker.capabilities` to `broker.capabilitiesFor(request.symbol)`. Existing `LogBroker`/`PaperBroker` get the default — no behavior change. `CompositeBroker` overrides to delegate to the routed sub-broker.

`Broker.supports(symbol)` is also added (defaulted to `true`). `CompositeBroker` uses it implicitly via pattern matching. Existing brokers don't need to override unless they want to constrain their symbol set (`BybitSpotBroker` does).

---

## 7. `CompositeBroker`

```kotlin
class CompositeBroker(
    private val routes: List<Pair<SymbolPattern, Broker>>,
    private val fallback: Broker? = null,
    private val bus: EventBus? = null,
) : Broker {
    override val name: String = "Composite"

    // capabilities is meaningless without a symbol; deprecated for composite.
    override val capabilities: Set<OrderTypeCapability>
        get() = error("Use capabilitiesFor(symbol) on CompositeBroker")

    override fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> =
        brokerFor(symbol)?.capabilitiesFor(symbol) ?: emptySet()

    override fun supports(symbol: String): Boolean = brokerFor(symbol) != null

    private val orderIdToBroker: MutableMap<String, Broker> = mutableMapOf()

    init {
        bus?.let { b ->
            // Prune the order-id map on terminal events to bound memory.
            b.subscribe<BrokerEvent.OrderFilled>     { e -> orderIdToBroker.remove(e.clientOrderId) }
            b.subscribe<BrokerEvent.OrderCancelled>  { e -> orderIdToBroker.remove(e.clientOrderId) }
            b.subscribe<BrokerEvent.OrderRejected>   { e -> orderIdToBroker.remove(e.clientOrderId) }
        }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        val target = brokerFor(request.symbol)
            ?: return SubmitAck(
                clientOrderId = request.id, brokerOrderId = null,
                accepted = false, rejectReason = "no broker for ${request.symbol}",
            )
        orderIdToBroker[request.id] = target
        return target.submit(request)
    }

    override fun cancel(orderId: String) {
        orderIdToBroker[orderId]?.cancel(orderId)
    }

    override fun modify(orderId: String, changes: OrderModification): SubmitAck {
        val target = orderIdToBroker[orderId]
            ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "unknown orderId $orderId")
        return target.modify(orderId, changes)
    }

    private fun brokerFor(symbol: String): Broker? =
        routes.firstOrNull { (pattern, _) -> pattern.matches(symbol) }?.second ?: fallback
}
```

`SymbolPattern` is the same type that backs `CompositeMarketSource` (`prefix`, `exact` factories). No new abstraction.

### 7.1 Routing semantics

- **List order = priority.** First matching pattern wins. If two patterns match the same symbol, only the first sub-broker sees it.
- **Fallback** handles unmatched symbols. Typical configs:
  - **Test**: fallback = `PaperBroker` (anything unrouted gets paper-traded).
  - **Production**: fallback = `null` (anything unrouted is rejected at `submit`).
- **Cancel routing** uses the `orderIdToBroker` map populated at `submit` time. Map entries are pruned on terminal `BrokerEvent`s to keep memory bounded.
- **Capabilities** for a symbol are exactly the routed sub-broker's capabilities. Unmatched symbols have `emptySet()` (effectively — the OrderManager falls back to engine synthesis or rejects depending on tier).

### 7.2 Adding a new broker — concrete steps

1. **Implement** `Broker` (typically extends an existing `XxxClient` for shared transport):
   ```kotlin
   class AlpacaStocksBroker(client: AlpacaClient, bus: EventBus, clock: Clock) : Broker {
       override val name = "AlpacaStocks"
       override val capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, ...)
       override fun supports(symbol) = symbol.startsWith("ALPACA_STOCKS:")
       // submit, cancel, modify implementations
   }
   ```

2. **Plug it in** at the wiring point (`LiveSession.start()` or `Backtest.run()` or user code):
   ```kotlin
   val composite = CompositeBroker(
       routes = listOf(
           SymbolPattern.prefix("BYBIT_SPOT:")    to bybitSpot,
           SymbolPattern.prefix("ALPACA_STOCKS:") to alpaca,    // ← new line
       ),
       fallback = paperBroker,
       bus = bus,
   )
   ```

That's the entirety of the API surface for adding a broker. No changes to `OrderManager`, `TradingPipeline`, strategies, or other brokers.

### 7.3 Adding a new symbol within an existing broker

**Zero code changes.** `BybitSpotBroker` matches the prefix; the symbol part is opaque data forwarded to Bybit's REST. `BYBIT_SPOT:DOGEUSDT` works the same as `BYBIT_SPOT:BTCUSDT`.

### 7.4 Why immutable construction-time routing (not a runtime registry)

We considered:
- (a) Construction-time list of routes (chosen).
- (b) Mutable `composite.register(pattern, broker)` at runtime.
- (c) Service-locator `BrokerRegistry` (global state).

(a) wins because:
- Predictable: the route list is visible in one place at session start.
- No race conditions: no broker is ever in a half-registered state.
- DSL-friendly: when Phase 8 DSL parses strategy declarations, it computes the route list at parse time and constructs `CompositeBroker` once.

If a real need for runtime registration emerges later, (b) can be added without breaking (a).

---

## 8. `BybitClient` — low-level shared transport

Single class, one instance per `(account, environment)` pair. Owns:
- OkHttp REST request/response (with HMAC signing)
- OkHttp WebSocket private connection (with on-connect auth and topic subscription dispatch)
- HMAC-SHA256 signing of REST + WS

### 8.1 Constructor

```kotlin
class BybitClient(
    apiKey: String? = null,             // env: BYBIT_API_KEY
    apiSecret: String? = null,          // env: BYBIT_API_SECRET
    testnet: Boolean? = null,           // env: BYBIT_TESTNET; default true if both null
    recvWindowMs: Long? = null,         // env: BYBIT_RECV_WINDOW_MS; default 5000
    httpClient: OkHttpClient = defaultHttpClient(),
    clock: Clock = SystemClock(),
)
```

Constructor parameters take precedence over env vars. Final values:
- `apiKey` / `apiSecret`: required; throws `IllegalStateException` at construction if neither constructor arg nor env var supplied.
- `testnet`: defaults to `true` if not specified anywhere — defense-in-depth so a forgotten flag never hits real money.
- `recvWindowMs`: 5000 ms default.

### 8.2 Endpoints (V5)

| Environment | REST host                  | Private WS                                       |
|-------------|----------------------------|--------------------------------------------------|
| Testnet     | `https://api-testnet.bybit.com`  | `wss://stream-testnet.bybit.com/v5/private` |
| Live        | `https://api.bybit.com`          | `wss://stream.bybit.com/v5/private`         |

### 8.3 REST signing

Bybit V5 REST signing (POST):
- Headers: `X-BAPI-API-KEY`, `X-BAPI-TIMESTAMP` (millis since epoch), `X-BAPI-SIGN`, `X-BAPI-RECV-WINDOW`.
- Pre-sign string: `timestamp + apiKey + recvWindow + jsonBody`.
- Sign: `HMAC-SHA256(apiSecret, preSignString).hex()`.

`BybitClient` exposes:
```kotlin
fun postSigned(path: String, body: String): String   // returns response body
fun getSigned(path: String, query: Map<String, String>): String
```

JSON parsing stays at the call site (each per-product broker parses what it needs). `BybitClient` is plumbing only.

### 8.4 WebSocket lifecycle

On `connect()`:
1. Open WS to private endpoint.
2. Send `{"op":"auth","args":[apiKey, expires, signature]}` where `signature = HMAC-SHA256(apiSecret, "GET/realtime${expires}").hex()` and `expires = clock.now() + 10_000`.
3. On auth success, allow listeners to subscribe via `subscribe(topic, listener)`.
4. Send `{"op":"subscribe","args":[<topic>]}` per registered listener.

`BybitClient` exposes:
```kotlin
fun connect()                                                             // opens WS, auths
fun close()
fun subscribe(topic: String, listener: (JsonObject) -> Unit)              // registers per-topic
```

Topic dispatch is fan-out: a single inbound WS frame is routed to all listeners registered for its topic field. Per-product brokers register `"order"` and `"execution"` listeners for their category.

### 8.5 Error handling

REST errors (Bybit returns `retCode != 0`) propagate as `BybitApiException(retCode, retMsg)`. Per-product brokers translate to `OrderRejected(reason = "$retCode: $retMsg")`.

WS disconnects fire OkHttp's `onFailure` / `onClosed`. `BybitClient` logs and notifies listeners via a `onDisconnect` callback. **Phase 7e does not auto-reconnect** — the user's strategy stops receiving fill events until the JVM is restarted. Documented as a known limitation.

---

## 9. `BybitSpotBroker` — concrete

### 9.1 Class

```kotlin
class BybitSpotBroker(
    private val client: BybitClient,
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    override val name: String = "BybitSpot"
    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )

    override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_SPOT:")

    init {
        client.subscribe("order")     { json -> onOrderUpdate(json) }
        client.subscribe("execution") { json -> onExecution(json) }
    }

    override fun submit(request: OrderRequest): SubmitAck { ... }
    override fun cancel(orderId: String) { ... }
    override fun modify(orderId: String, changes: OrderModification): SubmitAck { ... }
}
```

### 9.2 Submit path

Translation of `OrderRequest` → Bybit `/v5/order/create` body (always `category=spot`):

| OrderRequest type | Bybit `orderType` | Bybit `triggerPrice` | Bybit `triggerDirection` | Notes |
|---|---|---|---|---|
| `Market`          | `Market` | — | — | `qty` only |
| `Limit`           | `Limit`  | — | — | `price = limitPrice` |
| `Stop`            | `Market` | `stopPrice` | computed by side | conditional Market |
| `StopLimit`       | `Limit`  | `stopPrice` | computed by side | conditional Limit at `limitPrice` |
| `IfTouched`       | `Market` or `Limit` | `triggerPrice` | computed (opposite of Stop) | per `onTrigger` |

`triggerDirection`:
- BUY Stop / BUY StopLimit → `1` (rise above)
- SELL Stop / SELL StopLimit → `2` (fall below)
- BUY IfTouched → `2` (fall to trigger)
- SELL IfTouched → `1` (rise to trigger)

`orderLinkId = request.id` — this is our `clientOrderId`, threaded through to all subsequent events.
`timeInForce` mapping: GTC → `"GTC"`, IOC → `"IOC"`, FOK → `"FOK"`. DAY is not natively supported on Bybit Spot; we map to GTC and document.

REST returns `{retCode, retMsg, result: {orderId, orderLinkId}}`. We construct `SubmitAck`:
- `retCode == 0` → `accepted=true, brokerOrderId=result.orderId`.
- `retCode != 0` → publish `BrokerEvent.OrderRejected(reason="$retCode: $retMsg")` and return `accepted=false`.

The synchronous return path covers immediate validation rejection. The asynchronous fill flow goes through the WS.

### 9.3 Cancel path

POST `/v5/order/cancel` with `{category: "spot", symbol: <bare>, orderLinkId: <our id>}`.
On success: WS will emit an `order` update with `orderStatus=Cancelled`. We don't synthesize an event from the REST ack — wait for the WS confirmation. (If Bybit ack is success but WS never delivers — known limitation; phase 7f reconcile catches it.)

### 9.4 Modify path

POST `/v5/order/amend`. Bybit accepts changes to `qty`, `price`, `triggerPrice`. Returns `retCode == 0` on success. WS emits an `order` update with the new fields. Phase 7e plumbs the call but does not surface `modify` to strategies (they cancel + resubmit).

### 9.5 Symbol mapping (in/out)

- **Outgoing**: strip prefix. `BYBIT_SPOT:BTCUSDT` → REST `symbol=BTCUSDT`, `category=spot`.
- **Incoming**: re-prefix. WS event with `symbol="BTCUSDT"` → published `BrokerEvent.OrderFilled(symbol="BYBIT_SPOT:BTCUSDT", ...)`.

Engine consumers (`PositionTracker`, `PnLCalculator`) only see prefixed symbols. The bare-symbol form is purely a Bybit wire format.

### 9.6 WS event translation

| Bybit `order` `orderStatus` | `BrokerEvent` |
|---|---|
| `New`                       | `OrderAccepted` (`brokerOrderId=orderId`) |
| `PartiallyFilled`           | `OrderPartiallyFilled` |
| `Filled`                    | `OrderFilled` |
| `Cancelled`                 | `OrderCancelled` |
| `Rejected`                  | `OrderRejected` |
| `Triggered`                 | (informational; no event published — happens internally before fill for Stop/StopLimit) |

`OrderFilled` and `OrderPartiallyFilled` use the Bybit `execution` topic for accurate fill price/quantity per execution leg, with `orderLinkId` keying back to the original `clientOrderId`.

---

## 10. Authentication & config

### 10.1 Constructor flag + env override

`BybitClient` constructor accepts `apiKey`, `apiSecret`, `testnet`, `recvWindowMs`. If any is `null`, it falls back to environment variables:

```
BYBIT_API_KEY            (required if not constructor-supplied)
BYBIT_API_SECRET         (required if not constructor-supplied)
BYBIT_TESTNET            ("true"/"false"; default "true" if neither present)
BYBIT_RECV_WINDOW_MS     (default 5000)
```

### 10.2 Default-to-testnet semantics

`testnet` defaults to `true` when ambiguous. Live trading requires the explicit value `false`. Cases:
- Constructor `testnet = null`, env unset → testnet (safe default).
- Constructor `testnet = null`, env `BYBIT_TESTNET=false` → live.
- Constructor `testnet = false` → live regardless of env.
- Constructor `testnet = true` → testnet regardless of env.

Documented in the phase changelog: **never write live API keys in code; always use the env var path for production**.

### 10.3 No keys in the repo

CI / tests / examples never instantiate `BybitClient` with literal credentials. Tests use `FakeBybitClient`. The one e2e-live smoke test reads from env at runtime.

---

## 11. Order lifecycle through Bybit (worked example)

```
Strategy emits Signal.Submit(OrderRequest.Limit(id="c1", "BYBIT_SPOT:BTCUSDT", BUY, 0.01, limitPrice=80000))
  ↓
TradingPipeline → orderManager.submit(req)
  ↓
OrderManager.dispatch():
  • request is Limit (Tier 1) → submitToBroker
  • broker is CompositeBroker
  • broker.capabilitiesFor("BYBIT_SPOT:BTCUSDT") = bybitSpot.capabilities (LIMIT in set)
  ↓
CompositeBroker.submit(req) → routes to BybitSpotBroker
  • orderIdToBroker[c1] = bybitSpot
  ↓
BybitSpotBroker.submit:
  POST /v5/order/create
    {category: "spot", symbol: "BTCUSDT", side: "Buy", orderType: "Limit",
     qty: "0.01", price: "80000", orderLinkId: "c1", timeInForce: "GTC"}
  Response: {retCode: 0, result: {orderId: "abc-123", orderLinkId: "c1"}}
  ↓
SubmitAck(clientOrderId="c1", brokerOrderId="abc-123", accepted=true) returned
OrderManager: c1 state SUBMITTED → (waiting for WS Accepted)

[WS frame arrives shortly]
  {topic: "order", data: [{orderLinkId: "c1", orderId: "abc-123", orderStatus: "New", ...}]}
  ↓
BybitSpotBroker.onOrderUpdate translates:
  bus.publish(BrokerEvent.OrderAccepted(clientOrderId="c1", brokerOrderId="abc-123", ...))
  ↓
OrderManager.onAccepted: c1 state SUBMITTED → WORKING

[market drops to 80000; Bybit fills]
  WS frame: {topic: "execution", data: [{orderLinkId: "c1", execPrice: "79998", execQty: "0.01", ...}]}
  ↓
BybitSpotBroker.onExecution translates:
  bus.publish(BrokerEvent.OrderFilled(clientOrderId="c1", price=79998, quantity=0.01,
                                      symbol="BYBIT_SPOT:BTCUSDT", side=BUY))
  ↓
TradingPipeline.OrderFilled subscription: positions.applyFill(...), pnl.recompute(), TradeEvent published.
OrderManager.onFilled: c1 state WORKING → FILLED.
CompositeBroker (via bus subscription): orderIdToBroker.remove("c1").
```

---

## 12. WebSocket lifecycle

- One private WS connection per `BybitClient`.
- `BybitClient.connect()` is idempotent — calling twice is a no-op.
- Per-product brokers subscribe at construction (via `client.subscribe(topic, listener)`). Subscription is fan-out: multiple listeners can register for the same topic.
- `BybitClient.close()` triggers WS close and clears listeners.
- On disconnect (`onFailure` / `onClosed`), `BybitClient` notifies listeners via an `onDisconnect(reason: String)` callback. **Phase 7e takes no recovery action.** The strategy stops receiving fill events; user must restart.

---

## 13. Testing strategy

### 13.1 Unit tests with `FakeBybitClient`

`FakeBybitClient(bus, clock)` records every REST call (`postSigned`, `getSigned`) and exposes `emitWsFrame(topic, json)` to drive listeners. Used to test `BybitSpotBroker` end-to-end without network.

### 13.2 `CompositeBrokerTest`

Two `FakeBroker`s with different patterns; verify:
- `submit` routes to the right broker by symbol prefix.
- Unmatched symbol falls back to the fallback broker (or rejects if `fallback = null`).
- `cancel(orderId)` reaches the broker that originally accepted the order.
- `capabilitiesFor(symbol)` delegates correctly.
- `orderIdToBroker` map prunes on terminal events.
- First-match-wins ordering.

### 13.3 OrderManager dispatch with composite

Augment existing `OrderManagerTier2FallbackTest` to verify `OrderManager` calls `capabilitiesFor(symbol)` not `capabilities`. (One added test asserts that a composite where one sub-broker has STOP and another doesn't routes a Stop order correctly per symbol.)

### 13.4 e2e-live smoke

`BybitSpotLiveSmokeTest`, `@Tag("e2e-live")`. Reads `BYBIT_API_KEY` / `BYBIT_API_SECRET` from env (testnet credentials). Submits a `Limit BUY BTCUSDT @ 1` (far below market), asserts `OrderAccepted`, then `cancel`s and asserts `OrderCancelled`. Excluded from default `./gradlew test`. Run on demand:
```
./gradlew test -PincludeTags=e2e-live --tests "com.qkt.broker.bybit.BybitSpotLiveSmokeTest"
```

If credentials aren't set, the test is skipped via `Assumptions.assumeTrue(...)` (still excluded from default; the assume-skip just makes the smoke runnable without erroring on missing keys).

---

## 14. Migration plan

Affected production files:

```
src/main/kotlin/com/qkt/broker/Broker.kt              # add capabilitiesFor + supports defaults
src/main/kotlin/com/qkt/broker/CompositeBroker.kt     # NEW
src/main/kotlin/com/qkt/broker/bybit/BybitClient.kt   # NEW
src/main/kotlin/com/qkt/broker/bybit/BybitSpotBroker.kt   # NEW
src/main/kotlin/com/qkt/broker/bybit/BybitSigner.kt   # NEW
src/main/kotlin/com/qkt/broker/bybit/BybitSymbol.kt   # NEW
src/main/kotlin/com/qkt/broker/bybit/BybitOrderTranslator.kt   # NEW
src/main/kotlin/com/qkt/app/OrderManager.kt           # use capabilitiesFor(symbol)
src/main/kotlin/com/qkt/broker/PaperBroker.kt         # add supports() override (limit to "paper:" or accept all — design choice)
src/main/kotlin/com/qkt/broker/LogBroker.kt           # similar
```

Affected test files:

```
src/test/kotlin/com/qkt/broker/CompositeBrokerTest.kt         # NEW
src/test/kotlin/com/qkt/broker/bybit/FakeBybitClient.kt        # NEW
src/test/kotlin/com/qkt/broker/bybit/BybitSpotBrokerTest.kt    # NEW
src/test/kotlin/com/qkt/broker/bybit/BybitSpotLiveSmokeTest.kt # NEW
src/test/kotlin/com/qkt/app/OrderManager*Test.kt               # minor — no signature changes
```

Estimated scope: ~1000 LOC production new, ~700 LOC tests. About 1.3× Phase 7d-b.

`PaperBroker.supports()` and `LogBroker.supports()` keep the default (`true`) — they accept any symbol. Tests and audits often want a broker that takes anything.

`CompositeBroker` does NOT consult `Broker.supports(symbol)` for routing; it uses the explicit `routes: List<Pair<SymbolPattern, Broker>>` only. `supports()` remains informational on individual brokers. If a routing pattern sends a symbol to a broker that does not support it, that's a wiring bug — the broker's `submit` will reject and surface as `OrderRejected`.

---

## 15. Acceptance criteria

- [ ] `Broker.capabilitiesFor(symbol)` ships with default delegating to `capabilities`.
- [ ] `OrderManager.dispatch()` uses `capabilitiesFor(request.symbol)`.
- [ ] `CompositeBroker` ships with pattern routing, fallback, cancel routing, capability delegation, terminal-event map pruning.
- [ ] `BybitClient` ships with HMAC-SHA256 signing, REST + private WS, env-var-driven config, default-to-testnet.
- [ ] `BybitSpotBroker` ships supporting `Market`, `Limit`, `Stop`, `StopLimit`, `IfTouched`, `Modify`. WS event translation publishes correct `BrokerEvent`s. Symbol prefix translation correct.
- [ ] `FakeBybitClient` ships and is the canonical test fixture for Bybit-broker tests.
- [ ] `CompositeBrokerTest` covers routing, cancel, capabilitiesFor, fallback, ordering, pruning.
- [ ] `BybitSpotBrokerTest` covers each `OrderRequest` translation, WS event handling, error path.
- [ ] `BybitSpotLiveSmokeTest` exists at `@Tag("e2e-live")`.
- [ ] All Phase 7d tests still pass with the `capabilitiesFor` switch.
- [ ] `./gradlew run` still produces 10 FILLED+REJECTED (Phase 7 demo invariant preserved).
- [ ] Phase 7e changelog at `docs/phases/phase-7e-bybit-and-composite.md`.

---

## 16. Out of scope (deferred)

Carried forward from §2; itemized for the changelog:

- Reconnect supervision (BybitClient WS).
- Position reconciliation against Bybit's view.
- Account / equity / buying-power reporting.
- Margin / leverage / borrow/funding handling.
- Rate-limit enforcement and back-off.
- `BybitLinearBroker` (USDT perpetuals).
- `BybitInverseBroker`, `BybitOptionBroker`.
- `OrderManager.modify()` exposed to strategies.
- DAY time-in-force on Bybit Spot (Bybit doesn't natively; mapped to GTC).
- Multi-account.
- Smart-order-routing / cross-venue execution aggregation.
- Mutable runtime broker registration (`composite.register(...)` after construction).
- Symbol mapping abstraction (translating between TradingView form `BINANCE:BTCUSDT` and Bybit form `BYBIT_SPOT:BTCUSDT`).

---

## 17. Open questions / spec ambiguities

These are flagged for the implementation plan to resolve.

1. **Empty initial WS subscription.** If `BybitSpotBroker` is constructed but `BybitClient.connect()` hasn't been called yet, listener registration must queue. Plan documents the explicit ordering: construct client → construct brokers (registers listeners) → call `client.connect()` (auths + sends subscribe ops in registered order).

2. **Time skew vs `recvWindowMs`.** Bybit rejects requests where `abs(serverTime - clientTimestamp) > recvWindow`. We use `clock.now()`, which is `SystemClock` in production (wall clock; well-synced). In tests with `FixedClock`, signing still works — Bybit doesn't enforce this on testnet for old timestamps. Not a blocker.

3. **Spot order minimums.** Bybit Spot has minimum order sizes per symbol (e.g., 0.0001 BTC for BTCUSDT). 7e does not validate client-side — Bybit's REST returns `retCode != 0`, which we surface as `OrderRejected`. Document.

4. **Decimal precision.** `OrderRequest.quantity` is `BigDecimal` with our `Money.SCALE = 8`. Bybit truncates to per-symbol `qtyStep` and `tickSize`. We send the un-rounded value; Bybit rejects if precision exceeds limits. 7f could fetch instrument info and round client-side.

5. **`orderLinkId` length / character limits.** Bybit max 36 chars. Our `IdGenerator.next()` produces short numeric ids — no issue. Document for future random-id schemes.

6. **WS heartbeat.** Bybit V5 expects `{"op":"ping"}` every ~20s; the server replies with `pong`. We add a scheduler in `BybitClient` for periodic pings to keep the WS alive. Failure to ping → server-side disconnect after 30s. Without recover-on-reconnect (Phase 7f), losing the WS means losing fill events.

7. **`capabilities` get() error on CompositeBroker.** Calling `composite.capabilities` throws; only `capabilitiesFor(symbol)` is safe. This is intentional but breaks Java-style introspection. If a broader consumer (logging?) reads `capabilities`, change to return an empty set or union. For 7e: error is acceptable; document.

8. **`OrderTypeCapability.MODIFY` advertisement.** `BybitSpotBroker` advertises MODIFY, but `OrderManager` doesn't expose modify to strategies in 7e. The capability is correct (the broker DOES support modify); it's just not used. Plan handles this gracefully.

9. **`SymbolPattern` location.** Currently lives in `com.qkt.marketdata.source` (used by `CompositeMarketSource`). Phase 7e's `CompositeBroker` reuses it. Move to `com.qkt.common`? Plan decides; default keep in `marketdata.source` to avoid disturbing Phase 7 consumers.

10. **Bybit API version drift.** V5 is current; V3 was retired. Lock the V5 endpoint paths in `BybitClient` constants. If Bybit announces V6 deprecating V5, we'll address in a separate phase.

---

## References

- Phase 7d spec: [`2026-05-06-trading-engine-phase7d-design.md`](./2026-05-06-trading-engine-phase7d-design.md)
- Phase 7d changelog: [`../phases/phase-7d-broker-and-orders.md`](../phases/phase-7d-broker-and-orders.md)
- Bybit V5 API documentation (external): https://bybit-exchange.github.io/docs/v5/intro
- qkt skill (conventions): [`.claude/skills/qkt/SKILL.md`](../../.claude/skills/qkt/SKILL.md)
