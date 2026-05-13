# Phase 28 — Multi-source market data

> Today, every live qkt strategy reads ticks and bars from TradingView. Phase 28 lets a single qkt daemon route per-symbol-prefix to multiple market data backends — MT5 gateways (already a broker), Bybit public WebSocket (currently unused for data), with TradingView remaining the fallback. **No DSL change.** All routing happens at daemon boot from existing broker profile config.

## Goal

Make this strategy still work, untouched:

```qkt
STREAMS {
  gold = EXNESS:XAUUSD EVERY 5m
  btc  = BYBIT_SPOT:BTCUSDT EVERY 1m
  spy  = OANDA:SPY EVERY 5m
}
```

…but route each stream to a different source automatically:

- `EXNESS:XAUUSD` → MT5 gateway at the Exness broker profile's `gateway_url` (current `mt5-gateway` HTTP, polled).
- `BYBIT_SPOT:BTCUSDT` → Bybit public spot WebSocket (`wss://stream.bybit.com/v5/public/spot`), `publicTrade.BTCUSDT` + `tickers.BTCUSDT`.
- `OANDA:SPY` → TradingView (existing path).

## Motivation

Three forcing functions:

1. **Parity validated.** The harness shipped today (`tools/parity/`, commits `7c50fef` + `3f55288`) demonstrated that MT5 ticks for XAUUSD agree with TV to within the broker's bid-ask spread (mean delta $0.21 over 60s; same for M5 bars). **But MT5 ticks arrive ~89ms ahead of TV on average and ~50% more frequently** (90 ticks/min vs 61). For execution-sensitive strategies (anything triggering on price-level breaks), the broker's own feed is authoritative.
2. **TradingView is not under our control.** The QuoteSession bug fix in `3f55288` was triggered by anonymous-token rate limiting. For prod, the source we execute on (MT5) should also be the source we observe.
3. **Bybit-WS data isn't wired in at all.** qkt has full Bybit broker integration (`com.qkt.broker.bybit`) for execution but **only TV** as a MarketSource. Strategies running on Bybit today are reading TV ticks and submitting orders to Bybit — a feed/execution mismatch.

## Scope

### In scope

**A. `Mt5MarketSource`** (promotion of throwaway `Mt5DataClient`/`Mt5TickClient` from `tools/parity/`):

