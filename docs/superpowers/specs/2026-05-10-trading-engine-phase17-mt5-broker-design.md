# Phase 17 — MT5 Broker (multi-profile via mt5-gateway)

**Phase:** 17
**Status:** Design
**Author:** elitekaycy
**Date:** 2026-05-10

---

## 1. Goal

Ship a generic MT5 broker that can talk to any MT5-connected venue (Exness, ICMarkets, FTMO, Pepperstone, etc.) through a per-broker `mt5-gateway` HTTP service. The broker is **profile-driven**: each broker's quirks (symbol suffix, alias map, server timezone, magic, capabilities) live in a configuration profile loaded from YAML. A single qkt strategy can route to multiple MT5 brokers simultaneously by referencing distinct profile names in stream declarations.

Phase 17 ships v1 with:

1. `MT5Protocol` — protocol-level constant declaring what the qkt translator + mt5-gateway can transact. v1 = `[MARKET, BRACKET]`.
2. `MT5Client` — pure HTTP transport (OkHttp) to a single mt5-gateway.
3. `MT5BrokerProfile` — per-broker policy (URL, symbol policy, TZ, magic, instrument specs, poll/timeout/retry, optional capability restrictions). Capabilities derived from `MT5Protocol`, not declared per profile.
4. `MT5DefaultProfiles` — built-in registry: `exness`, `icmarkets`, `ftmo`, `pepperstone` with sensible defaults. Operators override per installation.
5. `MT5Broker(profile)` — implements `Broker`. Owns its `MT5Client` + `MT5PositionPoller` + `MT5StateRecovery`.
6. `MT5OrderTranslator` — qkt `OrderRequest.Market` and `OrderRequest.Bracket` → `MT5OrderRequest`. Other variants fall through to qkt's existing engine-managed paths.
7. `MT5BrokerProfileLoader` — extends Phase 12a's `qkt.config.yaml` `brokers:` section. Resolves built-in defaults + user/project YAML overrides + env vars + CLI flags. Supports name-match partial overrides and explicit `extends:` chains.
8. Daemon + CLI integration: profiles auto-load at startup; new `qkt brokers list` shows resolved profiles with provenance.

**v1 broker capability set:** `[MARKET, BRACKET]`. All other qkt order types continue to work via `OrderManager`'s engine-managed fallback paths — qkt holds the trigger logic and forwards Market entries to the broker when triggers fire.

**v1 tick source:** TradingView (Phase 7c, existing). MT5 handles execution only. The "TradingView vs MT5 prices may diverge" risk is documented; the deferred tick-feed accuracy audit will quantify it.

**Out of scope (deferred):**

- `[LIMIT, STOP, STOP_LIMIT]` broker-managed pending orders. Engine-managed fallback covers them in v1.
- Cross-broker same-symbol routing (e.g., `EURUSD` on both Exness and ICMarkets in one strategy). Documented limitation; refactors `CompositeBroker` + `PositionTracker` + `BrokerEvent` to key on `(brokerName, symbol)` are deferred.
- MT5-gateway WebSocket fills. mt5-gateway is HTTP-only today; we poll positions on a 1Hz cadence per profile.
- Position-close annotation (was-this-an-SL-hit-vs-TP-hit-vs-manual). Future MT5 deal-history query.
- Multi-account per single mt5-gateway. Each profile = its own gateway container.
- `OrderManager.modify` for SL/TP changes after entry.

---

## 2. Why

The qkt user wants to trade Exness via MT5 starting tomorrow. Building a broker for one venue is wasted work — MT5 supports dozens of brokers and each has small but meaningful policy differences. Building "broker = profile" once buys all of them.

Specifically, brokers vary on:

