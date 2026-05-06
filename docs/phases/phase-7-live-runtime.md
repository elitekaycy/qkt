# Phase 7 — Live Runtime + MarketSource Umbrella

**Status:** Shipped. Merged into `main` on (placeholder — fill in at merge).
**Spec:** [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../superpowers/specs/2026-05-05-trading-engine-phase7-design.md)
**Plans:** 7a / 7b / 7c (linked at the bottom).

---

## Summary

Phase 7 turns qkt into a live trading runtime. The same strategy code that runs in `Backtest` now also runs against real-time market data via the new `LiveSession` runtime. Vendor-specific data acquisition is hidden behind the `MarketSource` umbrella; the first concrete vendor is TradingView (via OkHttp WebSocket, anonymous mode). Phase 7 also ships session-anchored indicators (`PreviousDayHigh`, `SessionHigh`, etc.), a pluggable `TradingCalendar` (FX / NYSE / crypto), `TimeMark` + `TimeRange.of(...)` composable time primitives, indicator warmup machinery, and a `SessionContext` that lets mode-aware strategies opt into knowing whether they are backtest or live.

---

## What's new

### Vendor-agnostic data layer

- `com.qkt.marketdata.source.MarketSource` — the umbrella interface for all market-data vendors. Three capabilities: `LIVE_TICKS`, `BARS`, `TICKS`.
- `com.qkt.marketdata.source.MarketSourceCapability` — enum.
- `com.qkt.marketdata.source.MarketRequest` — query value type (was `DataRequest`).
- `com.qkt.marketdata.source.LocalMarketSource` — Phase 6's on-disk store, now behind `MarketSource` (was `StoreHistoricalDataProvider`).
- `com.qkt.marketdata.source.CompositeMarketSource` — symbol-pattern router with multi-vendor live fan-in.
- `com.qkt.marketdata.source.SymbolPattern` — `prefix(...)`, `exact(...)` factories.
- `com.qkt.marketdata.source.SequenceTickFeed` — adapter from `Sequence<Tick>` to `TickFeed`.
- `com.qkt.marketdata.source.NullMarketSource` — inert source for `Backtest` paths that don't need a real one.
- `com.qkt.marketdata.source.UnsupportedDataException` — moved from `marketdata.history`.
- `com.qkt.marketdata.source.Reductions` — extension functions for sequences of candles, moved from `marketdata.history`.

### Live runtime

- `com.qkt.app.LiveSession` — runtime entry point alongside `Backtest`. Single dedicated engine thread; bounded queue tick ingest.
- `com.qkt.app.LiveSessionHandle` — control surface (`stop`, `awaitTermination`, `running`, `droppedTicks`, `recentTrades`).
- `com.qkt.app.IndicatorWarmer` — bar-driven warmup driver. Pushes synthetic ticks at `bar.endTime - 1` through `pipeline.ingestForWarmup`.
- `com.qkt.marketdata.live.LiveTickSource` — vendor-internal push-style producer interface.
- `com.qkt.marketdata.live.LiveTickFeed` — bounded-queue adapter from `LiveTickSource` (push) to `TickFeed` (pull). Drop-oldest overflow.
- `com.qkt.app.TradingPipeline.ingestForWarmup(tick)` — second ingress that bypasses strategies and risk; only updates `MarketPriceTracker` and `CandleAggregator`.

### Calendar + session anchors

- `com.qkt.common.TradingCalendar` — interface plus `fxDefault()`, `nyse()`, `crypto()` factories.
- `com.qkt.common.SessionAnchor` — sealed class: `PreviousDay`, `CurrentSession`, `PreviousSession`, `Rolling(duration)`.
- `com.qkt.common.TimeMark` — sealed class: `Now`, `Absolute(instant)`, `AtSessionAnchor(anchor, timeOfDay?)`, `RelativeToNow(offset)`.
- `com.qkt.common.TimeRange.of(from, to, clock, calendar)` — composable range builder.
- `com.qkt.common.RefreshTrigger` — sealed class: `Once`, `EveryNTicks(n)`, `OnAnchorRollover(anchor, calendar)`, `OnSessionRollover`, `OnTimeOfDay(time)`.

### Range-aggregate indicators

- `com.qkt.indicators.range.RangeAggregateIndicator<T>` — base machinery for any indicator that reduces over a `TimeRange` of bars.
- `com.qkt.indicators.range.SessionAnchoredIndicator<T>` — sugar over the above.
- `com.qkt.indicators.range.PreviousDayHigh` — concrete indicator.
- `com.qkt.indicators.range.PreviousDayLow` — concrete indicator.
- `com.qkt.indicators.range.SessionHigh` — concrete indicator (anchor-parameterized).
- `com.qkt.indicators.range.SessionLow` — concrete indicator (anchor-parameterized).

