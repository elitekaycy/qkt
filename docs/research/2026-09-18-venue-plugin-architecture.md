# Venue plugins — futures, crypto, and any broker — research

**Date:** 2026-09-18
**Question:** How do we make qkt able to trade futures (and more crypto venues, and eventually
anything) through installable adapters — e.g. `qkt add --broker ampfuture` dropping in a JAR —
with one global mapping for instruments, orders, fills, positions, account data and credentials?
What is feasible, what scales, and what is sound enough to put real money behind?

**Builds on:** [2026-06-08 MT5 as the universal provider](2026-06-08-mt5-universal-provider.md).
Both blockers that document named (per-symbol calendars, config-driven profiles) have since
shipped (`broker/mt5/SymbolCalendars.kt`, `MT5BrokerProfileLoader.kt:34-37`). This document is
the next step it deferred: the venues MT5 cannot reach, and the deep asset-class concepts.

**Status:** research / proposal. Step 1a (the contracts, MT5 and Bybit behind them, live wiring) is
implemented — see [trading account contracts](../superpowers/specs/2026-09-18-trading-account-contracts-design.md).
The design settled on these names: `Connector`, `TradingAccount`, `AccountDirectory`; the research's
`Venue` is `TradingAccount`.

---

## TL;DR

- **Feasible — the seams mostly exist already.** `Broker` (`broker/Broker.kt`) is a narrow,
  asset-agnostic venue interface; `CompositeBroker` routes by symbol prefix; `MarketSource` +
  `MarketSourceCapability` do the same for data; `InstrumentRegistry` is an interface;
  `PositionAccountingMode` (NETTING/HEDGING), `CostKind.FUNDING/EXCHANGE_FEE` and
  `BrokerEvent.ConnectionChanged` are already modelled. qkt ships as a plain JVM 21 app
  (`installDist` + jlink), not a native image, so loading code at runtime is possible.
- **What blocks plugins today is small and specific:** `LiveSession` casts to `MT5Broker` in three
  places to get instrument metadata; broker construction is hand-assembled in `DaemonCommand`
  (`mt5Factories + bybitFactories`); credentials come from three different mechanisms; the
  broker YAML reader is flat; the jlink runtime lacks modules plugins commonly need; and
  `OrderRequest` (20 subtypes, many engine-internal) is too rich to be a public contract.
- **Your JAR idea is right for the adapter code, not for where live money runs.** The whole
  safety model you built — guardrails polling an independent gateway, the kill switch enforced
  *in the gateway* on order paths — depends on a choke point **outside** qkt. A Rithmic adapter
  loaded straight into the qkt JVM has no such choke point: the guardian could no longer stop it.
- **Recommendation — write each adapter once as a JAR, run it in one of two hosts:**
  1. **in-process** inside qkt, for backtest, paper and data; or
  2. **inside `qkt-venued`**, a small generic gateway host that loads the *same* JAR and exposes a
     versioned **Venue Gateway Protocol (VGP v1)** — the mt5-gateway's endpoints, generalized,
     with the kill switch implemented once in the host. Live money always goes through (2).
     Guardrails talks VGP and works against every venue unchanged.
- **Venue ≠ protocol ≠ account ≠ data vendor.** "AMP" is a futures clearing firm (FCM) you reach
  *through* a connectivity protocol (Rithmic, CQG, …). The plugin is the protocol
  (`rithmic`); AMP is configuration. So `qkt add --broker ampfuture` becomes two verbs:
  `qkt plugin install rithmic` (code) and `qkt brokers add amp --plugin rithmic` (account).
- **Futures and dated crypto futures share one core model.** Expiry, roll, margin, settlement
  and funding belong in the venue-neutral engine; plugins only *report* those facts. Build them
  once and Deribit/Binance quarterlies get them for free.
- **Soundness comes from three things, not from the loader:** certification levels enforced at
  deploy (DATA / PAPER / LIVE), a conformance test kit built from the defects you have already
  hit live, and plugin provenance recorded in promotion. Without these, plugins become the
  easiest way to lose money silently.
- **Start with the seams, not the loader.** Phase 1 is pure refactoring inside the monolith with
  zero behaviour change — and it is valuable even if plugins never ship.

---

## 1. What exists today

Verified in source on 2026-09-18 (branch `fix-live-parity-audit`, head `107bb211`).

### Already plugin-shaped

