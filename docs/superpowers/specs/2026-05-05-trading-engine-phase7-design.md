# qkt — Trading Engine Phase 7 Design

**Date:** 2026-05-05
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 7 of the qkt trading platform. Adds a live runtime alongside the existing backtest runtime, sharing one `TradingPipeline` and one set of strategy/indicator/risk code. Introduces a vendor-agnostic `MarketSource` umbrella that unifies historical bars, historical ticks, and live tick streaming behind a single interface. Ships `TradingViewMarketSource` as the first concrete vendor (live ticks via TradingView's `quote_session` WebSocket; historical bars via TradingView's `chart_session`), with `LocalMarketSource` rebranding Phase 6's on-disk cache and `CompositeMarketSource` providing per-symbol routing for future per-asset specialization (Binance for crypto, OANDA for FX, etc.). Adds session-anchored indicators (`PreviousDayHigh`, `SessionHigh` style), a `TradingCalendar`, indicator warmup machinery, and a `SessionContext` that lets DSL-era strategies optionally distinguish backtest from live without forcing the distinction on mode-agnostic strategies.
**Phase 6 baseline:** [`docs/superpowers/specs/2026-05-04-trading-engine-phase6-design.md`](2026-05-04-trading-engine-phase6-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin destined to ship as a packageable artifact (single JAR / CLI tool) so strategy authors can install qkt, write strategies in its forthcoming SQL-like DSL, and run them in backtest or live mode without writing JVM glue. Phases 1–5 shipped the deterministic engine core, indicators, risk, and the backtest harness. Phase 6 shipped the historical data layer (on-disk content-addressable CSV store, lazy gap-fill, `HistoricalDataProvider` query interface). Phase 7 turns qkt into a live trading runtime — same strategy code now also runs against real-time market data with paper execution against the existing `MockBroker`.

The packaging horizon shapes every architectural choice in this phase: no Node sidecar, no shelled-out scripts in the live data path, no runtime dependencies that an end user installing the qkt JAR would have to provision separately. Live data acquisition is implemented in pure Kotlin against the JVM's standard HTTP/WS clients.

Two architectural invariants carry forward from Phase 1 and tighten in Phase 7:

- **Strategy code is identical across backtest and live.** A strategy never branches on mode by default. The two things that differ between modes are (1) the `Clock` and (2) the `TickFeed`. Everything downstream is mode-agnostic.
- **Vendor independence.** No part of the engine, strategies, indicators, or risk rules depends on TradingView, Dukascopy, Binance, or any other specific vendor. Vendor-specific code lives behind the `MarketSource` interface; switching or composing vendors is a runtime config change.

This document is **Phase 7 only**.

## 2. Phase 7 — what we are building

A live runtime sharing the existing pipeline. Concrete deliverables:

1. **`MarketSource` interface** — vendor-agnostic umbrella covering live ticks, historical bars, and historical ticks. Replaces Phase 6's `HistoricalDataProvider` (which was historical-only). Capabilities advertised per-source (e.g. TV: live + bars; Local: bars + ticks; future Binance: all three).

2. **`TradingViewMarketSource`** — first concrete vendor. Native Kotlin via OkHttp WebSocket. Implements:
   - `liveTicks(symbols)` via TV `quote_session` (anonymous bootstrap, multi-symbol subscription, reconnect with backoff, heartbeat).
   - `bars(symbol, window, range)` via TV `chart_session` (resolution mapping from `TimeWindow`, bounded count).
   - `ticks(...)` is unsupported (TV doesn't expose tick history); querying it throws `UnsupportedDataException`.

3. **`LocalMarketSource`** — Phase 6's `StoreHistoricalDataProvider` rebranded. Wraps `DataStore`. Capabilities: `BARS`, `TICKS`. No live.

4. **`CompositeMarketSource`** — symbol-pattern router. Constructed with `Map<SymbolPattern, MarketSource>` plus a fallback. Lets you say "Binance for `BINANCE:*`, TV for everything else" once `BinanceMarketSource` exists.

5. **`LiveSession`** — new runtime entry point alongside `Backtest`. Runs the same `TradingPipeline` on a dedicated engine thread; live tick feed pushes into a bounded `BlockingQueue`; engine pulls. Non-blocking `start()` returns a `LiveSessionHandle` exposing `stop()`, status, and observable trade/rejection streams.

6. **`LiveTickFeed`** — concrete `TickFeed` returned by any `MarketSource.liveTicks(...)`. Wraps a WS client + queue + lifecycle. Bounded queue with drop-oldest overflow (default 10k). On disconnect, queue continues draining; reconnect runs in background; ticks resume.

7. **`IndicatorWarmer` + `Warmable` mixin + `WarmupSpec`** — two-phase startup. Strategies advertise warmup needs (`Bars(window, count)` or `Duration(window, duration)`). At session start, `IndicatorWarmer` queries the `MarketSource` for the warmup window and pushes through `IndicatorMap` ONLY (strategies do not see warmup ticks). After warmup completes, live feed drives the strategy.

8. **`TradingCalendar` + `SessionAnchor`** — session boundary primitives. Built-in calendars: `fxDefault()` (24/5 Sunday 17:00 NY → Friday 17:00 NY), `nyse()` (9:30–16:00 ET with US holidays), `crypto()` (24/7), `custom(spec)`. Used by session-anchored indicators and by strategies that gate on time-of-day.

9. **`SessionAnchoredIndicator<T>`** — base class for indicators that need both historical seed and live updates within the current anchor window. Examples: `PreviousDayHigh`, `SessionHigh(start = "18:00")`, `RollingDayLow`. Internally queries the injected `MarketSource` for prior-anchor data on first hit, caches per anchor, folds live ticks for the current anchor.

10. **`SessionContext`** — bundle injected into strategies that opt in. Exposes `mode: Mode (BACKTEST | LIVE)`, `clock: Clock`, `calendar: TradingCalendar`, `source: MarketSource`. The default `Strategy.onTick(tick, emit)` ignores it; mode-aware strategies override `Strategy.onTickWithContext(tick, ctx, emit)`. The DSL compiler exposes `mode`, `now()`, `in_session(...)` etc. as built-in symbols backed by `SessionContext`.

11. **Phase 6 naming refactor** — `marketdata.history` and `marketdata.store` package contents reorganize into `marketdata.source` (the `MarketSource` interface and its implementations), `marketdata.store` (unchanged: cache + manifest), and `marketdata.live` (WS plumbing). `HistoricalDataProvider` interface deletes; `StoreHistoricalDataProvider` renames to `LocalMarketSource`. `DataRequest` renames to `MarketRequest`. `Reductions` moves to `marketdata.source` package.

12. **Sample session-anchored strategy** — `BreakoutOfYesterdayHighStrategy` to exercise the new `SessionAnchoredIndicator` machinery in tests and as a worked example for users.

## 3. Out of scope (deferred)

- **Real broker integrations.** `MockBroker` continues to provide paper fills against live last-price. Phase 7b will introduce `LiveBroker` interface with concrete `AlpacaBroker`, `IBKRBroker`, etc.
- **The DSL itself.** Phase 8. The `SessionContext` design here is forward-compatible with the DSL's `mode` / `now()` / `in_session()` built-ins.
- **Additional `MarketSource` implementations beyond TV + Local.** `BinanceMarketSource`, `OANDAMarketSource`, etc. are out of scope. The `CompositeMarketSource` interface is the extensibility point; users (or later phases) can add implementations without touching the engine.
- **TV chart-session features beyond OHLC bars.** TV's protocol exposes drawings, server-side indicators, studies, screener data. Phase 7 ships only the `quote_session` for ticks and the `chart_session.create_series` path for bars. Everything else in TV's protocol is ignored.
- **TV authentication beyond anonymous mode.** TV's WS supports anonymous sessions for free-tier symbol coverage (which includes essentially every asset class). Logged-in mode (premium symbol coverage, higher rate limits) is deferred.
- **Multi-region / multi-instance live sessions.** `LiveSession` is single-process, single-instance. Sharding across machines is out of scope.
- **Persistence of live state across restarts.** Indicator state is rebuilt from warmup on every session start. Crash-recovery state checkpointing is deferred.

## 4. Decisions

### D1. Backtest vs live differ on `Clock` + `TickFeed` only

The two-axis difference is the architectural keystone. Everything else (strategies, indicators, risk, candles, broker abstraction, position tracker, PnL, event bus) is mode-agnostic. `Backtest` uses `FixedClock` + bounded `TickFeed`. `LiveSession` uses `SystemClock` + unbounded `TickFeed`. Both instantiate the same `TradingPipeline`.

**Why:** This invariant has held since Phase 1 and is the reason the project can ship live mode in one phase without a rewrite. Breaking it would force `if (isLive)` branches into strategy and indicator code, which would compound across the catalog and undermine the "same code in backtest and live" guarantee that the DSL needs.

**How to apply:** Any new code that needs to know the mode goes through `SessionContext`, never via a static or thread-local. Code that doesn't need to know never sees the mode at all.

### D2. `MarketSource` is the single vendor abstraction

One interface covers live ticks, historical bars, historical ticks. Capabilities are explicit (`MarketSourceCapability` enum). Unsupported capabilities throw `UnsupportedDataException` with a message naming the missing capability and the source class.

**Why:** Phase 6 had `HistoricalDataProvider` as historical-only, with `DataStore` as a sibling concept. With live added, three orthogonal interfaces (`HistoricalDataProvider` + `LiveDataFeed` + `DataStore`) is one too many — strategies that compose historical and live queries (the common case for session-anchored indicators) would need to wire two interfaces. One umbrella is simpler and matches the way users mentally model a "data source" (TradingView, Binance, Local cache).

**How to apply:** New vendors implement `MarketSource` directly. Strategies and indicators receive `MarketSource` (not the underlying store, fetcher, or WS client). The `DataStore` / `DataFetcher` / `Manifest` machinery from Phase 6 stays internal to `LocalMarketSource`.

### D3. Live ticks adapt push→pull via bounded blocking queue

`LiveTickFeed` exposes the existing `TickFeed.next(): Tick?` contract. The WS client (TV's network thread) enqueues ticks; the engine thread polls `next()` which blocks on the queue. Queue is bounded; overflow drops oldest with a metric increment (and an optional log warning).

**Why:** Preserves the synchronous single-threaded engine invariant. The engine is unaware that ticks come from a network thread. If the strategy is too slow, drop-oldest is the right policy for live trading: stale ticks are worse than missing ticks. Drop-newest would let backed-up state persist; backpressure (blocking the WS thread) would risk WS disconnect from the vendor.

**How to apply:** Default queue size 10k (~10 minutes of FX ticks at 16 ticks/sec, ~16 seconds of high-throughput crypto). User-configurable. Overflow events are observable via the `LiveSessionHandle`.

### D4. Native Kotlin TV WebSocket client; no Node sidecar

TradingView's WS protocol is implemented directly in Kotlin using OkHttp's WebSocket client. No Node, no shell-out, no external runtime.

**Why:** qkt is destined to ship as a single JAR. A Node sidecar fails the packaging test — every end user would need Node 18+ globally. The TV protocol scope needed for `quote_session` + `chart_session.create_series` is small enough (~hundreds of lines of Kotlin) that the cost is dominated by understanding the protocol, not implementing it. Prior art (Mathieu2301/TradingView-API in Node, ~3k stars) documents the protocol; we port the relevant slice.

**How to apply:** `marketdata.live.tv` package contains `TradingViewWebSocket` (low-level frame send/receive), `TradingViewQuoteSession` (subscribe/resolve/parse `qsd` frames), `TradingViewChartSession` (request `series` for bars). `TradingViewMarketSource` composes them into the `MarketSource` interface.

### D5. Warmup is bar-driven by default; ticks are an optional precision upgrade

`IndicatorWarmer` queries `MarketSource.bars(symbol, window, range)` for the warmup window. Indicators see candles via the existing `CandleAggregator` path; tick-driven indicators see synthetic ticks generated from bar closes (one synthetic tick per bar at the bar's close timestamp).

**Why:** Bar history is universally available across vendors (TV, Local, future Binance). Tick history is rare (only Local in Phase 7 — TV doesn't expose it; Binance does but specialised). Defaulting to bars means any vendor works for warmup out of the box. The bar-close-as-synthetic-tick approximation is acceptable for warmup because warmup runs are not allowed to emit trading signals (D6).

**How to apply:** `WarmupSpec.Bars(window, count)` is the canonical form. Strategies needing tick-precision warmup (rare) can set `WarmupSpec.Ticks(...)` — only honored if the source advertises `MarketSourceCapability.TICKS`, otherwise falls back to bars with a warning.

### D6. Strategies do not see warmup ticks

Only `IndicatorMap` consumes the warmup stream. Strategy `onTick` is not called during warmup.

**Why:** Otherwise, warmup would generate trades on stale prices, polluting the equity curve and PnL with fictitious fills. The whole point of warmup is "indicators are ready when the strategy starts." Letting the strategy see warmup ticks breaks that mental model.

**How to apply:** `TradingPipeline` exposes two ingress methods: `ingest(tick)` (full pipeline) and `ingestForWarmup(tick)` (only updates `IndicatorMap` and `CandleAggregator`, skips strategies and risk). `IndicatorWarmer` uses the latter.

### D7. `SessionContext` is opt-in, not mandatory

The default `Strategy.onTick(tick, emit)` signature stays unchanged. Strategies that need mode/clock/calendar/source override `Strategy.onTickWithContext(tick, ctx, emit)`. The default impl of `onTickWithContext` calls `onTick`, so existing strategies compile and run unchanged.

**Why:** The "strategies don't know they're live" invariant must be cheap to honor. Forcing every strategy to take a `SessionContext` parameter would make the common case (mode-agnostic strategy) more verbose and signal that mode-awareness is the norm. Opt-in via override keeps the common case clean and makes mode-awareness an explicit, visible choice.

**How to apply:** DSL strategies that don't reference `mode`, `now()`, or `in_session(...)` compile to `onTick`-only. DSL strategies that do reference any of those compile to `onTickWithContext`. The DSL compiler tracks which builtins are referenced.

### D8. Session anchors are first-class; calendar is pluggable

`TradingCalendar` is an interface; built-in instances cover FX (24/5), NYSE (9:30–16:00 ET with holidays), crypto (24/7), and a `custom(SessionSpec)` builder. `SessionAnchor` is an enum-like sealed type covering common anchors (PREVIOUS_DAY, CURRENT_SESSION, PREVIOUS_SESSION, ROLLING_24H, etc.) plus a custom-spec form.

**Why:** Session semantics differ wildly across asset classes (FX has no daily close; equities have hard session boundaries; crypto has none). Building these into a shared calendar avoids per-strategy reimplementation and makes session-anchored queries reproducible.

**How to apply:** Calendar is injected into `SessionAnchoredIndicator` instances via the strategy's `SessionContext`. Strategies pick the calendar appropriate to their universe (or use the engine default). The `LiveSession` constructor takes the calendar; `Backtest` does too (default for backtest is the same calendar the user picks for the live runtime, ensuring backtest and live behave identically on session boundaries).

### D9. `LiveSession` is single-process, single-thread engine

The engine thread is the single owner of pipeline state, exactly as in `Backtest`. Network I/O (WS receive) happens on OkHttp's network thread; ticks cross to the engine thread via the bounded queue. There is no parallel ingestion across symbols at the engine layer; multi-symbol scaling happens via the queue.

**Why:** The deterministic single-threaded engine is qkt's correctness story. Introducing parallelism at the engine layer would require locking (loss of determinism, performance penalty) or per-symbol shards (massive complexity for marginal benefit at qkt's scale). The bounded queue gives us push-asynchronous ingestion without disturbing the engine model.

**How to apply:** `LiveSession.start()` spawns one engine thread that drains the queue. `LiveSessionHandle.stop()` interrupts it cleanly. Multi-symbol sessions share one queue, one engine thread, one pipeline.

### D10. Reconnection is automatic; semantics are at-most-once

If the WS drops, the live feed reconnects with exponential backoff (default 1s, 2s, 4s, 8s, capped at 60s). Ticks during the disconnected window are LOST — there is no replay/catchup. The reconnect resubscribes the active symbol set.

**Why:** TV's WS does not offer historical replay from a sequence number. Most live-data vendors don't. Replaying via `bars()` after reconnect is possible but introduces consistency questions (bar timestamps don't match tick timestamps; backfilled bar closes might miss intra-bar ticks). At-most-once with explicit gap detection is simpler and matches what every production live trading system actually does.

**How to apply:** `LiveSessionHandle` exposes a `disconnections` observable; strategies that want to react (close positions on extended outage, etc.) subscribe. `MockBroker` continues to fill on whatever the most recent in-process price is, so paper trading degrades gracefully through brief outages.

## 5. Architecture diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Application                          │
│   Backtest │ LiveSession │ DSL Runner (Phase 8)         │
└──────┬──────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────┐
│                  TradingPipeline                         │
│   tick → strategies → orders → broker → fills            │
│   (single-threaded, deterministic, mode-agnostic)        │
└────┬─────────────┬────────────────┬──────────────────────┘
     │             │                │
     ▼             ▼                ▼
┌────────┐  ┌──────────┐    ┌──────────────────┐
│TickFeed│  │  Broker  │    │  Indicators +    │
│(pull)  │  │(execute) │    │  Strategies      │
└───┬────┘  └────┬─────┘    │  + RiskEngine    │
    │           │            └──────┬────────────┘
    │           │                   │
    │           │                   ▼  (warmup + session anchors)
    │           │            ┌──────────────────┐
    │           │            │  MarketSource    │
    │           │            │  (random-access  │
    │           │            │   queries)       │
    │           │            └────────┬─────────┘
    │           │                     │
    ▼           ▼                     │
┌────────────────────────────────────────────────────┐
│           MarketSource (per vendor)                │
│  TradingView │ Local │ (future) Binance │ ...      │
│  ────────────  ─────  ─────────────────             │
│  liveTicks()   —      liveTicks()                   │
│  bars()        bars() bars()                        │
│  ticks()       ticks() ticks()                      │
└────────────────────┬───────────────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────────────┐
│  Backends                                          │
│  TV WebSocket │ DataStore (cache) │ Binance WS+REST│
└────────────────────────────────────────────────────┘
```

## 6. Naming refactor (Phase 6 → Phase 7)

| Phase 6 name | Phase 7 name | Reason |
|---|---|---|
| `HistoricalDataProvider` interface | **deleted** | absorbed into `MarketSource`; the historical face is `bars()` / `ticks()` |
| `StoreHistoricalDataProvider` | `LocalMarketSource` | it's a `MarketSource` whose backend is a local cache |
| `DataStore` / `DefaultDataStore` | unchanged | still the local CSV cache; now wrapped by `LocalMarketSource` |
| `DataFetcher` / `ScriptDataFetcher` | unchanged | gap-fill plugin for `DataStore` |
| `DataRequest` | `MarketRequest` | the type is a query against `MarketSource`, not the store specifically |
| `DataCapability` | `MarketSourceCapability` | matches the renamed interface |
| `UnsupportedDataException` | unchanged | already vendor-neutral |
| `Reductions` (extension functions) | unchanged, package moves to `marketdata.source` | follows its primary consumer |

Package reorg:

```
marketdata/
  Tick.kt, Candle.kt, TickFeed.kt, MergingTickFeed.kt, ...    (unchanged)
  source/                                                      (NEW, was history/)
    MarketSource.kt
    MarketSourceCapability.kt
    MarketRequest.kt
    LocalMarketSource.kt                                       (was StoreHistoricalDataProvider)
    CompositeMarketSource.kt
    Reductions.kt                                              (moved from history/)
  store/                                                       (unchanged contents)
    DataStore.kt, DefaultDataStore.kt, Manifest.kt, ...
  live/                                                        (NEW)
    LiveTickFeed.kt
    tv/
      TradingViewMarketSource.kt
      TradingViewWebSocket.kt
      TradingViewQuoteSession.kt
      TradingViewChartSession.kt
```

## 7. Component contracts

### 7.1 `MarketSource`

```kotlin
interface MarketSource {
    val name: String
    val capabilities: Set<MarketSourceCapability>

    fun supports(symbol: String): Boolean

    fun liveTicks(symbols: List<String>): TickFeed
    fun bars(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle>
    fun ticks(symbol: String, range: TimeRange): Sequence<Tick>
}

enum class MarketSourceCapability { LIVE_TICKS, BARS, TICKS }
```

Default implementation behavior for unsupported capabilities:
- `liveTicks` on a source without `LIVE_TICKS` → throws `UnsupportedDataException`
- `bars` on a source without `BARS` → throws `UnsupportedDataException`
- `ticks` on a source without `TICKS` → throws `UnsupportedDataException`

### 7.2 `LiveSession`

```kotlin
class LiveSession(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule>,
    private val source: MarketSource,
    private val symbols: List<String>,
    private val candleWindow: TimeWindow? = null,
    private val clock: Clock = SystemClock(),
    private val calendar: TradingCalendar = TradingCalendar.fxDefault(),
    private val warmupOverride: WarmupSpec? = null,    // null = auto-derive from strategies
    private val queueCapacity: Int = 10_000,
) {
    fun start(): LiveSessionHandle
}

interface LiveSessionHandle {
    fun stop()
    val running: Boolean
    val trades: Flow<Trade>
    val rejections: Flow<RiskRejectedEvent>
    val disconnections: Flow<DisconnectionEvent>
    val droppedTicks: Long
}
```

### 7.3 `IndicatorWarmer` + `WarmupSpec`

```kotlin
sealed class WarmupSpec {
    object None : WarmupSpec()
    data class Bars(val window: TimeWindow, val count: Int) : WarmupSpec()
    data class Duration(val window: TimeWindow, val duration: java.time.Duration) : WarmupSpec()
    data class Ticks(val duration: java.time.Duration) : WarmupSpec()
}

interface Warmable { val warmup: WarmupSpec }

class IndicatorWarmer(
    private val source: MarketSource,
    private val pipeline: TradingPipeline,
) {
    fun warmup(symbols: List<String>, spec: WarmupSpec, now: Instant)
}

// WarmupSpec exposes a canonical comparator:
//   fun WarmupSpec.windowMs(now: Instant): Long   // 0 for None, total ms for Bars/Duration/Ticks
//
// LiveSession aggregates per-strategy warmup specs by picking the widest:
//   val effective = warmupOverride
//       ?: strategies.filterIsInstance<Warmable>()
//           .map { it.warmup }
//           .maxByOrNull { it.windowMs(clock.now()) }
//       ?: WarmupSpec.None
//   IndicatorWarmer(source, pipeline).warmup(symbols, effective, clock.now())
```

### 7.4 `TradingCalendar` + `SessionAnchor`

```kotlin
sealed class SessionAnchor {
    object PreviousDay : SessionAnchor()
    object CurrentSession : SessionAnchor()
    object PreviousSession : SessionAnchor()
    data class Rolling(val duration: java.time.Duration) : SessionAnchor()
    data class Custom(val spec: SessionSpec) : SessionAnchor()
}

interface TradingCalendar {
    fun isInSession(symbol: String, t: Instant): Boolean
    fun sessionRange(symbol: String, t: Instant): TimeRange
    fun anchorEpochFor(anchor: SessionAnchor, t: Instant): Long
    fun rangeFor(anchor: SessionAnchor, anchorEpoch: Long): TimeRange
}

object TradingCalendar {
    fun fxDefault(): TradingCalendar
    fun nyse(): TradingCalendar
    fun crypto(): TradingCalendar
    fun custom(spec: SessionSpec): TradingCalendar
}
```

### 7.5 `SessionContext` + `Strategy` extension

```kotlin
data class SessionContext(
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
)

enum class Mode { BACKTEST, LIVE }

interface Strategy {
    fun onTick(tick: Tick, emit: (Signal) -> Unit) { /* default */ }
    fun onTickWithContext(tick: Tick, ctx: SessionContext, emit: (Signal) -> Unit) {
        onTick(tick, emit)
    }
}
```

### 7.6 `SessionAnchoredIndicator`

```kotlin
abstract class SessionAnchoredIndicator<T>(
    protected val anchor: SessionAnchor,
    protected val calendar: TradingCalendar,
    protected val source: MarketSource,
) : Indicator<Tick> {
    protected abstract fun reduce(seed: Sequence<Tick>): T?
    protected abstract fun fold(current: T?, tick: Tick): T?

    private var anchorEpoch: Long = Long.MIN_VALUE
    private var cached: T? = null

    override fun update(tick: Tick) { /* see body */ }
    override fun value(): T? = cached
}

class PreviousDayHigh(symbol: String, calendar: TradingCalendar, source: MarketSource)
    : SessionAnchoredIndicator<BigDecimal>(...)

class SessionHigh(symbol: String, anchor: SessionAnchor, calendar: TradingCalendar, source: MarketSource)
    : SessionAnchoredIndicator<BigDecimal>(...)
```

## 8. Backtest impact

Existing `Backtest` callers continue to work. Internally:
- `Backtest.fromStore(store, request, ...)` rewrites to `Backtest.fromSource(source = LocalMarketSource(store), request, ...)`. Old factory keeps a thin `@Deprecated` adapter for one phase.
- Backtest gains a `warmupSpec: WarmupSpec` parameter (default `None`) and a `calendar: TradingCalendar` parameter (default `fxDefault()`). When `warmupSpec != None`, `IndicatorWarmer` runs before the main feed loop using the same `MarketSource` that produces the backtest feed.
- Backtest's `clock` parameter (already injectable) defaults to `FixedClock`.
- Strategies overriding `onTickWithContext` receive a `SessionContext(mode = BACKTEST, ...)`.

Determinism guarantee: backtest results with `WarmupSpec.None` are bit-identical to Phase 6. Backtest results with non-None warmup are deterministic given the same store contents.

## 9. Live runtime contracts

- **Threading:** one engine thread, one OkHttp network thread, one reconnect-supervisor thread (only active during disconnect). User code (strategies, indicators, risk) executes only on the engine thread.
- **Backpressure:** drop-oldest on bounded queue. Drop count is observable. Strategies are NOT informed of drops directly; they observe via the handle if they care.
- **Shutdown:** `stop()` closes the WS, drains the queue (synchronously, with a deadline), interrupts the engine thread, returns a final snapshot. SIGINT handler optional via `ShutdownHook` parameter.
- **Clock skew:** `SystemClock.now()` is used for session anchors and look-ahead-bias guards. No NTP synchronization is enforced. If the local clock is wrong, session boundaries are wrong; this is the user's responsibility.
- **Look-ahead-bias guard:** `MarketSource.bars(...)` and `ticks(...)` continue to enforce `range.to <= clock.now()`. In live mode this becomes "you cannot query bars beyond wall time," which is a true constraint (TV won't return them anyway); the guard provides a clear error rather than silent empty results.

## 10. TradingView WebSocket protocol notes

Implementation reference (not normative): Mathieu2301/TradingView-API (Node, ~3k stars on GitHub).

Protocol sketch:
- Connect to `wss://data.tradingview.com/socket.io/websocket` with origin header `https://www.tradingview.com`.
- Authenticate via `set_auth_token` with the `unauthorized_user_token` constant for anonymous mode. (This token is published in TV's web client source; it is not a user secret.)
- Create a quote session: `quote_create_session` with a generated session ID (8-char random).
- Set fields: `quote_set_fields` listing the fields we want (`lp` for last, `bid`, `ask`, `volume`, `change`).
- Add symbols: `quote_add_symbols` with the session ID and full TV symbols (e.g. `OANDA:EURUSD`, `BINANCE:BTCUSDT`).
- Receive `qsd` (quote session data) frames; each contains a symbol and a delta of fields. Maintain symbol state and emit `Tick` on price change.
- Heartbeat: TV sends `~h~N~h~` ping frames; we echo them back. Failure to respond within ~30s causes server-side disconnect.
- Disconnect handling: any frame parse error or socket close triggers reconnect with exponential backoff. On reconnect, recreate session and re-add symbols.

Bar history (separate session on the same WS):
- `chart_create_session` with a chart-session ID.
- `resolve_symbol` with the symbol and resolution.
- `create_series` with timeframe (mapped from `TimeWindow`: `1S`, `1`, `5`, `60`, `1D`, etc.) and bar count.
- Receive `timescale_update` frames containing the bars.
- Close session after data received.

Frame format: `~m~LENGTH~m~JSON_PAYLOAD` (Socket.IO v3-ish). Heartbeats are `~h~N~h~`. Implement a small frame parser; do not pull in a Socket.IO client library.

## 11. Acceptance criteria

Phase 7 ships when:

- [ ] `MarketSource` interface exists; `MarketSourceCapability` enum exists; `MarketRequest` type exists.
- [ ] `LocalMarketSource` (was `StoreHistoricalDataProvider`) implements `MarketSource` with `BARS` + `TICKS` capabilities.
- [ ] `TradingViewMarketSource` implements `MarketSource` with `LIVE_TICKS` + `BARS` capabilities. Connects to TV WS, subscribes to a multi-symbol set, parses `qsd` frames into `Tick`s, handles heartbeats, reconnects with backoff.
- [ ] `CompositeMarketSource` exists and routes per-symbol via `Map<SymbolPattern, MarketSource>` + fallback.
- [ ] `LiveSession` runtime starts on a background engine thread, wires `MarketSource.liveTicks(...)` as `TickFeed`, drives the existing `TradingPipeline`, exposes `LiveSessionHandle` with stop/status/observable streams.
- [ ] `LiveTickFeed` adapts WS push to `TickFeed.next()` pull via bounded queue with drop-oldest overflow; drop count observable.
- [ ] `IndicatorWarmer` + `Warmable` mixin + `WarmupSpec` ship; warmup runs `pipeline.ingestForWarmup(...)` (NOT `pipeline.ingest(...)`); strategies do not see warmup ticks.
- [ ] `TradingCalendar` interface ships with `fxDefault()`, `nyse()`, `crypto()`, `custom(spec)` factories.
- [ ] `SessionAnchor` sealed type ships with `PreviousDay`, `CurrentSession`, `PreviousSession`, `Rolling(duration)`, `Custom(spec)` variants.
- [ ] `SessionAnchoredIndicator<T>` base class ships; `PreviousDayHigh` and `SessionHigh` sample indicators ship.
- [ ] `SessionContext` + `Mode` enum ship; `Strategy.onTickWithContext` default-implementation bridges to `onTick`; existing strategies compile unchanged.
- [ ] Phase 6 names refactored: `HistoricalDataProvider` deleted, `StoreHistoricalDataProvider` → `LocalMarketSource`, `DataRequest` → `MarketRequest`, `DataCapability` → `MarketSourceCapability`. Packages reorganized into `marketdata.source` / `marketdata.store` / `marketdata.live`.
- [ ] `Backtest.fromStore` retains compatibility (deprecated thin wrapper); `Backtest.fromSource(source, request, ...)` is the new ergonomic entry point.
- [ ] Backtest gains `warmupSpec` and `calendar` parameters (defaulted).
- [ ] `BreakoutOfYesterdayHighStrategy` sample exists and exercises the session-anchored path.
- [ ] All existing 264+ tests continue to pass (rename-driven changes only).
- [ ] New tests cover: `MarketSource` capability advertisement and unsupported-capability errors, `LocalMarketSource` end-to-end against sample fixtures, `TradingViewMarketSource` against a recorded WS fixture (offline test), `LiveTickFeed` push→pull adapter (queue overflow, ordering, close), `IndicatorWarmer` (warmup ticks bypass strategies), `TradingCalendar` boundary cases, `SessionAnchoredIndicator` switching across anchors, `LiveSession` start/stop lifecycle.
- [ ] Live demo: a minimal main runs `LiveSession` against TV with EURUSD + XAUUSD + BTCUSD, receives ticks, simulates fills via `MockBroker`, prints trades to stdout. Documented in README.
- [ ] No new runtime dependencies beyond OkHttp (already a transitive JVM standard) and the existing `kotlinx-serialization-json`.
- [ ] `./gradlew run` (the existing demo) still produces identical output.

## 12. Testing strategy

- **Unit tests** for everything mode-agnostic (calendars, anchors, warmer, market-source routing) using fakes and recorded fixtures.
- **Recorded fixture tests** for TV WS protocol parsing — capture a real WS session as a JSON file, replay it through the parser offline. No network required for CI.
- **Integration tests** for `LiveSession` using an in-memory `MarketSource` that emits a deterministic tick stream from `Channel<Tick>`. Validates the engine thread, queue, and pipeline wiring without TV.
- **End-to-end smoke test** for `TradingViewMarketSource` (manual, run-on-demand) — connects to real TV, subscribes to one symbol, asserts ticks arrive within a timeout. Flagged with `@Tag("e2e")` and excluded from default `./gradlew test`.
- **Determinism test** — backtest with `WarmupSpec.Bars(...)` runs twice against the same `LocalMarketSource` produces bit-identical `BacktestResult` (preserves Phase 6's determinism guarantee).

## 13. Open questions

1. **`Warmable` placement.** Should `Warmable` be implemented by `Strategy` (one warmup per strategy) or by `Indicator` (one warmup per indicator, aggregated)? Spec currently puts it on `Strategy`. Indicator-level warmup is more granular but harder to wire (strategies own indicators directly; the engine doesn't know about per-strategy indicator collections). Resolved: stick with `Strategy`-level for Phase 7; revisit if indicator-level is needed.

2. **`SessionAnchoredIndicator` source coupling.** The base class takes a `MarketSource` as a constructor parameter. This couples indicators to a source instance, which feels heavy. Alternative: route source queries through `SessionContext` instead. Resolved: parameter-injection is fine for Phase 7 because indicators are constructed by strategy code that has the source in scope; in the DSL phase (Phase 8) the compiler injects from `SessionContext` automatically.

3. **TV symbol resolution.** TV uses `EXCHANGE:SYMBOL` form (`OANDA:EURUSD`). qkt internally uses bare symbols (`EURUSD`). Mapping is per-source. Resolved: each `MarketSource` implementation accepts whatever symbol form it expects; `CompositeMarketSource` is responsible for translating the user's preferred form to the per-source form. Document in `MarketSource` Javadoc.

4. **Bar timeframe granularity.** TV exposes 1s, 5s, 15s, 30s, 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M. qkt's `TimeWindow` is parameterized in millis. We need a `TimeWindow → TV resolution string` mapping. Resolved: ship a small `TradingViewResolution` mapper that supports the standard intervals; non-standard intervals (e.g. 13s) throw a clear error.

5. **Warmup determinism in live mode.** Live warmup queries TV at startup; TV's bar history can include very recent bars whose values change as the bar finalizes. Resolved: warmup queries are bounded to bars STRICTLY before the current incomplete bar. Specifically, if `now = 14:23:17` and window = `1m`, warmup includes the bar ending `14:23:00` and earlier; the in-progress `14:23:00–14:24:00` bar is NOT included. This is consistent with how live trading systems handle this. Document in `IndicatorWarmer`.

## 14. Implementation strategy

Executed via `superpowers:writing-plans` after spec approval. Estimated decomposition (refined in the plan doc):

- Group A: rename refactor (Phase 6 → Phase 7 names), no behavior change. ~5 small commits.
- Group B: `MarketSource` umbrella, `LocalMarketSource`, `CompositeMarketSource`. ~6 commits.
- Group C: `LiveTickFeed` + bounded queue + `LiveSession` runtime + handle. ~5 commits.
- Group D: `TradingViewWebSocket` + `QuoteSession` + `ChartSession` + `TradingViewMarketSource`. ~8 commits.
- Group E: `IndicatorWarmer` + `WarmupSpec` + `Warmable` + pipeline split (`ingest` / `ingestForWarmup`). ~4 commits.
- Group F: `TradingCalendar` + `SessionAnchor` + `SessionAnchoredIndicator` + `PreviousDayHigh` + `SessionHigh`. ~5 commits.
- Group G: `SessionContext` + `Mode` + `Strategy.onTickWithContext` + `BreakoutOfYesterdayHighStrategy` sample. ~3 commits.
- Group H: README, live demo, smoke tests, determinism test. ~3 commits.

Total: ~40 commits, similar shape to Phase 6. Each group is independently testable; Groups A–B can land first as a rename PR if helpful.