### Strategy mode-awareness

- `com.qkt.strategy.Mode` — enum: `BACKTEST`, `LIVE`.
- `com.qkt.strategy.SessionContext` — bundle: `mode`, `clock`, `calendar`, `source`.
- `com.qkt.strategy.Strategy.onTickWithContext(tick, ctx, emit)` — opt-in default method bridging to `onTick`.
- `com.qkt.strategy.WarmupSpec` — sealed class: `None`, `Bars(window, count)`, `Duration(window, duration)`, `Ticks(duration)`.
- `com.qkt.strategy.Warmable` — interface: `val warmup: WarmupSpec`.

### TradingView vendor (Plan 7c)

- `com.qkt.marketdata.live.tv.TradingViewMarketSource` — public `MarketSource` impl (`LIVE_TICKS` + `BARS`).
- `com.qkt.marketdata.live.tv.TradingViewWebSocket` — low-level WS client (OkHttp).
- `com.qkt.marketdata.live.tv.TradingViewWebSocketLike` — interface for testability.
- `com.qkt.marketdata.live.tv.TradingViewListener` — listener interface.
- `com.qkt.marketdata.live.tv.TradingViewFrame` — typed wrapper (`Message`, `Heartbeat`).
- `com.qkt.marketdata.live.tv.TradingViewFraming` — `~m~LEN~m~PAYLOAD` encode/decode.
- `com.qkt.marketdata.live.tv.TradingViewQuoteSession` — high-level live-tick subscription.
- `com.qkt.marketdata.live.tv.TradingViewChartSession` — high-level historical bars query.
- `com.qkt.marketdata.live.tv.TradingViewResolution` — `TimeWindow` → TV resolution string mapper.

### Sample strategies

- `com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy` — uses `PreviousDayHigh` + `SessionContext`.
- `com.qkt.strategy.samples.RollingHighBreakoutStrategy` — uses raw `RangeAggregateIndicator` + `RefreshTrigger.OnSessionRollover`.

### Application

- `com.qkt.app.LiveDemo.main` — entry point at `./gradlew runLiveDemo`.

### Backtest enhancements

- `Backtest.fromSource(source, request, candleWindow?, warmupSpec?)` — factory consuming `MarketSource`. New ergonomic entry point.
- `Backtest.fromStore(store, request, candleWindow?)` — kept for compatibility, internally rewritten over `LocalMarketSource`.
- `Backtest` accepts `warmupSpec: WarmupSpec = WarmupSpec.None` and `calendar: TradingCalendar = TradingCalendar.crypto()`.

---

## Migration from previous phase

| Phase 6 name | Phase 7 name | Notes |
|---|---|---|
| `com.qkt.marketdata.history.HistoricalDataProvider` | (deleted) | Absorbed into `MarketSource`. |
| `com.qkt.marketdata.history.StoreHistoricalDataProvider` | `com.qkt.marketdata.source.LocalMarketSource` | Same backend, now behind the umbrella. |
| `com.qkt.marketdata.store.DataRequest` | `com.qkt.marketdata.source.MarketRequest` | Renamed + moved. |
| `com.qkt.marketdata.history.UnsupportedDataException` | `com.qkt.marketdata.source.UnsupportedDataException` | Moved. |
| `com.qkt.marketdata.history.Reductions` | `com.qkt.marketdata.source.Reductions` | Moved (extension functions). |
| `DataCapability` | `MarketSourceCapability` | Renamed. |
| `package com.qkt.marketdata.history` | (deleted) | Replaced by `marketdata.source` + `marketdata.live`. |

Existing call sites need import rewrites only; behavior is identical for the historical-only path.

---

## Usage cookbook

Worked examples covering the full surface area of Phase 7. Each is a runnable Kotlin snippet (assuming a JVM project with qkt on the classpath).

### 1. Bar history from a vendor (one-shot query)

```kotlin
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.time.Instant

fun main() {
    val source = TradingViewMarketSource.connect()
    try {
        val range =
            TimeRange(
                from = Instant.parse("2024-01-15T00:00:00Z"),
                to = Instant.parse("2024-01-15T01:00:00Z"),
            )
        val bars = source.bars("OANDA:EURUSD", TimeWindow.FIVE_MINUTES, range).toList()
        bars.forEach { c -> println("${c.startTime} O=${c.open} H=${c.high} L=${c.low} C=${c.close}") }
    } finally {
        source.close()
    }
}
```