| Seam | File | Why it helps |
|---|---|---|
| Venue interface | `broker/Broker.kt` | `submit / cancel / modify / modifyPosition`, capabilities, `getOpenPositions`, `recoverPendingOrders`, `watchBookedLegs`, `deals`, `accountState`, `pendingOrders`, `positionAccountingMode`, `shutdown`. Asset-agnostic. |
| Per-session construction | `app/BrokerFactory.kt` | `(EventBus, Clock, MarketPriceTracker, PositionProvider, String?) -> Broker`. Its doc already states the principle this design needs: *"the venue label is the public identity, the protocol is an implementation detail."* |
| Multi-venue routing | `broker/CompositeBroker.kt` | First-match `SymbolPattern` routing, order-id and ticket → leaf tracking, merged deals / pending orders. |
| Market data | `marketdata/source/MarketSource.kt` | `liveTicks / bars / ticks / tickSlice`, per-symbol capabilities. `cli/MarketSourceFactory.kt` composes by prefix. |
| Data capabilities | `MarketSourceCapability` | `LIVE_TICKS, BARS, TICKS, VOLUME` — volume-weighted indicators are already rejected on a feed without `VOLUME`. |
| Instruments | `instrument/InstrumentRegistry.kt` | Interface with a hard error on missing meta (no silent `contractSize = 1`). |
| Order capabilities | `broker/OrderTypeCapability.kt` | The engine decomposes composites (Bracket, OCO, OTO…) into what a venue accepts. |
| Position modes | `broker/BrokerState.kt` | `PositionAccountingMode.NETTING / HEDGING / UNKNOWN`. Futures are netting. |
| Cost kinds | `accounting/Accounting.kt` | `CostKind`: `COMMISSION, SWAP, FUNDING, BORROW, EXCHANGE_FEE, SPREAD_COST, TAX` — perps (funding) and futures (exchange fees) already have a slot. |
| Connection events | `events/BrokerEvent.kt` | `ConnectionChanged`, `GatewayUnreachable`, `AccountEquityStale`, `PositionReconciled`. |
| Calendars | `broker/mt5/SymbolCalendars.kt`, `common/DailyBreakCalendar.kt` | Per-symbol rules, `"<base> pause HH:MM-HH:MM <Zone>"`. |
| Runtime | `build.gradle.kts` | JVM 21, `application` + `installDist`, jlink runtime, jpackage on Windows. No native image. |

Existing venues: MT5 (`broker/mt5`, ~6.6k lines; `MT5Broker.kt` alone 2,842), Bybit spot and
linear (`broker/bybit`, ~2.1k lines), `PaperBroker`, `LogBroker`, `MT5BrokerSimulator`.

### What blocks plugins

1. **`LiveSession` reaches into MT5.** `app/LiveSession.kt:636`, `:1004` and `:1355` use
   `filterIsInstance<MT5Broker>()` to build `MT5InstrumentRegistry`. A third-party broker has no
   way to supply instrument metadata to a live session.
2. **Broker assembly is hardcoded.** `cli/DaemonCommand.kt:~270-350` builds `mt5Factories` from
   profiles and `bybitFactories` only when `BYBIT_API_KEY` is set, under hardcoded keys
   `"bybit_spot"` / `"bybit_linear"`. `MarketSourceFactory.composite` repeats the same hardcoding
   for data. `BotSessionCommand.kt:186` constructs `MT5Broker` directly as well.
3. **Credentials come from three places.** MT5: config + `${VAR}` + `QKT_BROKER_<NAME>_<FIELD>`
   overrides (`MT5BrokerProfileLoader`). Bybit: direct `System.getenv("BYBIT_API_KEY")`
   inside `BybitClient` (`:119-139`). FRED: direct env. A plugin has no host service to ask.
4. **The broker YAML reader is flat.** `brokers:` arrives as `Map<String, Map<String, String>>`;
   nested blocks (calendars, aliases, overrides) are parsed separately per broker name
   (`MT5BrokerProfileLoader` docstring). A plugin cannot declare nested config such as a
   `credentials:` block. `snakeyaml-engine` is already a dependency, so this is a parser choice,
   not a missing capability.
5. **The runtime has a fixed module set.** Dockerfile and `jlinkRuntime` ship only
   `java.base, java.logging, java.naming, java.xml, jdk.httpserver, jdk.crypto.ec,
   jdk.unsupported`. `java.net.http` (the JDK HTTP/WebSocket client), `java.sql` and
   `java.management` are absent. A plugin that uses them works in development and throws
   `NoClassDefFoundError` inside the production image.
6. **`OrderRequest` is not a public contract.** It is a sealed interface with 20 subtypes
   (`execution/OrderRequest.kt`), including engine-internal shapes such as `ArmedTrailingStop`,
   `SteppedStop`, `TimeTighteningStop`, `ScaleOut`, `TimeExit` and `Stack`. Every broker today
   has its own translator (`MT5OrderTranslator`, `BybitOrderTranslator`) over that full type.
   Exposing it would freeze engine internals into the plugin API, and adding a subtype would
   break every compiled plugin's exhaustive `when`.
7. **Defaults that vouch for everything.** Several `Broker` methods default to "trust the
   engine": `recoverPendingOrders` returns every id ("Brokers without venue truth vouch for every
   order"), `getOpenPositions` returns empty, `deals` returns empty. Correct for `PaperBroker`;
   dangerous for a third-party *live* adapter that simply forgets to override them — it would
   start trading on assumed state without error.
8. **No discovery.** No `ServiceLoader`, no `URLClassLoader` anywhere in `src/main`. CLI
   commands are a hand-written `when` in `cli/Main.kt`.

None of these is deep. Items 1–4 are refactors inside the monolith.

---

## 2. The first decision: where does an adapter run?

### Option A — in-process JAR (the original idea)

qkt loads `qkt-venue-rithmic.jar` from a plugins directory and calls it directly.

- ✅ Typed, low latency, one deployable, easy to test, fits Kotlin/JVM venues.
- ❌ **No independent choke point.** The kill switch lives in `mt5-gateway` today (the path-gated
  `/order`, `/modify_sl_tp`, `/close_position`… set), and the guardian enforces prop limits
  by flipping it. An in-process adapter talks to the exchange directly; the guardian has
  nothing to flip. You would be re-implementing the safety model inside the process it is
  supposed to protect against.
- ❌ Crash / leak / dependency-conflict blast radius is the whole trading daemon.
- ❌ No sandbox. The Java `SecurityManager` was deprecated for removal in JDK 17 and is
  permanently disabled from JDK 24; plugin code runs with qkt's full authority.