- **Symbol suffixes**: Exness `EURUSDm`, ICMarkets `EURUSD.raw`, FTMO `EURUSD`, Pepperstone `EURUSD.cmd`, etc.
- **Symbol aliases**: Exness names index CFDs `USTEC` for NAS100 and `XBRUSD` for UK Brent; others use the contract names directly.
- **Server timezones**: most MT5 brokers run on GMT+2/+3 server time (DST-dependent); some use exchange-local time. Reported timestamps must convert to UTC.
- **Capabilities**: not every broker enables every order type at the MT5 level (some restrict pending orders on certain instruments).
- **Min volumes / point sizes**: per-symbol and per-broker.
- **Magic numbers**: identify which "expert" placed an order; a single qkt instance using two profiles needs distinct magics so the position poller can scope correctly.

Hardcoding any of these in the broker class blocks adding a second venue. A profile data class with sensible defaults plus a YAML config-file solves this once.

Reference: `~/Desktop/personal/fxquant/pa-quant/src/broker/mt5-client.ts` (~536 LOC TypeScript) demonstrates the pattern in production today — `symbolSuffix`, `serverTzOffsetHours`, alias maps, retry + circuit-breaker, single `placeOrder` translating BUY/SELL/BUY_LIMIT/etc.

---

## 3. Architecture

### 3.1 Component layout

```
                                                        ┌─────────────────┐
qkt strategy                                            │ mt5-gateway A   │
   │ Signal.Buy("EURUSD", 0.1)                          │ (Exness)        │
   ▼                                                    │ http://:5001    │
┌──────────────────┐                                    └────────▲────────┘
│  OrderManager    │                                             │
└──────────────────┘                                             │
   │ if MARKET ∈ profile.capabilities → broker; else fallback    │
   ▼                                                             │
┌──────────────────────────────────┐                             │
│  CompositeBroker                 │                             │
│  routes by symbol → Broker       │                             │
└──────────────────────────────────┘                             │
   │ symbol "EURUSD" → exness profile                            │
   ▼                                                             │
┌──────────────────────────────────┐    HTTP placeOrder          │
│  MT5Broker(profile=exness)       │─────────────────────────────┘
│   ├─ MT5Client(gatewayUrl)       │
│   ├─ MT5OrderTranslator(profile) │
│   ├─ MT5Symbol(profile)          │
│   ├─ MT5PositionPoller(profile)  │ ◀──── 1Hz GET /positions ──┐
│   └─ MT5StateRecovery(profile)   │                            │
└──────────────────────────────────┘                            │
                                                                ▼
                                                         emits BrokerEvent.OrderFilled
                                                         on position-disappeared
```

Each profile is one full vertical slice: client + poller + state recovery.

### 3.2 Capabilities — protocol-level constant

Capabilities are a property of the **MT5 protocol** (what the qkt translator + mt5-gateway can transact), not the individual broker. Hardcoding them per-profile would let users claim capabilities the translator can't deliver and forces every profile to repeat the same list.

```kotlin
object MT5Protocol {
    /**
     * What the qkt MT5 translator + mt5-gateway can transact natively.
     * v1 ships MARKET + BRACKET; v2 adds LIMIT, STOP, STOP_LIMIT once
     * MT5OrderTranslator handles them. Profiles never widen this — they
     * may only restrict via [MT5BrokerProfile.capabilityRestrictions].
     */
    val capabilities: Set<OrderTypeCapability> = setOf(MARKET, BRACKET)
}
```

Effect: when v2 lands, updating the constant gives all profiles the new capabilities automatically — no per-profile config change.

### 3.3 Profile schema