### 2. Live tick consumption (no strategy, just observe)

```kotlin
import com.qkt.marketdata.live.tv.TradingViewMarketSource

fun main() {
    val source = TradingViewMarketSource.connect()
    val feed = source.liveTicks(listOf("OANDA:EURUSD"))
    try {
        repeat(50) {
            val tick = feed.next() ?: return
            println("${tick.timestamp} ${tick.symbol} @ ${tick.price}")
        }
    } finally {
        feed.close()
        source.close()
    }
}
```

### 3. Backtest with `MarketRequest` (no warmup)

```kotlin
import com.qkt.app.Backtest
import com.qkt.common.SystemClock
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.EveryNthTickBuyStrategy
import java.nio.file.Path
import java.time.Instant

fun main() {
    val store = DefaultDataStore(root = Path.of("data/sample"))
    val source = LocalMarketSource(store, SystemClock())
    val request =
        MarketRequest(
            symbols = listOf("EURUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
    val result =
        Backtest.fromSource(
            strategies = listOf(EveryNthTickBuyStrategy(symbol = "EURUSD", n = 10)),
            source = source,
            request = request,
        ).run()
    println("Trades: ${result.tradeCount}, PnL: ${result.totalPnL}")
}
```

### 4. Backtest with bar warmup

```kotlin
import com.qkt.app.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.marketdata.source.LocalMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.nio.file.Path
import java.time.Instant

fun main() {
    val store = DefaultDataStore(root = Path.of("data/sample"))
    val source = LocalMarketSource(store, SystemClock())
    val request =
        MarketRequest(
            symbols = listOf("EURUSD"),
            from = Instant.parse("2024-01-16T00:00:00Z"),
            to = Instant.parse("2024-01-17T00:00:00Z"),
        )
    Backtest
        .fromSource(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("EURUSD")),
            source = source,
            request = request,
            warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1440),
        ).run()
        .let { println("Trades: ${it.tradeCount}, PnL: ${it.totalPnL}") }
}
```

### 5. LiveSession against TradingView

```kotlin
import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.time.Duration

fun main() {
    val source = TradingViewMarketSource.connect()
    val handle =
        LiveSession(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")),
            rules = listOf(MaxPositionSize("OANDA:EURUSD", maxQty = Money.of("3"))),
            source = source,
            symbols = listOf("OANDA:EURUSD"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
        ).start()
    handle.awaitTermination(Duration.ofMinutes(10))
    source.close()
}
```

### 6. Multi-vendor routing via `CompositeMarketSource`

```kotlin
import com.qkt.app.LiveSession
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.strategy.Strategy
import java.time.Duration

fun main() {
    val tv = TradingViewMarketSource.connect()
    // Pretend BinanceMarketSource exists. Phase 7 ships only TV; the routing pattern is forward-compatible.
    // val binance = BinanceMarketSource.connect()
    val composite =
        CompositeMarketSource(
            routes =
                listOf(
                    SymbolPattern.prefix("BINANCE:") to tv, // would be `binance` once that vendor lands
                    SymbolPattern.prefix("OANDA:") to tv,
                ),
            fallback = tv,
        )
    val strategies: List<Strategy> = emptyList()
    val handle =
        LiveSession(
            strategies = strategies,
            source = composite,
            symbols = listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"),
            clock = SystemClock(),
            calendar = TradingCalendar.crypto(),
        ).start()
    handle.awaitTermination(Duration.ofSeconds(10))
}
```

### 7. Session-anchored indicator standalone

```kotlin
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.indicators.range.PreviousDayHigh
import com.qkt.marketdata.live.tv.TradingViewMarketSource

fun main() {
    val source = TradingViewMarketSource.connect()
    val pdh = PreviousDayHigh(symbol = "OANDA:EURUSD", calendar = TradingCalendar.fxDefault(), source = source, clock = SystemClock())
    val feed = source.liveTicks(listOf("OANDA:EURUSD"))
    repeat(20) {
        val tick = feed.next() ?: return@repeat
        pdh.update(tick)
        println("now=${tick.price} prevDayHigh=${pdh.value()}")
    }
    feed.close()
    source.close()
}
```

### 8. Custom range-aggregate indicator (rolling 6-hour high)