- Implements `MarketSource` with `LIVE_TICKS` and `BARS` capabilities.
- `liveTicks(symbols)` returns a `LiveTickFeed` backed by an HTTP poller (one thread per source, polling each symbol's `/symbol_info_tick/{symbol}` round-robin, deduping by `time_msc`). Default poll interval: 50ms.
- `bars(symbol, window, range)` calls `/fetch_data_range?symbol=...&timeframe=...&start=...&end=...`.
- `supports(symbol)` accepts the broker profile's name as the prefix: `EXNESS:` for the `exness` profile, `LATCH:` for `latch`, etc.
- Reads its config from the existing `MT5BrokerProfile` (no new schema). The same profile object that drives `MT5Broker` for execution also drives `Mt5MarketSource` for data.

**B. `BybitSpotMarketSource` and `BybitLinearMarketSource`** (new, no auth required for public endpoints):

- Implements `MarketSource` with `LIVE_TICKS` and `BARS`.
- `liveTicks(symbols)` opens one Bybit public WebSocket (`/v5/public/spot` or `/v5/public/linear`), subscribes to `publicTrade.<symbol>` (last trades) and `tickers.<symbol>` (best bid/ask). Emits `Tick(price, bid, ask, volume, timestamp)`.
- `bars(symbol, window, range)` calls REST `/v5/market/kline?category=spot|linear&symbol=...&interval=...&limit=...`.
- `supports(symbol)` accepts the source's prefix: `BYBIT_SPOT:` or `BYBIT_PERP:`.
- Reconnect: backoff on disconnect, replay subscribe commands on reconnect. Reuse the lesson from the `TradingViewQuoteSession.hasDisconnected` pattern fixed today.

**C. `CompositeMarketSource` wiring at daemon boot** (`DaemonCommand.startDaemon`, `RunCommand`):

- Build the source registry by reading already-loaded broker profiles + adding TV as fallback.
- For each MT5 profile → `Mt5MarketSource` with prefix derived from `profile.name.uppercase()`.
- For Bybit, two source instances per registered Bybit broker (one spot, one linear) → `BybitSpotMarketSource` / `BybitLinearMarketSource` with prefixes `BYBIT_SPOT:` and `BYBIT_PERP:`.
- TV is registered as the fallback (`CompositeMarketSource.fallback`).
- `sourceFactory: (List<String>) -> MarketSource` returns the composite.

**D. Symbol prefix mapping table** (codified in source):

| Prefix | Source | Wire symbol |
| --- | --- | --- |
| `EXNESS:` | `Mt5MarketSource(profile=exness)` | strip prefix + `symbolSuffix` (e.g., `XAUUSD` → `XAUUSDm`) |
| `LATCH:` | `Mt5MarketSource(profile=latch)` | strip prefix + `symbolSuffix` (typically empty) |
| `BYBIT_SPOT:` | `BybitSpotMarketSource` | strip prefix (`BTCUSDT` → `BTCUSDT`) |
| `BYBIT_PERP:` | `BybitLinearMarketSource` | strip prefix |
| anything else | TradingView (fallback) | bare symbol passed through |

### Out of scope (explicitly YAGNI)

- **No DSL change.** No `FROM` keyword. No bare-symbol auto-routing.
- **No new config schema.** Broker profiles already encode everything. No `sources:` block.
- **No asset-class abstraction.** No `forex`/`crypto` mapping.
- **No `Bybit private` WS for data.** Account-scoped data (positions, executions) flows through the existing broker code path. Phase 28 is public market data only.
- **No historical tick stream from Bybit.** `MarketSourceCapability.TICKS` (range-based historical ticks) is not implemented. Just `LIVE_TICKS` and `BARS`.
- **No multi-strategy WS deduplication.** Each strategy gets its own connection (matches today's TV behavior). Connection pooling is a separate phase.

## Architecture

### Component diagram

```
                    ┌──────────────────────────┐
                    │   DaemonCommand (boot)   │
                    └───────────┬──────────────┘
                                │ reads
                                ▼
                    ┌──────────────────────────┐
                    │   Config + broker profiles│
                    └───────────┬──────────────┘
                                │ constructs
                                ▼
                    ┌──────────────────────────┐
                    │  CompositeMarketSource   │
                    │      (fallback: TV)      │
                    └─────────┬────────────────┘
                              │ routes by prefix
              ┌───────────────┼─────────────┬──────────────┐
              ▼               ▼             ▼              ▼
        Mt5Market...   Mt5Market...   BybitSpot...  BybitLinear...
        (exness)        (latch)        Market...      Market...
              │               │             │              │
              ▼               ▼             ▼              ▼
         HTTP poll       HTTP poll       WS+REST        WS+REST
        :5002/...      :5003/...    stream.bybit/spot  /linear
```

### Per-source thread model

| Source | Lifetime | Threads |
| --- | --- | --- |
| `Mt5MarketSource.liveTicks(symbols)` | Per strategy that subscribes | 1 daemon thread per source, round-robin polling each subscribed symbol |
| `BybitSpotMarketSource.liveTicks(symbols)` | Per strategy that subscribes | OkHttp WebSocket internal thread (callback-driven, no extra thread) |
| TV (existing) | Per strategy | OkHttp WebSocket internal thread |

The `LiveTickFeed` wrapper (already in `marketdata/live/`) handles back-pressure: a bounded queue (default 10,000 ticks) between the producer thread and the strategy thread. Overflow drops oldest with a counter for observability (`droppedTicks`).

### Bars: synchronous fetch

`MarketSource.bars(symbol, window, range)` returns `Sequence<Candle>`. Implementation:

- **MT5**: one HTTP call to `/fetch_data_range?symbol=...&timeframe=...&start=ISO&end=ISO`. Blocks until response.
- **Bybit**: one or more REST `/v5/market/kline` calls (pagination if range exceeds `limit=1000`). Sequence is lazy — yields as pages stream in.
- **TV** (existing): one chart session over the existing WS.

### Reconnect & failure handling

| Source | Failure mode | Recovery |
| --- | --- | --- |
| MT5 poller | HTTP 5xx, connection refused, timeout | Each poll is independent; logs `[mt5-poll] fetch failed: ...` once per failure, continues polling. No backoff (gateway is local). |
| Bybit WS | WS disconnect | Reconnect with exponential backoff (1s, 2s, 4s, 8s, capped at 30s). On reconnect, replay subscribe commands. Reuse `hasDisconnected` flag pattern from `TradingViewQuoteSession`. |
| Bybit REST | HTTP 5xx | Retry once with 1s delay; surface as `IOException` on second failure. |
| TV (unchanged) | WS disconnect | Existing `ReconnectSupervisor` — but only wired for the Bybit broker side today (Phase R-audit yellow item). Phase 28 does not address that. |

### Symbol mapping detail (the trick)

The existing `MT5Symbol`/`MT5BrokerProfile.symbolPolicy` already handles `BUY EXNESS:XAUUSD` → submit MT5 order for `XAUUSDm`. The same translation is needed on the data side. Resolve via a shared helper:

```kotlin
// com.qkt.broker.mt5.MT5SymbolPolicy (already exists)
fun toWireSymbol(qktSymbol: String): String   // "EXNESS:XAUUSD" → "XAUUSDm"
fun fromWireSymbol(wireSymbol: String): String // "XAUUSDm" → "EXNESS:XAUUSD"
```

`Mt5MarketSource` uses the same `symbolPolicy` instance as `MT5Broker`. This guarantees a single source of truth for the mapping (a strategy that buys `EXNESS:XAUUSD` and reads `EXNESS:XAUUSD` ticks must agree on what wire symbol that is).

## Public API

### Unchanged

- `com.qkt.marketdata.source.MarketSource` interface — no changes.
- `com.qkt.marketdata.source.MarketSourceCapability` — no new variants needed.
- `com.qkt.marketdata.source.CompositeMarketSource` — no API change, just used differently at boot.
- DSL — no changes.
- Strategy code — no changes. Strategies that today address `EXNESS:XAUUSD` keep working.

### New

```kotlin
// com.qkt.marketdata.live.mt5.Mt5MarketSource
class Mt5MarketSource(
    private val profile: MT5BrokerProfile,
    private val http: OkHttpClient = defaultHttp(),
    private val clock: Clock = SystemClock(),
    private val pollIntervalMs: Long = 50L,
) : MarketSource, AutoCloseable

// com.qkt.marketdata.live.bybit.BybitSpotMarketSource
class BybitSpotMarketSource(
    private val wsUrl: String = "wss://stream.bybit.com/v5/public/spot",
    private val restBase: String = "https://api.bybit.com",
    private val http: OkHttpClient = defaultHttp(),
    private val clock: Clock = SystemClock(),
) : MarketSource, AutoCloseable

// com.qkt.marketdata.live.bybit.BybitLinearMarketSource
class BybitLinearMarketSource(
    private val wsUrl: String = "wss://stream.bybit.com/v5/public/linear",
    private val restBase: String = "https://api.bybit.com",
    private val http: OkHttpClient = defaultHttp(),
    private val clock: Clock = SystemClock(),
) : MarketSource, AutoCloseable
```

Internal helper classes (in same packages):

- `Mt5TickPoller` — daemon thread, polls round-robin, emits to `LiveTickSource` queue.
- `Mt5BarFetcher` — synchronous range fetch with pagination.
- `BybitPublicWsClient` — WS client with reconnect supervisor.
- `BybitKlineClient` — REST kline pagination.

### Wiring change

```kotlin
// DaemonCommand.startDaemon — currently:
val sourceFactory: (List<String>) -> MarketSource = ::defaultTradingViewSource

// becomes:
val sourceFactory: (List<String>) -> MarketSource = {
    buildCompositeSource(mt5Profiles, bybitProfiles)
}

private fun buildCompositeSource(
    mt5Profiles: List<MT5BrokerProfile>,
    bybitProfiles: List<BybitBrokerProfile>,
): MarketSource {
    val routes = mutableListOf<Pair<SymbolPattern, MarketSource>>()
    for (p in mt5Profiles) {
        routes.add(SymbolPattern.prefix("${p.name.uppercase()}:") to Mt5MarketSource(p))
    }
    if (bybitProfiles.any { it.category == SPOT }) {
        routes.add(SymbolPattern.prefix("BYBIT_SPOT:") to BybitSpotMarketSource())
    }
    if (bybitProfiles.any { it.category == LINEAR }) {
        routes.add(SymbolPattern.prefix("BYBIT_PERP:") to BybitLinearMarketSource())
    }
    return CompositeMarketSource(routes = routes, fallback = TradingViewMarketSource.connect())
}
```

(Note: Bybit data sources are constructed once per daemon, not per strategy. TV is also created once. MT5 sources are created once per profile.)

## Configuration

**Zero new config schema.** The existing `qkt.config.yaml` already has everything:

```yaml
brokers:
  exness:
    type: mt5
    gateway_url: ${MT5_HEDGE_URL:-http://localhost:5002}
    symbol_suffix: m
    magic: 123456
  latch:
    type: mt5
    gateway_url: http://localhost:5003
    symbol_suffix: ""
    magic: 123457
  bybit_spot:
    type: bybit
    category: spot
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
  bybit_perp:
    type: bybit
    category: linear
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
```

The presence of an MT5 profile named `exness` automatically registers `EXNESS:` → `Mt5MarketSource(profile=exness)`. The presence of any Bybit profile with `category: spot` registers `BYBIT_SPOT:` → `BybitSpotMarketSource`. Same for `linear` / `BYBIT_PERP:`.

**Important:** the `bybit_spot` and `bybit_perp` profile entries are needed for **execution** anyway (the broker side). Phase 28 reuses them for the data side too — single source of truth.

## Testing strategy

### Mt5MarketSource

- **MockWebServer test**: feed canned `/symbol_info_tick` and `/fetch_data_pos` responses; assert tick emission, dedup by `time_msc`, bar parsing.
- **Symbol policy test**: `Mt5MarketSource(profile=exness with suffix=m)` translates `EXNESS:XAUUSD` ↔ `XAUUSDm`.
- **Polling cadence**: 50ms default; one HTTP call per poll per symbol; verifies dedup when broker time hasn't advanced.

### BybitSpotMarketSource / BybitLinearMarketSource

- **Fixture-based WS test** (like `TradingViewQuoteSessionTest`): replay canned `publicTrade.BTCUSDT` and `tickers.BTCUSDT` frames; assert `Tick` emission with bid/ask/price/volume.
- **REST kline test**: MockWebServer canned response; assert `Candle` parsing.
- **Reconnect test**: simulate disconnect; assert subscribe commands re-issued.
- **No double-subscribe on initial connect** (regression test for the bug fixed in `3f55288`).

### Composite wiring

- **End-to-end**: with three profiles loaded, request `liveTicks` for `["EXNESS:XAUUSD", "BYBIT_SPOT:BTCUSDT", "OANDA:SPY"]` — assert `FanInTickFeed` yields ticks from all three.
- **Fallback test**: `liveTicks(["UNKNOWN:FOO"])` falls through to TV; if TV rejects it (regex doesn't match `EXCHANGE:SYMBOL`), the error surfaces cleanly.

### Non-tests

- **No live-feed e2e in CI** (already excluded via `excludeTags("e2e-live")` in the test config).
- **Live smoke test stays manual**, documented in `docs/parity/README.md` style.

## Migration & backwards compatibility

- **Existing strategies on TV-only deployments**: zero change. If `mt5Profiles` and `bybitProfiles` are both empty at boot, `CompositeMarketSource` collapses to just the TV fallback. No behavior change.
- **Existing strategies using `EXNESS:XAUUSD` (hedge-straddle.qkt)**: today they read TV bars/ticks (fallthrough — TV's regex `EXCHANGE:SYMBOL` accepts the symbol). After Phase 28, if an `exness` profile is configured, they read MT5 data. **This IS a behavior change for live deployments.** Operators who want to stay on TV for these symbols must either remove the `exness` profile or change the strategy to use `OANDA:XAUUSD`.

  **Mitigation:** Phase 28 changelog must call this out prominently. Operators are explicitly opting in to MT5 data by having an `exness` profile.

- **No deprecation window.** Per qkt §7: "No backwards compatibility cruft."

## Open questions

None currently. All material decisions locked:

1. ✅ Bybit category: both spot + linear (two source instances).
2. ✅ Symbol routing: broker-name prefix (`EXNESS:`, `LATCH:`, `BYBIT_SPOT:`, `BYBIT_PERP:`).
3. ✅ No DSL change, no config schema change.
4. ✅ MT5 via HTTP polling (existing gateway shape).
5. ✅ Bybit via public WS + REST (no auth needed for public data).

## Estimated effort

| Component | LOC | Days |
| --- | --- | --- |
| `Mt5MarketSource` + `Mt5TickPoller` + `Mt5BarFetcher` | ~250 | 0.5 |
| `BybitPublicWsClient` + symbol/frame translators | ~300 | 1 |
| `BybitSpotMarketSource` + `BybitLinearMarketSource` | ~150 | 0.5 |
| `BybitKlineClient` (REST) | ~120 | 0.5 |
| `CompositeMarketSource` boot wiring in `DaemonCommand` + `RunCommand` | ~80 | 0.25 |
| Tests | ~600 | 1.5 |
| Phase changelog (`docs/phases/phase-28-...md`) | n/a | 0.25 |
| **Total** | **~1500 LOC** | **~4.5 days** |

## Future work (post-Phase 28)

- **Bybit private WS data** — execution/position events from Bybit (currently REST-polled by `BybitSpotStateRecovery`). Out of scope here; touches broker, not data.
- **WS-based MT5 source** — if the `mt5-gateway` adds a streaming endpoint, drop polling. Drop-in replacement of `Mt5TickPoller`.
- **Asset-class abstraction or DSL `FROM` clause** — only if multiple strategies actually need to override per-stream routing. Today, prefix encodes everything.
- **Connection pooling** — N strategies subscribing to the same Bybit `publicTrade.BTCUSDT` open N WebSockets. Could share one connection. Adds complexity; not warranted at current scale.
- **Reconnect supervisor unification** — Bybit-WS data, TV, and Bybit-broker each have their own reconnect logic. A `com.qkt.common.ReconnectSupervisor` extraction would dedupe.

## References

- Parity harness commits: `7c50fef` (harness), `3f55288` (QuoteSession bug fix).
- Existing MT5 broker profile: `src/main/kotlin/com/qkt/broker/mt5/MT5BrokerProfile.kt`.
- Existing Bybit broker: `src/main/kotlin/com/qkt/broker/bybit/`.
- `CompositeMarketSource`: `src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt`.
- `MarketSource` interface: `src/main/kotlin/com/qkt/marketdata/source/MarketSource.kt`.
- TradingView reference impl: `src/main/kotlin/com/qkt/marketdata/live/tv/`.
- Parity findings: `docs/parity/parity-bars-xauusd-m5.md`, `docs/parity/parity-ticks-xauusd.md`.