- ❌ JVM-only. Rithmic's and CQG's first-party SDKs, and many crypto SDKs, are C++/.NET/Python first.

### Option B — out-of-process gateway per venue (generalize mt5-gateway)

Each venue runs as its own service speaking a common HTTP/WS protocol; qkt has one generic client.

- ✅ Kill switch and guardian work exactly as today, for every venue.
- ✅ Crash isolation; any language; independently deployable and restartable.
- ✅ You already operate this pattern (mt5-gateway + guardrails + insights all consume it).
- ❌ A protocol to design, version and test. One more container per account.
- ❌ An extra network hop — irrelevant at your holding periods (1h–21d), relevant only for HFT.

### Recommendation — both, from one codebase

```
                 ┌──────────── same adapter JAR (qkt-venue-rithmic) ────────────┐
                 │                                                               │
   backtest / paper / data                                   live money
   ┌───────────────────────┐                     ┌────────────────────────────────────┐
   │ qkt (JVM)             │                     │ qkt-venued (JVM, one per account)  │
   │  └─ InProcessHost     │                     │  ├─ loads the same adapter JAR     │
   │      └─ adapter       │                     │  ├─ kill switch (path + symbol)    │
   └───────────────────────┘                     │  ├─ VGP v1 HTTP/WS server          │
                                                 │  └─ adapter → exchange             │
                                                 └───────────────▲────────────────────┘
                                                                 │ VGP v1
                                      ┌──────────────────────────┼─────────────────────┐
                                      │ qkt daemon (VgpBroker)   │ qkt-guardrails      │ qkt-insights
```

- The adapter is written **once**, against the plugin SPI (§5).
- **In-process host**: for backtest, paper and data plugins. Fast, typed, no ops cost.
- **`qkt-venued`**: a small generic JVM service that loads adapter JARs and exposes VGP v1 (§6).
  The kill switch, auth, idempotency, event journal and health are implemented **once** here,
  not per venue.
- **Live deploys refuse in-process adapters.** Enforced by certification level (§10), not by
  convention.
- **MT5 needs nothing new.** `mt5-gateway` already is a gateway; it becomes the reference VGP
  implementation later (Phase 6), or stays as-is behind the existing `MT5Broker`.
- **Non-JVM venues** (a Python `ib_insync` bridge, a C++ Rithmic bridge) can implement VGP directly
  without being a JAR at all. The protocol is the real contract; the JAR SPI is the convenient path.

This keeps what the JAR idea is good at (one artifact, typed, easy to add) and keeps what your
safety stack depends on (an independent choke point).

---

## 3. The mental model: four different things

Futures break the "one broker = one thing" intuition that MT5 taught.

| Concept | What it is | Examples | In qkt |
|---|---|---|---|
| **Protocol / connectivity** | The API you speak | MT5 (via gateway), Rithmic R\|Protocol, CQG WebAPI, TT, Tradovate REST/WS, IBKR TWS, Bybit v5, Binance, Deribit | **the plugin** (`type: rithmic`) |
| **Venue / clearing** | Who holds the account and clears | AMP, Optimus, NinjaTrader Clearing, a prop firm, Exness | **config** on a broker entry |
| **Account** | One login, one balance | AMP demo #123, The5ers HS 50k | **one broker entry**, one `qkt-venued`, one guardian |
| **Data vendor** | Where research data comes from | Databento, FirstRate, Norgate, Dukascopy, your hub | **a data plugin**, independent of execution |

So there is no `ampfuture` plugin. AMP offers several connectivity options; you pick one (say
Rithmic), and AMP becomes the `fcm:` / `system:` fields on a Rithmic broker entry. The same
Rithmic plugin then serves a Rithmic-routed futures prop account with no new code — exactly as
the one MT5 adapter already serves both Exness and The5ers.

---

## 4. The venue-neutral domain model (core changes)

These live in core, not in plugins. Plugins *report* facts; the engine *owns* meaning.

### 4.1 Instrument identity

Today a symbol is a string `PREFIX:SYMBOL` and `InstrumentMeta` is keyed by it. Futures need a
distinction between what a strategy names and what the exchange trades:

```kotlin
/** What a strategy writes. Stable across rolls. */
data class InstrumentRef(val venue: String, val root: String, val selector: ContractSelector)
//   AMP:ES@front   AMP:ES@next   AMP:ESZ5 (explicit)   BYBIT_LINEAR:BTCUSDT (perp, selector = None)

sealed interface ContractSelector {
    object None : ContractSelector                      // spot, CFD, perpetual
    data class Front(val offset: Int = 0) : ContractSelector   // @front, @next
    data class Explicit(val code: String) : ContractSelector   // ESZ5
}

/** What an order is sent on. Changes at every roll. */
data class VenueSymbol(val venue: String, val code: String)   // AMP / ESZ5
```

Every existing symbol maps to `ContractSelector.None`, so nothing about FX, metals, CFDs or
crypto changes. Positions, P&L and attribution key on `InstrumentRef` (the economic identity);
orders and venue reconciliation key on `VenueSymbol`. This is the structural fix for the
"one economic position spans many symbols" problem.

### 4.2 Instrument kind and metadata

```kotlin
enum class InstrumentKind { SPOT, CFD, PERPETUAL, DATED_FUTURE /*, OPTION later */ }
```

`InstrumentMeta` gains optional fields, all defaulted so every existing YAML and registry keeps
working:

| Field | Why |
|---|---|
| `kind` | Drives everything below; default `CFD` for MT5, `SPOT`/`PERPETUAL` for Bybit. |
| `quoteCurrency` (explicit) | Closes the `QuoteCurrencyGuard` hole: symbols with no currency tail (`ES`, `FDAX`, `FGBL`) currently pass unchecked, so EUR-quoted futures would book as USD. |
| `expiry`, `lastTradeDay`, `firstNoticeDay` | Expiry guard; no entry the strategy cannot hold to its horizon. |
| `settlement` (`CASH` / `PHYSICAL`) | Physical-delivery contracts must be flat before first notice. |
| `initialMargin`, `maintenanceMargin` (+ `asOf`) | The margin model; nothing in qkt reads margin today (verified: zero hits). |
| `priceLimitBand` | Limit up/down: a stop may not be executable. |
| `fundingIntervalHours` | Perpetual funding, booked as `CostKind.FUNDING`. |
| `volumeSemantics` (`TICK_COUNT` / `TRADED`) | Stops a volume rule fitted on CFD tick counts running unchanged on exchange volume. |

### 4.3 Data capabilities

Extend `MarketSourceCapability` (it already has `VOLUME`): `TRADES` (prints with aggressor
side), `DEPTH`, `OPEN_INTEREST`, `SETTLEMENT`, `CONTRACT_CHAIN`. Extend `Bar`/`Candle` with
optional `openInterest` and `settlement`, and the bar CSV with optional trailing columns
(the header already makes the format self-describing).

### 4.4 Core futures behaviour

Covered in depth in the 2026-09-18 conversation analysis ("what is missing for futures"); in
short, all venue-neutral:

- **Settlement marking** — `EquityTracker` marks continuously today; futures accounts move at the
  daily settlement price. Book the variation at settlement so the ledger matches the venue.
- **Margin model** — pre-trade "can I hold this", including revised margins.
- **Expiry guard** — refuse entries inside the roll window unless rolling is configured.
- **Roll engine** — atomic from the strategy's point of view: close old + open new under one
  `InstrumentRef` position, using a native calendar-spread order where the venue has one
  (`OrderTypeCapability.CALENDAR_SPREAD`, new), legged otherwise with explicit exposure checks.
- **Limit state** — "stop not executable" as a first-class risk state.

Deribit and Binance quarterly futures are `DATED_FUTURE` too, so crypto inherits all of it.

---

## 5. The plugin SPI

A new Gradle module, **`qkt-api`**, holds only what plugins compile against. It is semver'd and
locked with the Kotlin binary-compatibility-validator plugin, so an accidental break fails CI.

### 5.1 Entry point

```kotlin
package com.qkt.api

/** Discovered with java.util.ServiceLoader from each plugin JAR. */
interface VenuePlugin {
    val id: String                               // "rithmic" — the `type:` in config
    val spiVersion: Int                          // major version of qkt-api it was built for
    fun describe(): PluginDescriptor
    fun open(config: VenueConfig, host: HostServices): Venue
}

data class PluginDescriptor(
    val id: String,
    val version: String,
    val kinds: Set<InstrumentKind>,              // what it can trade
    val certification: Certification,           // DATA, PAPER, LIVE — §10
    val configSchema: List<ConfigField>,         // validated by the host before open()
    val requiredJdkModules: Set<String>,         // checked against the running runtime
)

data class ConfigField(val key: String, val type: FieldType, val required: Boolean, val secret: Boolean, val doc: String)
```

### 5.2 One `Venue` per account, thin per-session views

Mirrors what the daemon already does for MT5 (one shared `MT5Client` + `MT5ReadCache` per
gateway, one `MT5Broker` per session):

```kotlin
interface Venue : AutoCloseable {
    val name: String                             // "amp-demo" — the broker entry name
    val prefix: String                           // "AMP" — the DSL stream prefix
    fun instruments(): InstrumentProvider        // replaces filterIsInstance<MT5Broker>
    fun calendars(): CalendarProvider
    fun marketData(): MarketSource?              // null when data comes from elsewhere
    fun session(ctx: SessionContext): VenueSession
    fun truth(): VenueTruth?                     // required for LIVE, no defaults — §10
    fun onConnection(listener: (ConnectionState, String?) -> Unit)
}

interface InstrumentProvider {
    fun meta(symbol: VenueSymbol): InstrumentMeta?
    fun resolve(ref: InstrumentRef, at: java.time.Instant): VenueSymbol     // ES@front -> ESZ5
    fun chain(root: String): List<ContractSpec>                             // futures only
}
```

### 5.3 A small, stable order model

Plugins never see `OrderRequest`. The host lowers it, using the capabilities the plugin
declares — the same decomposition the engine does today, moved in front of a stable type:

```kotlin
sealed interface VenueOrder {
    val clientId: String; val symbol: VenueSymbol; val side: Side
    val quantity: java.math.BigDecimal; val tif: TimeInForce
    data class Market(...) : VenueOrder
    data class Limit(..., val price: BigDecimal) : VenueOrder
    data class Stop(..., val stopPrice: BigDecimal) : VenueOrder
    data class StopLimit(..., val stopPrice: BigDecimal, val limitPrice: BigDecimal) : VenueOrder
}

data class VenueSubmit(
    val order: VenueOrder,
    val protection: Protection? = null,   // attached SL/TP, only if BRACKET/POSITION_MODIFY declared
    val ocoGroup: String? = null,         // only if OCO declared
    val reduceOnly: Boolean = false,
    val closes: String? = null,           // position/ticket id on hedging venues
)

interface VenueSession {
    val capabilities: (VenueSymbol) -> Set<OrderTypeCapability>
    fun submit(s: VenueSubmit): SubmitAck      // async result via events
    fun cancel(clientId: String)
    fun modify(clientId: String, change: OrderModification): SubmitAck
    fun events(sink: (VenueEvent) -> Unit)     // accepted, filled, partial, cancelled, rejected
}
```

