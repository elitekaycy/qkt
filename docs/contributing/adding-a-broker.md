# Adding a broker

This guide walks through implementing a new broker integration end-to-end. The two reference implementations in the codebase are `com.qkt.broker.mt5` (MetaTrader 5 via HTTP gateway, poll-based fill detection) and `com.qkt.broker.bybit` (Bybit REST + WebSocket, push-based fills). Read this guide alongside one of those — the patterns are intentionally regular.

## What "adding a broker" means

A broker integration connects qkt to one trading venue. It accepts `OrderRequest`s from the engine, translates them to the venue's wire shape, places them, and reports back via `BrokerEvent`s on the bus.

The engine never touches venue-specific code. It only sees the `Broker` interface (`src/main/kotlin/com/qkt/broker/Broker.kt`). Everything below that interface is the broker package's private territory.

## The `Broker` interface — what you implement

```kotlin
interface Broker {
    val name: String
    val capabilities: Set<OrderTypeCapability>
    fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> = capabilities
    fun supports(symbol: String): Boolean = true

    fun submit(request: OrderRequest): SubmitAck
    fun cancel(orderId: String)
    fun modify(orderId: String, changes: OrderModification): SubmitAck =
        throw UnsupportedOperationException("$name does not support modify")
}
```

| Member | Purpose |
| --- | --- |
| `name` | Stable, human-readable venue id. Appears in logs and status output. Use the venue's common name lowercased (`exness`, `bybit-linear`, `coinbase`). |
| `capabilities` | What `OrderRequest` shapes route through this broker natively. The engine consults this before sending — strategies that use unsupported shapes fail at submit time, not silently. |
| `supports(symbol)` | Used by `CompositeBroker` to pick the right leaf. Default `true` is fine if the broker handles every symbol the engine routes to it. |
| `submit` | Returns `SubmitAck` synchronously. Fill or rejection arrives async via `BrokerEvent` on the bus. **Don't block here.** |
| `cancel` | Cancel a working order by qkt-side `clientOrderId`. No-op if the order is already terminal. |
| `modify` | Optional. Default throws `UnsupportedOperationException`. Implement if the venue's API supports modify (most modern APIs do). |

## Package layout convention

Put your broker in `src/main/kotlin/com/qkt/broker/<venue>/`. The conventional file set:

| File | Responsibility | Required? |
| --- | --- | --- |
| `<Venue>Broker.kt` | Implements `Broker`. Wires translator + client + state-recovery + (optionally) poller. | Yes |
| `<Venue>OrderTranslator.kt` | Pure function: qkt `OrderRequest` → venue wire shape. No I/O. | Yes |
| `<Venue>Client.kt` | HTTP/WS client. JSON serialization, retries, timeouts. Stateless beyond connection management. | Yes |
| `<Venue>Symbol.kt` | qkt symbol ↔ venue symbol translation. Suffix policy (`EURUSD` ↔ `EURUSDm`), alias map. | Yes |
| `<Venue>WireTypes.kt` (or inlined) | Data classes for venue request/response shapes. | Recommended for venues with rich JSON |
| `<Venue>StateRecovery.kt` | Re-sync on daemon startup: fetch open positions, emit `PositionReconciled` events. | Yes (for live brokers) |
| `<Venue>PositionPoller.kt` | If the venue doesn't push fills, polls `/positions` and detects deltas. | If venue lacks WS push |
| `<Venue>Signer.kt` | API request signing (HMAC, JWT, etc.). | If venue uses signed requests |
| `<Venue>BrokerProfile.kt` + `<Venue>DefaultProfiles.kt` | Per-account configuration (credentials, magic number, symbol policy, capability restrictions). | If multiple accounts/sub-venues share the same protocol |

Multi-variant venues (Bybit Spot vs Bybit Linear, MT5 Exness vs MT5 ICMarkets) keep the shared parts at the package root and add variant-specific files alongside. See `com.qkt.broker.bybit` for the pattern — `BybitClient.kt`, `BybitOrderTranslator.kt`, `BybitSymbol.kt`, `BybitSigner.kt` are shared; `BybitSpotBroker.kt`/`BybitSpotStateRecovery.kt` and `BybitLinearBroker.kt`/`BybitLinearStateRecovery.kt` are the variants.

## Implementation walkthrough

### Step 1 — Wire types

Define data classes for every venue request and response shape you'll touch. Even if the venue uses JSON-by-convention, write Kotlin types — they're the contract between translator and client.