```kotlin
import com.qkt.candles.TimeWindow
import com.qkt.common.RefreshTrigger
import com.qkt.common.SystemClock
import com.qkt.common.TimeMark
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.indicators.range.RangeAggregateIndicator
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.math.BigDecimal
import java.time.Duration

fun main() {
    val source = TradingViewMarketSource.connect()
    val clock = SystemClock()
    val calendar = TradingCalendar.fxDefault()
    val rolling6hHigh =
        RangeAggregateIndicator<BigDecimal>(
            symbol = "OANDA:EURUSD",
            window = TimeWindow.ONE_MINUTE,
            rangeSpec = {
                TimeRange.of(
                    from = TimeMark.RelativeToNow(Duration.ofHours(-6)),
                    to = TimeMark.Now,
                    clock = clock,
                    calendar = calendar,
                )
            },
            reduce = { it.maxOfOrNull { c -> c.high } },
            source = source,
            clock = clock,
            refreshOn = RefreshTrigger.EveryNTicks(100),
        )
    val feed = source.liveTicks(listOf("OANDA:EURUSD"))
    repeat(200) {
        val tick = feed.next() ?: return@repeat
        rolling6hHigh.update(tick)
        println("rolling6hHigh=${rolling6hHigh.value()}")
    }
    feed.close()
    source.close()
}
```

### 9. Time-of-day strategy gating using `TradingCalendar.nyse()`

```kotlin
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy

class NyseOnlyStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {}

    override fun onTickWithContext(tick: Tick, ctx: SessionContext, emit: (Signal) -> Unit) {
        if (tick.symbol != symbol) return
        val nyse = TradingCalendar.nyse()
        val now = java.time.Instant.ofEpochMilli(ctx.clock.now())
        if (!nyse.isInSession(symbol, now)) return
        // ... your logic here
    }
}
```

### 10. Custom `WarmupSpec` (multi-strategy aggregation)

```kotlin
import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import com.qkt.strategy.samples.RollingHighBreakoutStrategy

fun main() {
    val source = TradingViewMarketSource.connect()
    val handle =
        LiveSession(
            strategies =
                listOf(
                    BreakoutOfYesterdayHighStrategy("OANDA:EURUSD"),
                    RollingHighBreakoutStrategy("OANDA:EURUSD"),
                ),
            source = source,
            symbols = listOf("OANDA:EURUSD"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
            // override the auto-derived widest spec with a hand-picked one
            warmupOverride = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 2880), // 48h of M1 bars
        ).start()
    handle.awaitTermination(java.time.Duration.ofMinutes(5))
    source.close()
}
```

### 11. Multi-vendor data composition (TV bars for warmup, TV live for steady state)

```kotlin
import com.qkt.app.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy
import java.time.Instant

fun main() {
    // TV's bar history doubles as the warmup source for a backtest.
    val tv = TradingViewMarketSource.connect(clock = SystemClock())
    val request =
        MarketRequest(
            symbols = listOf("OANDA:EURUSD"),
            from = Instant.parse("2024-01-15T00:00:00Z"),
            to = Instant.parse("2024-01-15T01:00:00Z"),
        )
    // Note: Backtest needs TICKS capability. TV does not advertise TICKS, so this combination falls back
    // to a LocalMarketSource for ticks and TV for warmup bars; in practice you wire that via CompositeMarketSource.
    // Shown here only to illustrate the TV.bars() warmup path.
    Backtest
        .fromSource(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")),
            source = tv, // throws if TICKS missing — see "Known limitations" below
            request = request,
            warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 200),
        ).run()
}
```

In practice, mix TV (bars) with `LocalMarketSource` (ticks) via `CompositeMarketSource` when you need a pure backtest.

---

## Testing patterns

### Canonical fakes

- `InMemoryMarketSource` (test-side) — seed `liveTicks` and `bars` with pre-computed sequences. Used in 7b's runtime tests and 7c's strategy tests.
- `FakeTradingViewWebSocket` (test-side) — implements `TradingViewWebSocketLike`. Records every `send(...)` and exposes `replay(frames)` plus `simulateConnect()` / `simulateDisconnect(reason)`. Used in all `TradingView*SessionTest` cases.

### Recorded WS fixtures

Located at `src/test/resources/tv-fixtures/`. Each `.jsonl` file is a sequence of TV WS payloads (no framing wrapper — one JSON object or `~h~N~h~` heartbeat per line). Tests load, parse via `TradingViewFrame.parse`, replay through a `FakeTradingViewWebSocket`.

### Look-ahead-bias guard

Tests asserting that an indicator or `MarketSource` cannot read beyond `clock.now()` use the canonical message:

```kotlin
assertThatThrownBy { source.ticks("X", futureRange).toList() }
    .hasMessageContaining("look-ahead bias")
```

The same wording is used by `LocalMarketSource`, `RangeAggregateIndicator`, and `TimeRange.of(...)`.