**Capability negotiation doubles as forward compatibility.** The host only ever sends shapes a
plugin declared. When qkt later adds a new engine order type, it is lowered onto the existing
four; an old plugin never receives something it cannot handle, and an undeclared shape is
rejected in the host rather than thrown inside the plugin.

Built-ins bridge in through an adapter so nothing is rewritten up front:
`class PluginBroker(session: VenueSession, lowering: OrderLowering) : Broker`.

### 5.4 Host services — plugins never reach out

```kotlin
interface HostServices {
    val clock: Clock
    fun logger(name: String): org.slf4j.Logger
    fun secret(ref: String): Secret                 // env:, file:, ${VAR} — §8
    fun executor(name: String): java.util.concurrent.ScheduledExecutorService  // host owns shutdown
    fun http(): okhttp3.OkHttpClient                // shared, pooled, TLS already in the runtime
    fun stateDir(): java.nio.file.Path              // per-plugin, per-account
    fun rateLimiter(key: String, perSecond: Double): RateLimiter
    fun metrics(): Metrics
}
```

Plugins do not call `System.getenv`, do not start raw threads and do not open their own
`HttpClient`. That makes them testable, keeps secrets in one place, and sidesteps the missing
`java.net.http` module entirely.

### 5.5 Data plugins

A separate, smaller SPI for research data (Databento, FirstRate imports, your hub):

```kotlin
interface DataPlugin { val id: String; fun open(config: VenueConfig, host: HostServices): MarketSource }
```

Reusing `MarketSource` means backtests, `qkt fetch`, completeness validation and the bar store
work with no new code paths. A data plugin can never be certified above `DATA`.

---

## 6. Venue Gateway Protocol (VGP v1)

The contract between qkt / guardrails / insights and any live venue. It is the mt5-gateway's
surface, generalized and versioned, plus the two things futures venues need that MT5 polling
never did: a push channel and idempotent submits.

| Method | Path | Notes |
|---|---|---|
| GET | `/v1/health` | `venue_connected`, `kill_switch`, `server_time`, protocol + adapter versions |
| GET | `/v1/account` | balance, equity, currency; margin used/available; futures initial/maintenance |
| GET | `/v1/instruments[/{code}]` | normalized `InstrumentMeta` superset incl. `kind`, `expiry`, margins |
| GET | `/v1/contracts/{root}` | futures chain |
| GET | `/v1/positions` | per ticket (hedging) or per contract (netting), with accounting mode |
| GET | `/v1/orders` | working orders |
| POST | `/v1/orders` | **idempotent on `client_order_id`** — a retried submit must never double-fill |
| PATCH / DELETE | `/v1/orders/{id}` | modify / cancel |
| POST | `/v1/positions/close` | by ticket, or by symbol + optional quantity |
| GET | `/v1/deals?from=&to=` | executions with typed costs |
| GET | `/v1/stream?since=<seq>` | WebSocket: orders, fills, positions, account, quotes — every event carries a **sequence number**; reconnect resumes from `since`, so a fill is never lost across a disconnect |
| POST | `/v1/kill` | `{"scope":"all"}` or `{"scope":"symbols","symbols":["ES","NQ"]}` |
| POST | `/v1/kill/release` | same scopes |
| GET | `/v1/bars`, `/v1/ticks` | historical, when the venue has them |

Semantics that must be written into the spec, not left to implementations:

- **Kill switch gates paths that add or change risk**, as mt5-gateway's `_KILL_GATED` does today —
  and **scoped kill is native**. The weekend work showed the need: v0.5.0 had to emulate
  "everything except BTCUSD" in the guardian by listing positions and closing them one by one.
  VGP makes that one call.
- **Closing positions must stay possible under an engaged switch** through an explicit
  flatten path; the weekend fix had to release a guardian-held switch before closing because
  `/close_position` is gated. VGP should separate "flatten" (always allowed) from "open or
  enlarge" (gated).
- **Timestamps are UTC epoch-ms on the wire.** The raw MT5 broker-time offset
  (`mt5-gateway-server-offset`) is a gateway concern, never a client one.
- **Auth:** Bearer token, as the gateway already does.
- **Compatibility:** `qkt-venued` also serves the legacy paths (`/account`, `/health`, `/kill`,
  `/kill/release`, `/get_positions`, `/close_position`) so today's guardrails `GatewayClient`
  works against a futures venue on day one. A `protocol: vgp1` option in guardrails comes later.

---

## 7. Loading, packaging and the CLI

### 7.1 Modules

```
qkt-api              the SPI (§5) — semver, binary-compat locked
qkt-core             engine: DSL, pipeline, risk, P&L, backtest
qkt-cli              commands, daemon, plugin manager
qkt-venued           the gateway host (§2, §6)
qkt-plugin-tck       conformance kit (§10)
adapters/bybit       first adapter moved out (dogfood)
adapters/mt5         stays in-tree and built-in until VGP is proven (§11)
```

Moving an existing adapter out first is the real test of the API: if Bybit cannot be written
against `qkt-api` alone, no third party can.

### 7.2 Discovery and isolation

- One `URLClassLoader` per plugin JAR, discovered with `ServiceLoader<VenuePlugin>` /
  `ServiceLoader<DataPlugin>`.