```kotlin
data class MT5BrokerProfile(
    val name: String,                              // DSL broker label, e.g. "exness"
    val gatewayUrl: String,                        // "http://localhost:5001"
    val symbolPolicy: SymbolPolicy,
    val serverTzOffsetHours: Int = 0,              // e.g. 2 or 3 for Exness; UTC subtraction
    val magic: Int,                                // identifies orders placed by this profile
    val instrumentOverrides: Map<String, InstrumentSpec> = emptyMap(),
    val pollIntervalMs: Long = 1000,
    val httpTimeoutMs: Long = 5000,
    val retryAttempts: Int = 3,
    val deviationPoints: Int = 20,
    val capabilityRestrictions: Set<OrderTypeCapability> = emptySet(),
) {
    /** Effective capabilities: protocol set minus opt-outs declared by the broker. */
    val capabilities: Set<OrderTypeCapability>
        get() = MT5Protocol.capabilities - capabilityRestrictions
}

data class SymbolPolicy(
    val suffix: String = "",                       // "m" Exness, ".raw" ICMarkets, "" FTMO
    val aliases: Map<String, String> = emptyMap(), // qkt → broker base name; e.g. NAS100→USTEC
)

data class InstrumentSpec(
    val minVolume: BigDecimal,
    val volumeStep: BigDecimal,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,                // min distance for SL/TP in points
)
```

Field discipline — what's protocol-level vs profile-level:

| Concern | Where | Reason |
|---|---|---|
| Order type translation logic | Protocol (`MT5OrderTranslator`) | Same gateway, same wire format |
| `capabilities` | Protocol constant | Derived from translator + gateway |
| Field schema (`type`, `volume`, `sl`, `tp`, `magic`, `comment`) | Protocol (`MT5OrderRequest`) | mt5-gateway contract |
| `gatewayUrl` | Profile | Each broker = own gateway container |
| `symbolPolicy` (suffix, aliases) | Profile | Per-broker convention |
| `serverTzOffsetHours` | Profile | Per-broker server time |
| `magic` | Profile | Must be unique per profile |
| `pollIntervalMs`, `httpTimeoutMs`, `retryAttempts` | Profile | Per-broker tolerance/network |
| `deviationPoints` | Profile (default 20) | Per-broker liquidity |
| `instrumentOverrides` | Profile | Per-broker per-symbol min volume etc |
| `capabilityRestrictions` | Profile (optional) | Rare opt-outs (FTMO etc) |

### 3.4 Built-in default profiles

Shipped in qkt source as a registry:

```kotlin
object MT5DefaultProfiles {
    val exness = MT5BrokerProfile(
        name = "exness",
        gatewayUrl = "http://localhost:5001",
        symbolPolicy = SymbolPolicy(
            suffix = "m",
            aliases = mapOf(
                "NAS100" to "USTEC",
                "US500" to "US500",
                "US30" to "US30",
                "UKOIL" to "XBRUSD",
                "NGAS" to "XNGUSD",
            ),
        ),
        serverTzOffsetHours = 2,
        magic = 10001,
    )

    val icmarkets = MT5BrokerProfile(
        name = "icmarkets",
        gatewayUrl = "http://localhost:5002",
        symbolPolicy = SymbolPolicy(suffix = ".raw"),
        serverTzOffsetHours = 3,
        magic = 10002,
    )

    val ftmo = MT5BrokerProfile(
        name = "ftmo",
        gatewayUrl = "http://localhost:5003",
        symbolPolicy = SymbolPolicy(suffix = ""),
        serverTzOffsetHours = 2,
        magic = 10003,
    )

    val pepperstone = MT5BrokerProfile(
        name = "pepperstone",
        gatewayUrl = "http://localhost:5004",
        symbolPolicy = SymbolPolicy(suffix = ".cmd"),
        serverTzOffsetHours = 2,
        magic = 10004,
    )

    val all: Map<String, MT5BrokerProfile> = listOf(exness, icmarkets, ftmo, pepperstone).associateBy { it.name }
}
```

These are stub defaults — operators override per-installation. New defaults get added over time via PRs as users converge on them.

`profile.name` is **also** the DSL broker label. So `EXNESS:EURUSD` in a `.qkt` file resolves to a profile named "exness" (case-insensitive normalize at lookup).

### 3.5 Project config — extending `qkt.config.yaml`

Phase 12a established `qkt.config.yaml` (loaded by `com.qkt.cli.Config`) with `${VAR}` env-var expansion and a reserved `brokers:` section. Phase 17 wires that section into MT5 profile resolution. **No new config file** — broker profiles live alongside existing config.