### Determinism assertion

```kotlin
val a = Backtest.fromSource(strategies, source, request, warmupSpec = WarmupSpec.Bars(...)).run()
val b = Backtest.fromSource(strategies, source, request, warmupSpec = WarmupSpec.Bars(...)).run()
assertThat(a.tradeCount).isEqualTo(b.tradeCount)
assertThat(a.totalPnL).isEqualByComparingTo(b.totalPnL)
```

Phase 7b's `BacktestWarmupTest.\`Bars warmup backtest is deterministic across two runs\`` is the reference pattern.

### Multi-vendor fan-in

`CompositeMarketSource` returns a `FanInTickFeed` when `liveTicks(symbols)` spans multiple sources. The fan-in is round-robin per `next()` call. Test harness:

```kotlin
val composite = CompositeMarketSource(routes = listOf(...), fallback = ...)
LiveSession(strategies, source = composite, symbols = ...).start().awaitTermination(...)
assertThat(strategy.seen.map { it.symbol }).containsExactlyInAnyOrder("OANDA:EURUSD", "BINANCE:BTCUSDT")
```

### `@Tag("e2e")` for live smoke tests

Excluded from default `./gradlew test`. Run on demand with `./gradlew test -PincludeTags=e2e`. Used by `TradingViewLiveSmokeTest` to validate the production WS path against real network.

---

## Known limitations

- **No real-broker integration.** All fills go through `MockBroker`, which fills at the latest in-process price. The `LiveBroker` interface and concrete `AlpacaBroker`, `IBKRBroker` implementations are deferred to Phase 7d / Phase 8.
- **TradingView authenticated mode not implemented.** Anonymous mode only. Premium symbol coverage and elevated rate limits will land when authentication is added; out of scope for Phase 7.
- **TradingView tick history not exposed.** TV's protocol does not provide it; `TradingViewMarketSource.ticks(...)` throws `UnsupportedDataException`. Use bar history for warmup.
- **NyseCalendar half-days not modeled.** Early-close days (day before Independence Day, day after Thanksgiving, Christmas Eve) are treated as full sessions. Documented in the Phase 7a calendar discussion; will be addressed in a future phase or backlog item.
- **No DST handling for the FX calendar.** `FxCalendar` uses a fixed 22:00 UTC cutoff for the weekly Sunday-open / Friday-close. The real FX market's cutoff shifts by an hour twice a year with US daylight saving. Acceptable for Phase 7; revisit when we add real-broker integrations whose timestamps are DST-aware.
- **No persistence of indicator state across restarts.** `LiveSession` rebuilds all indicator state from warmup at startup. Crash recovery via state checkpointing is deferred.
- **Multi-region / multi-instance live sessions not supported.** `LiveSession` is single-process, single-thread.
- **`CompositeMarketSource` live fan-in is round-robin, not strict timestamp arrival order.** Acceptable for Phase 7's spec invariant ("arrival order across vendors, not timestamp order"); revisit if bursty vendors cause starvation.
- **No additional vendors beyond TradingView.** Binance, OANDA, IBKR, etc. are out of scope. The `CompositeMarketSource` interface is the extensibility point; new vendors implement `MarketSource` and slot in.
- **DSL not shipped.** Phase 8. The `SessionContext` design is forward-compatible.

---

## References

- Spec: [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../superpowers/specs/2026-05-05-trading-engine-phase7-design.md)
- Plan 7a (refactor + abstractions): [`docs/superpowers/plans/2026-05-05-trading-engine-phase7a.md`](../superpowers/plans/2026-05-05-trading-engine-phase7a.md)
- Plan 7b (live runtime + warmup): [`docs/superpowers/plans/2026-05-05-trading-engine-phase7b.md`](../superpowers/plans/2026-05-05-trading-engine-phase7b.md)
- Plan 7c (TradingView + samples + demo): [`docs/superpowers/plans/2026-05-05-trading-engine-phase7c.md`](../superpowers/plans/2026-05-05-trading-engine-phase7c.md)
- Phase 6 baseline (historical data layer): [`docs/superpowers/specs/2026-05-04-trading-engine-phase6-design.md`](../superpowers/specs/2026-05-04-trading-engine-phase6-design.md)
- Merge SHAs (placeholder — fill in at merge time):
  - 7a merge: `__SHA_7A__`
  - 7b merge: `__SHA_7B__`
  - 7c merge: `__SHA_7C__`
- Prior art for the TradingView WS protocol: `Mathieu2301/TradingView-API` on GitHub (Node.js, ~3k stars; consulted for protocol details, no code copied).