- **Parent-first** for `com.qkt.api.*`, `kotlin.*`, `org.slf4j.*`, `okhttp3.*` (shared types must
  come from one loader, otherwise you get the classic `X cannot be cast to X`). **Child-first**
  for everything else, so two plugins can carry different protobuf or Netty versions.
- Plugins compile against a Kotlin version ≤ the host's and do not bundle the stdlib.
- JPMS module layers are not needed; qkt runs on the classpath and should stay there.

### 7.3 The JAR

A shaded JAR containing:

- `META-INF/services/com.qkt.api.VenuePlugin`
- `qkt-plugin.json`: `id`, `version`, `spiVersion`, `requiredJdkModules`, `certification`, SHA-256
  of the payload, optional signature.

### 7.4 The runtime module gap

Two fixes, both cheap:

1. Add `java.net.http` to the jlink set (Dockerfile and `jlinkRuntime`) — about 1 MB. SDKs use it a lot.
2. `qkt plugin verify` checks every `requiredJdkModules` entry with
   `ModuleLayer.boot().findModule(name)` against the runtime that will actually run it, and
   refuses to install on a miss. This turns a production-only `NoClassDefFoundError` into an
   install-time error.

### 7.5 Pinning and deployment

- `qkt.plugins.lock` records `id`, `version`, `sha256`, source. The daemon refuses to start if an
  installed JAR does not match the lock. Same idea as your GHCR image pinning.
- **Docker: plugins are baked into a derived image, never downloaded at container start.**
  `FROM ghcr.io/elitekaycy/qkt:vX` + `COPY plugins/ /opt/qkt/plugins/`, tagged and pinned like
  every other image in the fleet. The same applies to `qkt-venued` images per adapter.

### 7.6 CLI

Your `qkt add --broker ampfuture` splits into "install code" and "add an account", because one
plugin serves many accounts:

```bash
qkt plugin install rithmic@0.1.0          # fetch, verify sha + modules, write lock
qkt plugin install ./qkt-venue-rithmic-0.1.0.jar
qkt plugin list | verify | remove

qkt brokers add amp-demo --plugin rithmic --prefix AMP   # writes the config entry, asks for secret *references*
qkt brokers check amp-demo                # login, account list, instruments, clock skew, kill-switch reachability
qkt brokers certify amp-demo --level paper # runs the TCK live scenarios under the account lock
```

`qkt brokers` already exists (`cli/BrokersCommand.kt`, MT5 listing) and keeps the current
vocabulary.

---

## 8. Configuration and credentials

### 8.1 One entry shape for every venue

```yaml
brokers:
  exness-demo:                       # unchanged — built-in
    type: mt5
    extends: exness
    gateway_url: http://127.0.0.1:5001
    magic: 20001

  amp-demo:
    type: rithmic                    # plugin id
    prefix: AMP                      # DSL: AMP:MES@front
    host: venued                     # venued (live-capable) | in-process (paper/data only)
    gateway_url: http://venued-amp:5101
    environment: test                # test | live
    system: "Rithmic Paper Trading"
    fcm: AMP
    account: ${RITHMIC_ACCOUNT}
    credentials:
      user: env:RITHMIC_USER
      password: file:/run/secrets/rithmic_password
    calendars:
      - ["*", "fx pause 17:00-18:00 America/New_York"]
    roll:
      policy: volume_crossover       # or days_before_expiry: 8
    instruments:
      MES: { commissionPerLot: 1.04 }

  binance-futures:
    type: binance
    prefix: BINANCE_UM
    host: venued
    gateway_url: http://venued-binance:5102
    credentials:
      api_key: env:BINANCE_API_KEY
      api_secret: env:BINANCE_API_SECRET
```

The plugin's `configSchema` is validated before `open()`; an unknown key, a missing required field
or a secret written inline is an error at load, not a surprise at 20:00 UTC.

### 8.2 One resolver in core

Resolution order, highest first:

1. `QKT_BROKER_<NAME>_<FIELD>` — the convention `MT5BrokerProfileLoader` already implements,
   kept so nothing existing changes.
2. The config value, which may be a literal (non-secrets only) or a reference:
   `${VAR}` (existing), `env:VAR`, `file:/path` (Docker/Kubernetes secrets).

Secrets are a type, not a string:

```kotlin
@JvmInline value class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "Secret(***)"
}
```

A `Secret` never reaches logs, state files, insights, `qkt brokers` output or promotion records.
Schema fields marked `secret: true` **must** be references; a literal is rejected. Bybit keeps
reading `BYBIT_API_KEY` as a fallback so existing deployments do not break.

### 8.3 Different venues, different logins

The resolver supplies credentials; the **plugin owns the session lifecycle** and reports it with
the existing `ConnectionChanged` event:

| Pattern | Examples | Lifecycle |
|---|---|---|
| API key + HMAC signing | Bybit, Binance, OKX | stateless; clock skew matters (`recvWindow`) |
| User/password → session token, refresh | Rithmic, Tradovate, most futures props | login, renew before expiry, re-login on reject |
| Local terminal login | IBKR TWS / IB Gateway, MT5 | the human logs in once (often with 2FA and a periodic restart); the adapter only connects to a socket |
| Wallet signature | Hyperliquid (EIP-712) | the key *is* the account — treat as highest-sensitivity secret |

`qkt brokers check` exercises the login path so a bad credential fails at setup, not at the
first signal.

---

## 9. Order and trade pipeline, end to end