Three end-user patterns:

**Pattern A — partial override of a built-in default (most common):**

The profile name matches a built-in (`exness`). User-provided fields override the corresponding default fields; everything else is inherited.

```yaml
# ./qkt.config.yaml
brokers:
  exness:
    type: mt5
    gateway_url: http://localhost:5005    # only this changes
```

Resolved profile: built-in `exness` with `gatewayUrl = "http://localhost:5005"`. Suffix, aliases, TZ, magic all inherited.

**Pattern B — extend a default under a new name (multiple accounts):**

```yaml
brokers:
  exness-personal:
    type: mt5
    extends: exness                       # explicit base
    gateway_url: http://localhost:5005
    magic: 10005

  exness-corporate:
    type: mt5
    extends: exness
    gateway_url: http://localhost:5006
    magic: 10006
```

Both inherit Exness's symbol policy and TZ; only URL + magic differ. Strategies reference `EXNESS-PERSONAL:EURUSD` or `EXNESS-CORPORATE:XAUUSD`.

**Pattern C — totally new broker (no default):**

```yaml
brokers:
  myforex:
    type: mt5
    gateway_url: http://localhost:6000
    symbol_suffix: ".pro"
    server_tz_offset_hours: 3
    magic: 50001
    aliases:
      NAS100: NAS100.pro
```

`type: mt5` is the discriminator that flags MT5-protocol brokers (vs `bybit`, `alpaca`, etc. — handled by other broker modules).

**Resolution rules:**

1. Start with built-in defaults from `MT5DefaultProfiles.all`.
2. For each entry in YAML `brokers:`:
   - If `extends:` is set → start from that base (built-in or another user profile).
   - Else if name matches a built-in → start from that built-in.
   - Else → empty starting point; user must provide all required fields.
3. Deep-merge user fields on top of the base.
4. Validate: required fields present, magic unique across all resolved profiles.

Required fields for a fresh profile (no `extends:`, no name match): `gateway_url`, `magic`, `server_tz_offset_hours`. Defaults exist for everything else.

### 3.6 Resolution order (last wins)

1. Built-in defaults (`MT5DefaultProfiles.all`).
2. User config: `~/.config/qkt/qkt.config.yaml`.
3. Project config: `./qkt.config.yaml` (or `--config <path>`).
4. Env vars: `QKT_BROKER_EXNESS_GATEWAY_URL=http://...` (surgical hot-fix; field name is uppercased and snake-cased).
5. CLI flags on relevant commands: `qkt deploy strategy.qkt --broker exness --gateway-url http://...` (one-shot override; rare, used in CI).

`${VAR}` expansion already works in YAML values via `Config.expandVars()` (Phase 12a):

```yaml
brokers:
  exness:
    type: mt5
    gateway_url: ${QKT_EXNESS_URL}
    magic: ${QKT_EXNESS_MAGIC}
```

### 3.7 `qkt brokers list` — inspect what's loaded

New CLI subcommand for prod debugging:

```
$ qkt brokers list
NAME              KIND  SOURCE                                GATEWAY                  MAGIC  HEALTH
exness            mt5   built-in + ./qkt.config.yaml          http://localhost:5005    10001  ok
exness-personal   mt5   ./qkt.config.yaml (extends exness)    http://localhost:5006    10005  ok
icmarkets         mt5   built-in                              http://localhost:5002    10002  unreachable
myforex           mt5   ./qkt.config.yaml                     http://localhost:6000    50001  ok
bybit             rest  ~/.config/qkt/qkt.config.yaml         api.bybit.com            -      ok
```

The `SOURCE` column shows where each profile's fields came from (built-in / user / project / env). `qkt brokers list --json` for tooling.

### 3.8 Daemon startup wiring