```kotlin
// VenueWireTypes.kt
data class VenueOrderRequest(
    val symbol: String,
    val side: String,
    val orderType: String,
    val quantity: BigDecimal,
    val price: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    // ... whatever the venue accepts
)

data class VenueOrderResponse(
    val orderId: String,
    val status: String,
    val errorMessage: String? = null,
)
```

Reference: `MT5WireTypes.kt`. Bybit chose to inline these into `BybitClient.kt`; either is fine.

### Step 2 — Symbol policy

Different venues call the same instrument different things. qkt uses canonical names (`EURUSD`, `BTCUSDT`); the symbol class translates to the venue's name.

```kotlin
class VenueSymbol(private val policy: SymbolPolicy) {
    fun toBroker(qktSymbol: String): String = policy.aliases[qktSymbol] ?: "$qktSymbol${policy.suffix}"
    fun toQkt(brokerSymbol: String): String { /* reverse */ }
}
```

The `SymbolPolicy` data class lives in `com.qkt.broker.mt5.MT5BrokerProfile` today but is generic — feel free to use it or define a parallel `VenueSymbolPolicy`.

### Step 3 — Translator

Pure function. No I/O. Takes an `OrderRequest`, returns a venue wire shape. This is where most of the venue-specific logic lives.

```kotlin
class VenueOrderTranslator(
    private val profile: VenueBrokerProfile,
    private val symbol: VenueSymbol,
) {
    fun translate(req: OrderRequest): VenueOrderRequest =
        when (req) {
            is OrderRequest.Market -> translateMarket(req)
            is OrderRequest.Limit -> translateLimit(req)
            is OrderRequest.Stop -> translateStop(req)
            // ... all the shapes you support
            else -> error("Venue does not translate ${req::class.simpleName}")
        }

    private fun translateMarket(req: OrderRequest.Market): VenueOrderRequest = /* ... */
}
```

**Composite shapes (OCO, OTO, Bracket)** that the engine splits into atomic legs before submitting (most modern brokers handle them this way): you don't need translator support — `OrderManager.submitOco` dispatches the legs individually. Your translator only needs to handle the atomic types.

**Composite shapes the engine sends as one wire call** (rare — only when a venue has a native compound order API): use a sealed return type so a single `translate` call can return multiple wire requests. See `MT5OrderTranslator.MT5Translation` for the pattern.

### Step 4 — Client

HTTP or WebSocket client. JSON ser/de, retries on idempotent GETs (NEVER on `POST /order` — duplicate placement is worse than a surfaced failure), timeouts, basic error parsing.

```kotlin
class VenueClient(
    private val baseUrl: String,
    private val httpTimeoutMs: Long = 5000,
    private val retryAttempts: Int = 3,
) {
    fun placeOrder(req: VenueOrderRequest): VenueOrderResponse { /* POST */ }
    fun cancelOrder(orderId: String): String { /* DELETE or POST */ }
    fun getPositions(): List<VenuePosition> { /* GET with retry */ }
    fun getTick(symbol: String): VenueTick? { /* GET */ }
}
```

Use `OkHttp` for HTTP (already a project dependency). For signed requests, the signer is a separate class — see `BybitSigner.kt`.

### Step 5 — Fill detection (the hard part)

**Two strategies depending on what the venue offers:**

#### Strategy A: Push (preferred — Bybit, Coinbase, Kraken)

The venue has WebSocket that pushes order/fill events. Subscribe at broker startup, parse incoming messages, emit `BrokerEvent.OrderFilled` directly.

```kotlin
class VenueBroker(...) : Broker {
    init {
        wsClient.subscribe("order") { event ->
            bus.publish(BrokerEvent.OrderFilled(
                clientOrderId = event.clientOrderId,
                brokerOrderId = event.exchangeOrderId,
                /* ... */
            ))
        }
    }
}
```

Cleanest, lowest-latency. Reference: any modern crypto venue.

#### Strategy B: Poll (MT5, brokers without push)

The venue exposes `/positions` (and ideally `/orders` for pending). Poll at intervals, detect deltas. Two events to detect:

1. **New position appears** = a pending order filled (or a market order completed)
2. **Position disappears** = position closed (stopped out, taken profit, manual close)

Reference: `com.qkt.broker.mt5.MT5PositionPoller`. It detects opens (Phase 26c) and closes (existing). The broker registers an `onPositionOpened` callback to correlate venue tickets back to qkt `clientOrderId`s.