```
.qkt strategy
  └─ DSL compile ─► OrderRequest (rich: Bracket, OCO, Trailing, ScaleOut, Stack…)
       └─ OrderManager + risk (venue-neutral; reads InstrumentMeta incl. margin/expiry/limits)
            └─ resolve InstrumentRef → VenueSymbol (ES@front → ESZ5)
                 └─ lower by capabilities(VenueSymbol) ─► VenueSubmit (4 order shapes ± protection/OCO)
                      ├─ in-process: VenueSession → SDK → exchange          (paper, data)
                      └─ VgpBroker ─► qkt-venued ─► kill-switch gate ─► adapter ─► exchange   (live)
  ◄─ VenueEvent stream (seq-numbered) ─► BrokerEvent (Accepted/Filled/Partial/Cancelled/Rejected)
       └─ positions keyed by InstrumentRef, legs by VenueSymbol + ticket
            └─ P&L, typed costs (COMMISSION/EXCHANGE_FEE/SWAP/FUNDING), settlement variation
                 └─ risk, halts, insights, promotion evidence

Startup / reconnect (LIVE only, no defaults):
  VenueTruth.openPositions / workingOrders / deals / account
    └─ LegBookReconciler + recoverPendingOrders + watchBookedLegs  (existing machinery)
```

Normalization rules that make the mapping global:

- **Identity:** qkt client order id is the idempotency key end to end; the venue order id and
  position ticket are recorded, never assumed stable across venues.
- **Quantities:** always in venue units (`volumeStep`), quantized **down**, rejected below
  `volumeMin` — today's behaviour (`PaperBroker.kt:159`, `ExecutionBehavior.kt:65`,
  `ActionCompiler.kt:126`).
- **Prices:** rounded to tick size by the host, never by the adapter.
- **Position mode:** declared per symbol (`PositionAccountingMode`); reconciliation follows it.
- **Protection:** attached (MT5 `POSITION_MODIFY`), native bracket (futures OCO), or engine-held,
  chosen by capability — the engine already decides this per broker.
- **Time:** UTC epoch-ms everywhere past the adapter.
- **Costs:** always typed `VenueCost`; an adapter that cannot report a cost says so, rather than
  reporting zero.

---

## 10. What makes it sound

### 10.1 Certification levels, enforced at deploy

| Level | Requires | Allowed |
|---|---|---|
| `DATA` | `MarketSource` passes data TCK | research, backtest, `qkt fetch` |
| `PAPER` | + `VenueSession`, order-lifecycle TCK against the venue's sandbox | paper / demo |
| `LIVE` | + `VenueTruth` implemented (no defaults), restart/reconnect/idempotency TCK, served through `qkt-venued` with a reachable kill switch, a recorded `qkt brokers certify --level live` run on a demo account | real money |

`qkt deploy` in production mode refuses a broker whose plugin level is below `LIVE`. This is the
direct answer to §1 item 7: a live adapter cannot inherit "vouch for everything" defaults,
because the `VenueTruth` interface it must implement has none.

### 10.2 Conformance test kit (`qkt-plugin-tck`)

A JUnit suite any adapter runs, built from failures you have **already hit live** rather than
from imagination:

- submit → accept → fill; reject; cancel; cancel-after-fill race
- partial fills and cumulative quantity (rare on CFDs, normal on exchanges)
- duplicate submit with the same `client_order_id` → exactly one order
- process killed mid-order → restart → no phantom or duplicate position
  (`qkt-restart-over-positions-fixes`, #1146)
- venue-side stop fires while the engine closes → one close, resolved from deals
  (`venue-stop-engine-stop-race`)
- restart inside the entry bar after a stop-flatten (`qkt-stop-flatten-rule-edges`)
- several children entering inside one risk sample (`qkt-book-cap-race`)
- the 20-scenario position matrix (`qkt-position-matrix-audit`) and the 40-case portfolio matrix
- disconnect → reconnect from `since=<seq>` → no lost fill
- kill switch: gated paths rejected, flatten allowed, scoped kill leaves other symbols tradeable
- clock skew, stale feed, scheduled break

The existing `BacktestLiveParityTest` and `MT5GoldenVerifierTest` generalize into a per-venue
golden fill check.

### 10.3 Provenance

Promotion records the plugin `id@version` and SHA-256 next to `strategyHash`, and the daemon
refuses a promoted live strategy when the installed plugin differs. Today's `strategyHash`
already misses changes to imported portfolio children (`hg20k-live-book-deploy-facts`); do not
repeat that blind spot with adapters, which change behaviour far more than a sizing line does.

### 10.4 Fail closed

- Unknown plugin `type:` → config error, never a fallback.
- Plugin fails to load → the brokers that use it are unavailable and their strategies refuse to
  deploy; the rest of the daemon keeps running.
- Missing instrument meta → hard error (existing Phase 30 rule).
- Undeclared order shape → rejected in the host.
- `VenueTruth` read fails at startup → no trading (existing rule for MT5).

---

## 11. Feasibility by venue

External facts below are from general knowledge, **not verified in this session** — confirm each
before committing.

| Venue | Connectivity | Auth | Fit | Notes to verify |
|---|---|---|---|---|
| **Tradovate** | REST + WebSocket, JSON | user/password → access token, renewed | good first futures adapter: simplest protocol | API access may need a funded account and/or a paid add-on |
| **Rithmic** (AMP, many futures props) | R\|Protocol: WebSocket + protobuf | user/password, system + gateway selection | the one that unlocks most FCMs and futures props | conformance testing with Rithmic before production is typically required |
| **CQG** | WebAPI: WebSocket + protobuf | user/password | similar to Rithmic | licensing and fees per FCM |
| **IBKR** | TWS API socket to a locally logged-in TWS / IB Gateway | human login, 2FA, periodic restart | broad asset coverage | API jar distributed by IBKR (do not bundle it — the plugin model helps here); limited historical depth |
| **ProjectX / TopstepX** | REST + realtime hub | API key | relevant to your prop-payout thesis | availability and terms change; confirm current API access |
| **Binance / OKX** | REST + WS | API key + HMAC | close to the existing Bybit code | both have dated quarterlies → `DATED_FUTURE` |
| **Deribit** | JSON-RPC over WS | API key | dated BTC/ETH futures + options | good second test of the futures core |
| **Hyperliquid** | REST + WS | wallet signature (EIP-712) | perps | key custody is the whole security model |
| **Databento** (data) | HTTP historical, live stream | API key | first data plugin | check for a JVM client; the HTTP API is language-neutral |

---

## 12. Roadmap

Sizes are relative (S ≈ days, M ≈ 1–2 weeks, L ≈ several weeks). They are guesses.

| Phase | Scope | Size | Value on its own |
|---|---|---|---|
| **1 — Seams in the monolith** | `Broker.instruments()` replaces the three `filterIsInstance<MT5Broker>` sites; a `VenueRegistry` fed by `type:` replaces hand assembly in `DaemonCommand` / `MarketSourceFactory` / `BotSessionCommand`; unified credential resolver + `Secret`; nested YAML for broker entries; optional `InstrumentMeta` fields (`kind`, explicit `quoteCurrency`) | M | Removes MT5 coupling, fixes the quote-currency hole, one credential path. **Zero behaviour change; worth doing even without plugins.** |
| **2 — `qkt-api` + loader** | module split; `VenuePlugin`/`DataPlugin`; `ServiceLoader` + isolated class loaders; `qkt plugin` CLI; lockfile; add `java.net.http` to jlink; move **Bybit** out as the dogfood adapter; binary-compat validator in CI | M–L | Proves the API on real code. |
| **3 — Futures research path** | Databento or file-import data plugin; exchange-futures execution simulation in backtest (whole contracts, tick rounding, per-contract commission, no swap, limit bands); CME calendar in backtest (`BacktestContext.defaultCalendars()` is hardcoded today); expiry guard | M | Tier 0 on real futures data, properly. |
| **4 — VGP + first live-capable futures venue** | VGP v1 spec; `qkt-venued`; `VgpBroker`; kill switch incl. scoped kill; `qkt-plugin-tck`; first adapter (Tradovate or Rithmic) on demo; guardrails pointed at it via legacy paths; certify `PAPER` then `LIVE` | L | First real futures account, with the same guardian protection hg20k has. |
| **5 — Futures depth** | settlement marking, margin model, roll engine + `@front` refs, calendar-spread capability, OI/settlement data; Deribit or Binance quarterlies as the second consumer | L | Multi-month futures holds; dated crypto futures. |
| **6 — Converge MT5 (optional)** | `mt5-gateway` speaks VGP v1; `MT5Broker` becomes a VGP client or stays | L | Only if one code path is worth the risk to live MT5 accounts. Do not do this early. |

MT5 is deliberately last. It is ~6.6k lines of hard-won venue truth (ticket and comment matching,
OCO recovery, server-time handling) running real money; the plugin system should grow beside it
(strangler pattern), not start by rewriting it.

---

## 13. Risks and open questions

- **API surface creep.** The biggest long-term risk is leaking engine types into `qkt-api`. Keep
  it to §5; anything else is host-internal.
- **Two hosts, one behaviour.** In-process and `qkt-venued` must run the same adapter identically;
  the TCK must run against both.
- **Event ordering across the network.** Sequence numbers solve loss; the engine must also tolerate
  a fill arriving before the accept.
- **Roll semantics are a product decision,** not only engineering: legged vs spread, who owns the
  roll (engine vs strategy), what happens to stops and trailing state across a roll.
- **Operational load.** One `qkt-venued` + one guardian per futures account. Fine at your
  scale; worth a compose template per venue.
- **Licensing.** Some vendor SDKs cannot be redistributed; the plugin model (user installs the
  vendor JAR) handles it, but check each licence.
- **Does the edge exist?** None of this is worth building before a futures backtest (Tier 0)
  says the strategies survive real futures data and costs.

---

## Files of record

`broker/Broker.kt`, `broker/CompositeBroker.kt`, `broker/OrderTypeCapability.kt`,
`broker/BrokerState.kt`, `app/BrokerFactory.kt`, `app/LiveSession.kt` (636, 1004, 1355),
`cli/DaemonCommand.kt` (~270–350), `cli/MarketSourceFactory.kt`, `cli/bot/BotSessionCommand.kt`
(186), `broker/mt5/MT5BrokerProfileLoader.kt`, `broker/bybit/BybitClient.kt` (119–139),
`marketdata/source/MarketSource.kt`, `marketdata/source/MarketSourceCapability.kt`,
`instrument/InstrumentRegistry.kt`, `instrument/InstrumentMeta.kt`,
`instrument/QuoteCurrencyGuard.kt`, `execution/OrderRequest.kt`, `events/BrokerEvent.kt`,
`accounting/Accounting.kt`, `cli/BacktestContext.kt` (1122), `cli/Main.kt`, `build.gradle.kts`,
`Dockerfile`.