```kotlin
// In DaemonCommand.startDaemon():
val config = Config.load(resolveConfigPath(args))
val mt5Profiles = MT5BrokerProfileLoader().loadFromConfig(
    config.brokers,
    defaults = MT5DefaultProfiles.all,
    envOverrides = System.getenv(),
)
val mt5Brokers = mt5Profiles.map { MT5Broker(it, bus, clock) }
val brokerRegistry = (mt5Brokers.associateBy { it.name }) + existingBrokers
// ... wire into CompositeBroker
```

Missing config file → empty `mt5Profiles` → no MT5 brokers; existing setup works.

### 3.9 `MT5Symbol` — translation

Two methods:

```kotlin
class MT5Symbol(private val policy: SymbolPolicy) {
    fun toBroker(qktSymbol: String): String {
        val base = policy.aliases[qktSymbol] ?: qktSymbol
        return base + policy.suffix
    }

    fun toQkt(brokerSymbol: String): String {
        val base =
            if (policy.suffix.isNotEmpty() && brokerSymbol.endsWith(policy.suffix)) {
                brokerSymbol.removeSuffix(policy.suffix)
            } else {
                brokerSymbol
            }
        // Reverse alias if any (cache reverse map at construction)
        return reverseAliases[base] ?: base
    }
}
```

Used on submit (qkt → broker) and on event ingest (broker → qkt). Round-trip property: `toQkt(toBroker(s)) == s` for all qkt symbols listed in aliases or accepted by the suffix rule. Unit-tested.

### 3.10 `MT5OrderTranslator` — order shape

v1 handles two qkt order types:

```kotlin
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
) {
    fun translate(req: OrderRequest): MT5OrderRequest =
        when (req) {
            is OrderRequest.Market -> translateMarket(req)
            is OrderRequest.Bracket -> translateBracket(req)
            else -> error("MT5 v1 does not natively translate ${req::class.simpleName}; " +
                "OrderManager should fallback")
        }

    private fun translateMarket(req: OrderRequest.Market): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = null,
            tp = null,
            magic = profile.magic,
            comment = req.id,                       // round-trip qkt order id
            deviation = 20,                         // points; configurable later
        )

    private fun translateBracket(req: OrderRequest.Bracket): MT5OrderRequest {
        val entry = req.entry
        require(entry is OrderRequest.Market) { "MT5 v1 brackets require Market entry" }
        return MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = req.stopLoss,
            tp = req.takeProfit,
            magic = profile.magic,
            comment = req.id,
            deviation = 20,
        )
    }
}
```

For non-`Market`/`Bracket` types, `MT5Broker.submit` returns a rejection that signals OrderManager to take its existing fallback path (engine-managed pending orders). The fallback eventually emits a `Market` entry which IS broker-managed.

### 3.11 `MT5Client` — HTTP transport

Single class wrapping OkHttp. One instance per gateway URL.

```kotlin
class MT5Client(
    private val gatewayUrl: String,
    private val tzOffsetHours: Int,
    private val httpTimeoutMs: Long = 5000,
    private val retryAttempts: Int = 3,
) {
    fun isReady(): Boolean
    fun getAccountInfo(): MT5AccountInfo?
    fun getTick(brokerSymbol: String): MT5Tick?
    fun getSymbolInfo(brokerSymbol: String): MT5SymbolInfo?
    fun placeOrder(req: MT5OrderRequest): MT5OrderResponse
    fun getPositions(magic: Int? = null): List<MT5Position>
    fun getPendingOrders(magic: Int): List<MT5PendingOrder>
    fun closePosition(position: MT5Position): MT5OrderResult?
    fun cancelOrder(ticket: Long): CancelResult     // 'cancelled' | 'filled' | 'failed'
    fun modifySLTP(ticket: Long, sl: BigDecimal, tp: BigDecimal): MT5OrderResult?
}
```

**Timezone handling:** every method that returns a timestamp (e.g. `MT5Position.openTime`) subtracts `tzOffsetHours * 3600 * 1000` ms before returning. qkt-internal time is always UTC.