The key data structure for poll-based brokers:

```kotlin
private val pendingByVenueId: MutableMap<String, PendingMeta> = ConcurrentHashMap()

private data class PendingMeta(val orderId: String, val strategyId: String)

private fun onPositionOpened(position: VenuePosition) {
    val meta = pendingByVenueId.remove(position.venueOrderId) ?: return  // external, ignore
    bus.publish(BrokerEvent.OrderFilled(
        clientOrderId = meta.orderId,  // ← qkt's id, NOT the venue's
        /* ... */
    ))
}
```

The `meta.orderId` is what `OrderManager.siblings[]` keys on for OCO sibling cancel-on-fill, so this correlation is critical.

### Step 6 — State recovery

When the daemon restarts, the broker may have positions still open at the venue from before the crash. State recovery reads the current snapshot and republishes `BrokerEvent.PositionReconciled` events so qkt's local state catches up.

```kotlin
class VenueStateRecovery(
    private val client: VenueClient,
    private val profile: VenueBrokerProfile,
    private val symbol: VenueSymbol,
    private val bus: EventBus,
) {
    fun recover() {
        val positions = client.getPositions(magic = profile.magic)
        for (p in positions) {
            bus.publish(BrokerEvent.PositionReconciled(/* ... */))
        }
    }
}
```

Reference: `MT5StateRecovery.kt`. Called from `init {}` block of the broker.

### Step 7 — Broker class

Brings everything together. Implements the `Broker` interface.

```kotlin
class VenueBroker(
    private val profile: VenueBrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val client: VenueClient = VenueClient(profile.baseUrl),
) : Broker {
    override val name: String = profile.name
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    private val translator = VenueOrderTranslator(profile, VenueSymbol(profile.symbolPolicy))
    private val poller = /* if poll-based */
    private val stateRecovery = VenueStateRecovery(client, profile, symbol, bus)

    init {
        try {
            stateRecovery.recover()
            poller.start()  // or wsClient.connect()
        } catch (e: Exception) {
            log.warn("VenueBroker startup degraded: ${e.message}")
        }
    }

    override fun submit(request: OrderRequest): SubmitAck = /* translate, place, publish events */
    override fun cancel(orderId: String) = /* look up venue id, call client.cancelOrder */
}
```

The `init` block degrades gracefully — if state recovery fails or the poller can't reach the venue, log a warning and continue. The broker shouldn't refuse to construct just because the venue is temporarily unreachable.

### Step 8 — Profile + DefaultProfiles

If the broker has variants (multiple Exness accounts, Bybit Spot vs Linear) or per-instrument quirks, define a profile data class:

```kotlin
data class VenueBrokerProfile(
    val name: String,
    val baseUrl: String,
    val symbolPolicy: SymbolPolicy,
    val magic: Int,  // some venues; ignore for crypto
    val capabilityRestrictions: Set<OrderTypeCapability> = emptySet(),
    val instrumentOverrides: Map<String, InstrumentSpec> = emptyMap(),
    val pollIntervalMs: Long = 1000,
    /* ... */
) {
    val capabilities: Set<OrderTypeCapability>
        get() = VenueProtocol.capabilities - capabilityRestrictions
}
```

Reference: `MT5BrokerProfile.kt`, `MT5DefaultProfiles.kt`. The capability restrictions pattern lets you say "this venue type supports X, Y, Z protocol-wide; this specific broker (or account variant) disables X."

### Step 9 — Capability declaration

Declare what shapes the broker accepts. The engine refuses to send unsupported shapes to your broker.

```kotlin
// VenueProtocol.kt
object VenueProtocol {
    val capabilities: Set<OrderTypeCapability> = setOf(
        OrderTypeCapability.MARKET,
        OrderTypeCapability.LIMIT,
        // OrderTypeCapability.STOP,         // declare if supported
        // OrderTypeCapability.OCO,
        // OrderTypeCapability.TRAILING_STOP,
    )
}
```

Honest capability declarations prevent silent failures. A strategy submitting an OCO order to a netting-only venue should fail with a clear "capability mismatch" error, not get processed in some half-broken state.

### Step 10 — Wire the broker into the daemon

The daemon's `DaemonCommand` (`src/main/kotlin/com/qkt/cli/DaemonCommand.kt`) builds a `Map<String, BrokerFactory>` at startup. Add your broker:

```kotlin
val brokerFactories: Map<String, com.qkt.app.BrokerFactory> =
    mt5Profiles.associate { /* ... */ } +
    venueProfiles.associate { profile ->
        profile.name.lowercase() to
            { bus, clock, _ -> VenueBroker(profile, bus, clock) }
    }
```

The key is what strategies use in their `SYMBOLS` block — `EXNESS:XAUUSD` routes to the `exness` factory, `BYBIT_SPOT:BTCUSDT` routes to the `bybit-spot` factory, etc.

### Step 11 — Tests

Three layers:

**Translator unit tests** — pure functions, deterministic, no I/O. One test per shape × direction (BUY/SELL). Example: `MT5OrderTranslatorTest.kt`.

**Client unit tests** — use `okhttp3.mockwebserver.MockWebServer` to assert wire payloads. Example: `MT5ClientTest.kt`.

**Broker integration tests** — exercise the full `submit` → wire-call → response → event flow against a `MockWebServer` (or `FakeWebSocketServer` for push brokers). Cover at minimum:
- Market order: place → OrderAccepted + OrderFilled
- Pending order: place → OrderAccepted (no synthetic Filled); poll/push delivers fill → OrderFilled with correct `clientOrderId`
- OCO: both legs placed → one fills → other auto-cancels via `siblings[]`
- Cancel: working order → cancelOrder API call → OrderCancelled
- Rejected order: wire error → OrderRejected with reason

Reference: `MT5BrokerIntegrationTest.kt`.

## Anti-patterns to avoid

- **Don't catch every exception in `submit`** — let real network errors propagate to the bus as `OrderRejected`. Catching everything hides bugs.
- **Don't retry `POST /order`** — duplicate placement is worse than a surfaced failure. Retry GETs only.
- **Don't synthesize fill events that didn't happen** — if the venue says "rejected with retcode=10004", don't publish `OrderFilled`. The engine trusts you.
- **Don't block in `submit`** — return `SubmitAck` synchronously, publish fill/rejection async via the bus.
- **Don't tag orders with strategy-internal state in the venue's comment/clientOrderId field** — those round-trip through the venue and may be truncated, modified by the user, or absent in fill events.
- **Don't assume the venue keeps the same id when a pending fills** — some venues do (MT5), some don't (some FIX venues issue a new id at fill). Test the ticket-continuity assumption explicitly.

## Worked reference: Phase 26b + 26c on MT5

A complete walkthrough of adding the pending-order family to an existing broker (rather than starting from scratch) lives in two changelogs:

- [Phase 26b — MT5 native pending family + OCO + trailing](../phases/phase-26b-mt5-pending-family.md) — translation layer
- [Phase 26c — Pending-order fill-event lifecycle on MT5](../phases/phase-26c-pending-fill-lifecycle.md) — broker correlation + poller integration

Both follow the patterns above and call out where the convention bent (e.g. `MT5Translation` sealed type for OCO's two-leg case).

## When to consider an abstraction

There's no `AbstractBroker` base class in qkt today. With two broker families (MT5 and Bybit), the variation in wire protocols and fill semantics outweighs the shared surface. The `Broker` interface alone is enough.

When a **third** broker family lands (Alpaca? Coinbase? IBKR via FIX?), the right abstractions become more visible. Likely candidates:
- `AbstractPollingBroker` — shared poll loop + delta detection for poll-based venues
- `AbstractPushBroker` — shared WS connection + reconnect logic for push-based venues
- `OrderTranslatorBase<RequestT>` — generic shape with a typed wire-request return

Hold off until then. Two implementations don't justify an abstraction; three start to.

## Checklist for the PR

- [ ] Broker is in `src/main/kotlin/com/qkt/broker/<venue>/`
- [ ] Implements `Broker` interface — `name`, `capabilities`, `submit`, `cancel`, optionally `modify`
- [ ] Capability set declared honestly — strategies that submit unsupported shapes get a clean rejection
- [ ] Translator, client, symbol, state-recovery as separate files
- [ ] Push or poll for fill detection — pick one explicitly
- [ ] Translator unit tests, client wire-shape tests, broker integration tests
- [ ] Wired into `DaemonCommand` broker factory map
- [ ] Updated `docs/reference/dsl/streams.md` broker-prefix table
- [ ] Phase changelog if introducing new capability (see `docs/contributing/phase-workflow.md`)
- [ ] `tests/smoke-install.sh` passes
- [ ] `./gradlew build` passes incl. ktlint