**Retry policy:** `retryAttempts` retries on `IOException` with linear backoff (200ms, 400ms, 800ms). POST `/order` is **not retried** — duplicate placement is worse than a transient failure surfaced to the caller. GET endpoints retry freely.

**Circuit breaker:** simplistic open/closed/half-open with a 5s window after 3 consecutive failures. Skipped in v1 if it adds complexity; the retry policy handles transient blips. Documented as a future enhancement.

### 3.12 `MT5Broker` — Broker implementation

```kotlin
class MT5Broker(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    override val name: String = profile.name
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    override fun supports(symbol: String): Boolean = true

    private val client = MT5Client(
        gatewayUrl = profile.gatewayUrl,
        tzOffsetHours = profile.serverTzOffsetHours,
        httpTimeoutMs = profile.httpTimeoutMs,
        retryAttempts = profile.retryAttempts,
    )
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol)
    private val poller = MT5PositionPoller(client, profile, mt5Symbol, bus, clock)
    private val stateRecovery = MT5StateRecovery(client, profile, mt5Symbol, bus)

    init {
        stateRecovery.recover()         // emits BrokerEvent.PositionReconciled per open position
        poller.start()                  // daemon thread polls /positions every pollIntervalMs
    }

    override fun submit(request: OrderRequest): SubmitAck {
        if (request !is OrderRequest.Market && request !is OrderRequest.Bracket) {
            return SubmitAck.Rejected("MT5 v1 does not natively support ${request::class.simpleName}")
        }
        val mt5Req = translator.translate(request)
        val resp = client.placeOrder(mt5Req)
        if (!isOrderSuccessful(resp.result.retcode)) {
            bus.publish(BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                reason = resp.errorMessage ?: "retcode=${resp.result.retcode}",
                timestamp = clock.now(),
            ))
            return SubmitAck.Rejected(resp.errorMessage ?: "retcode=${resp.result.retcode}")
        }
        // MT5 fills market orders synchronously; emit accept + fill immediately.
        bus.publish(BrokerEvent.OrderAccepted(
            clientOrderId = request.id,
            brokerOrderId = resp.result.deal.toString(),
            timestamp = clock.now(),
        ))
        bus.publish(BrokerEvent.OrderFilled(
            clientOrderId = request.id,
            brokerOrderId = resp.result.deal.toString(),
            symbol = request.symbol,
            side = request.side,
            price = resp.result.price,
            quantity = request.quantity,
            strategyId = request.strategyId,
            timestamp = clock.now(),
        ))
        return SubmitAck.Accepted(brokerOrderId = resp.result.deal.toString())
    }

    override fun cancel(orderId: String) {
        // v1: no native pending orders → nothing to cancel server-side.
        // OrderManager handles engine-managed pending cancellation in-process.
    }
}
```

### 3.13 `MT5PositionPoller` — close detection

Daemon thread, one per profile. Every `pollIntervalMs`:

1. `client.getPositions(magic = profile.magic)` → current set keyed by ticket.
2. Diff against previous snapshot.
3. For each ticket in `previous \ current`: emit `BrokerEvent.OrderFilled` for the close, with side flipped from the original entry. Price comes from the most recent known position (last seen before disappearance) — caveat: this is approximate; future enhancement queries deal history for the exact close price.
4. For each ticket in `current` (existing): no event.
5. Update snapshot.

Emits use the qkt symbol (`mt5Symbol.toQkt(brokerSymbol)`) so downstream `PositionTracker` reads the same key the strategy used.

### 3.14 `MT5StateRecovery` — startup reconciliation

On `MT5Broker.init`:

1. `client.getPositions(magic = profile.magic)` → all open positions for this profile.
2. For each: emit `BrokerEvent.PositionReconciled(symbol, qty, avgPx)` — qkt's `PositionTracker.reset` overwrites local state with broker truth.

This handles the case where qkt restarted but real positions remain at the broker. Without this, qkt would believe it's flat and place duplicate entries.

### 3.15 `MT5BrokerProfileLoader`

```kotlin
class MT5BrokerProfileLoader {
    fun load(path: Path): List<MT5BrokerProfile> {
        if (!Files.exists(path)) return emptyList()
        val yaml = Files.readString(path)
        // snakeyaml-engine, already a dep
        val root = Load(LoadSettings.builder().build()).loadFromString(yaml) as Map<*, *>
        val brokers = root["brokers"] as Map<*, *>? ?: return emptyList()
        return brokers.map { (name, cfg) -> parseProfile(name as String, cfg as Map<*, *>) }
    }
}
```

Daemon startup wires:

```kotlin
val profiles = MT5BrokerProfileLoader().load(brokersFile)
val mt5Brokers = profiles.map { MT5Broker(it, bus, clock) }
val brokerRegistry = mapOf(
    "exness" to mt5Brokers.first { it.name == "exness" },
    /* etc */,
) + existingBrokers
```

Then `CompositeBroker` is constructed from the registered brokers + their declared symbols.

### 3.16 DSL routing

No DSL change. `EXNESS:EURUSD` in a `.qkt` file resolves the broker label "EXNESS" against the registered profile names (case-insensitive). The compiled strategy emits `Signal.Buy("EURUSD", ...)`; the engine routes to the right `Broker` instance via existing `CompositeBroker` machinery.

### 3.17 Multi-profile in one strategy (Scenarios A + B)

```
SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m
    gold = ICMARKETS:XAUUSD EVERY 1m
    btc = BYBIT:BTCUSDT EVERY 1m
```

Routes:
- `eur` orders → `MT5Broker(exness)` → mt5-gateway @ :5001 → Exness MT5
- `gold` orders → `MT5Broker(icmarkets)` → mt5-gateway @ :5002 → ICMarkets MT5
- `btc` orders → existing `BybitBroker` → Bybit REST

Each broker has its own poller, state recovery, and `BrokerEvent` stream. `PositionTracker` keys by symbol — Scenario A/B has distinct symbols so no collision. Scenario C (same symbol on two MT5 brokers) is the deferred limitation.

### 3.18 Live tick source

v1 reuses TradingView (Phase 7c). The DSL `EXNESS:EURUSD` declaration tells qkt: route orders to Exness, but qkt's `MarketSourceProvider` for live ticks maps the broker label "EXNESS" to a `TradingViewMarketSource` instance (configured via the existing market-source registry).

This is a one-line config in the daemon startup: register `"exness" -> TradingViewMarketSource` (and `"icmarkets" -> TradingViewMarketSource`) under the same naming convention used today for Bybit/Alpaca.

Future: when the deferred tick-feed accuracy audit ships, an `MT5TickSource` becomes available as an alternative for profiles whose users prefer broker-native quotes.

---

## 4. Migration

**No DSL changes.** Existing strategies keep working. New strategies can declare `EXNESS:`/`ICMARKETS:` streams as soon as a profile is in `brokers.yaml`.

**No `Broker` interface changes.** `MT5Broker` slots into the existing dispatch.

**New optional config file.** `~/.config/qkt/brokers.yaml`. Absent → no MT5 brokers; existing setups unaffected.

**`PaperBroker` continues to be the default for paper-only setups.** Adding MT5 is opt-in via brokers.yaml.

---

## 5. Testing

### Unit

- `MT5SymbolTest`: round-trip `toBroker(toQkt(s)) == s` for every alias, every suffix combo. Edge cases: empty suffix, no aliases.
- `MT5OrderTranslatorTest`: Market BUY/SELL → correct MT5 type/volume/comment. Bracket with sl/tp → correct fields. Stack/Limit/Stop → throws (caller falls back). Magic and symbol both reflect profile.
- `MT5BrokerProfileLoaderTest`: parse known YAML → expected profiles. Missing file → empty list. Malformed YAML → clear error.

### Integration (fake gateway)

- `MT5BrokerIntegrationTest`: spin up a fake HTTP server (OkHttp MockWebServer or similar). Submit a Market order → assert the gateway received POST /order with translated payload + magic. Inject a successful retcode response → assert `BrokerEvent.OrderFilled` published.
- Submit a Bracket → assert sl/tp made it into the request body.
- Two concurrent profiles (two fake servers): submit orders on each → assert each gateway received only its own.
- State recovery: pre-seed fake gateway with 2 open positions → instantiate `MT5Broker` → assert `BrokerEvent.PositionReconciled` published twice.
- Position poller: gateway returns position list with one ticket for 3 polls, then empty → assert one `BrokerEvent.OrderFilled` (close) emitted.

### End-to-end (manual or in CI with real mt5-gateway demo account)

- Deploy a strategy referencing `EXNESS:EURUSD` against a demo Exness MT5 account through a local mt5-gateway → confirm a Market order fills and qkt's PositionTracker matches MT5's open position.

---

## 6. Risks

**High — TradingView vs MT5 price drift.** Strategy decisions on TV prices, fills at MT5 prices. Spread + sub-second drift can flip whether a stop-loss triggers in backtest vs live. Mitigation: strategies should set stop-loss buffers wider than typical TV/MT5 spread for the symbol. Quantitative bound waits on the deferred tick-feed audit.

**Medium — Position-poll cadence latency.** 1Hz default means up to 1s between MT5 SL trigger and qkt awareness. For 1m+ bar strategies, fine. Strategies needing tighter feedback should set `pollIntervalMs: 250` in their profile. CPU cost is negligible (one HTTP GET per second per broker).

**Medium — mt5-gateway availability.** If the gateway crashes or MT5 disconnects from the broker (Wine OOM, network), `MT5Client` returns null/error. `MT5Broker.submit` rejects the order. Mitigation: log clearly; `qkt status mt5:<profile>` exposes gateway health (`/health` endpoint exists on gateway).

**Medium — Magic-number collisions across instances.** If two qkt daemons use the same `magic` against the same broker, their position pollers see each other's positions. Mitigation: doc says "magic must be unique per (broker, qkt instance)". Future: derive magic from instance id + profile.

**Low — Symbol mapping bugs.** Centralized in `MT5Symbol`, fully unit-tested. Profile-level overrides catch broker-specific oddities.

**Low — TZ drift across DST transitions.** Profile says GMT+2, broker switches to GMT+3 in summer. Wrong offset → 1h timestamp error. Mitigation: docs warn; future enhancement queries the gateway for current server time and computes offset dynamically.

**Low — Approximate close prices on poller-detected exits.** Poller emits the last-known position price on disappearance, not the actual close fill price. Strategies that rely on precise close-price PnL accounting will be off by spread. Mitigation: future enhancement queries `/deal_history` for the exact deal price after close detection.

---

## 7. References

- Reference TypeScript impl: `~/Desktop/personal/fxquant/pa-quant/src/broker/mt5-client.ts`
- mt5-gateway service: `~/Desktop/personal/fxquant/mt5-gateway` (Docker, Wine, MT5, Flask)
- qkt Bybit broker (architectural pattern): `src/main/kotlin/com/qkt/broker/bybit/`
- qkt `Broker` interface: `src/main/kotlin/com/qkt/broker/Broker.kt`
- qkt `OrderRequest`: `src/main/kotlin/com/qkt/execution/OrderRequest.kt`
- qkt `BrokerEvent`: `src/main/kotlin/com/qkt/events/BrokerEvent.kt`
- qkt `CompositeBroker`: `src/main/kotlin/com/qkt/broker/CompositeBroker.kt`
- Phase 7e–7h Bybit changelogs (live-broker pattern): `docs/phases/`
- Phase 14 portfolio daemon changelog (multi-broker patterns): `docs/phases/phase-14.md`
