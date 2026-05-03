# qkt — Trading Engine Phase 2b Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 2b of the qkt trading platform. Adds a tick-driven, clock-aligned candle aggregator that subscribes to `TickEvent` and emits `CandleEvent`. Extends `Strategy` with a default-no-op `onCandle`. Extends `MockTickFeed` with `tickIntervalMs` so tick streams can span window boundaries. Phase 3 (risk engine) is the next phase after 2b ships.
**Phase 2a baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase2a-design.md`](2026-05-03-trading-engine-phase2a-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. Phase 2a delivered the event bus + multi-strategy + SLF4J. Phase 2b delivers the candle layer on top of that bus.

This document is **Phase 2b only**.

## 2. Phase 2b — what we are building

A new `CandleAggregator` that:

1. Subscribes to `TickEvent` on the bus from Phase 2a.
2. Maintains per-symbol in-flight OHLCV state aligned to wall-clock window boundaries (e.g., `[12:00, 12:01)` for 1-minute candles).
3. When a tick arrives whose `timestamp >= state.endTime`, finalizes the closed window and `bus.publish(CandleEvent(closedCandle))`. The triggering tick starts a new window.
4. Empty windows (no tick during a 1-minute interval) are silently skipped — no synthetic candle, no event.

A new `TimeWindow` value type (`@JvmInline value class`) wraps the window duration in milliseconds with named constants (`ONE_MINUTE`, `FIVE_MINUTES`, etc.) and helpers `windowStartFor(ts)` / `windowEndFor(ts)`.

The `Strategy` interface gains `fun onCandle(candle: Candle, emit: (Signal) -> Unit) {}` — default no-op. Existing strategies (only `EveryNthTickBuyStrategy` today) keep compiling unchanged. New strategies can override `onCandle`, `onTick`, or both.

`MockTickFeed` gains a `tickIntervalMs: Long = 1_000L` parameter. Each successive tick gets `startTime + emitted * tickIntervalMs` so the tick stream spans real time and exercises window rolls in `./gradlew run`.

`Main.kt` instantiates `CandleAggregator(bus, TimeWindow.ONE_MINUTE)` BEFORE strategy subscriptions are registered (preserves the depth-first invariant: a tick that triggers a window roll causes `Strategy.onCandle` to fire before `Strategy.onTick` for the same tick).

End condition: Phase 2a acceptance criteria still hold. New criteria added: `./gradlew run` produces both `FILLED:` and `CANDLE:` log lines; the aggregator + interface + extended feed pass their tests; total test count grows from 44 to ~65.

**Phase 2b does NOT include** (deferred):

- Multi-window aggregation (1m + 5m simultaneously)
- Synthetic empty-window candles
- Timer-based candle closures
- bid/ask-aware OHLC
- VWAP per candle
- Late-tick correction events
- Aggregator persistence across restarts
- Multiple feeds / feed multiplexer
- Async / coroutines
- Risk validation (Phase 3)

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Window boundaries are clock-aligned.** `windowStart = (timestamp / durationMs) * durationMs`. | Industry standard. Multi-symbol coherence (every symbol's 12:00 candle covers the same wall-clock window). Backtest output matches historical data files. Math is one integer division. |
| D2 | **Tick-driven close only — no timers.** A tick whose `timestamp >= state.endTime` triggers the previous window's closure. | Single-threaded by design (Phase 2a D2). No scheduler thread. Backtest determinism falls out automatically. The "no ticks for minutes" edge case is rare in real markets and is exactly when you don't want to trade. |
| D3 | **Empty windows are skipped — no synthetic candles.** | Real market data stays real. A 20-candle moving average over real candles is a real average; over synthetic flats it's contaminated. Aligns with how providers (Binance, Polygon, IBKR) deliver historical data. |
| D4 | **One aggregator instance handles all symbols.** Internally holds `Map<String, MutableCandle>`. Single subscription to `TickEvent`. | Multi-symbol is the common case. Symbols appear lazily as ticks arrive. Mirrors `MarketPriceTracker`'s pattern. Doesn't preclude per-symbol or per-(symbol, window) instances later. |
| D5 | **`Strategy.onCandle` added with default no-op; `onTick` stays abstract.** | Backward-compatible — `EveryNthTickBuyStrategy` ships unchanged. Strategies can use ticks, candles, or both. Keeping `onTick` abstract prevents accidental no-op strategies from compiling. |
| D6 | **`@JvmInline value class TimeWindow(val durationMs: Long)`** with named constants and `windowStartFor` / `windowEndFor` helpers. | Type-safe at the call site (compiler rejects raw `Long`). Zero runtime cost (compiles to `Long`). Open-ended (any duration), with named constants for common values. |
| D7 | **Aggregator subscribes to `TickEvent` BEFORE strategies do.** Strategy.onCandle (during nested dispatch) runs before Strategy.onTick (in outer forEach). | Strategies that maintain an indicator buffer (e.g., last-20-candle EMA) get the buffer updated *before* they react to the current tick. The depth-first dispatch from Phase 2a (D11) makes this work without bus changes. |
| D8 | **Volume mixed-mode.** OHLC always updated from `tick.price`; volume only added when `tick.volume != null`. | Real feeds may or may not include volume. Throwing away ticks that lack volume (per a strict null policy) loses price information. Treating null-volume as zero contribution preserves price action while honoring the "no volume info" reality. |
| D9 | **`MockTickFeed` extended with `tickIntervalMs: Long = 1_000L`.** Backward-compatible default; one Phase 1 timestamp test gets a one-line update. | Phase 1's feed produced all ticks at the same timestamp — fine when nothing checked timestamps. Phase 2b makes timestamps central; the fix is overdue. |
| D10 | **`MutableCandle` is a private nested class inside `CandleAggregator`.** Mutable in-place updates for OHLCV; converts to immutable `Candle` only at window close. | Avoids per-tick allocation (one `Candle` per window roll, not per tick). Encapsulated behind `private`; consumers see only the immutable `Candle` in the emitted event. Standard pattern for hot-path aggregators. |
| D11 | **Aggregator subscribes via constructor side-effect.** No separate `start()` method. | The aggregator has no purpose except bus subscription. Splitting construct + start would create meaningless two-step init. Consistent with how every other component in this codebase wires itself. |
| D12 | **`MutableCandle.startTime` matches `TimeWindow.windowStartFor(firstTickTimestamp)`, NOT the first tick's actual timestamp.** | Clock-aligned per D1. A candle that includes a single tick at 12:00:23 reports startTime=12:00:00, endTime=12:01:00. Standard behavior of every aggregator handling mid-window startup. |

## 4. Architecture

### 4.1 Pipeline

```
                                 ┌────────────────────────────────────────┐
                                 │              EventBus                  │
                                 └────────────────────────────────────────┘
                                  ▲    ▲                              │
                                  │    │ publish CandleEvent          │ dispatch
                                  │    │                              ▼
┌──────────────┐ pull ┌─────────┐ │    │       ┌─────────────────────────────────┐
│ MockTickFeed │─────▶│ Engine  │ │    │       │ subscribers (in main(), order): │
└──────────────┘      │         │─┘    │       │  1. CandleAggregator (TickEvent)│
                      │ updates │      │       │  2. each Strategy (TickEvent)   │
                      │ tracker │ ─────┘       │  3. each Strategy (CandleEvent) │
                      │ publish │              │  4. signal→order (SignalEvent)  │
                      │TickEvent│              │  5. broker handler (OrderEvent) │
                      └─────────┘              │  6. trade logger (TradeEvent)   │
                                               │  7. candle logger (CandleEvent) │
                                               └─────────────────────────────────┘
                                                              │
                                  ┌───────────────────────────┘
                                  ▼
                        ┌──────────────────────┐
                        │  CandleAggregator    │
                        │ Map<symbol, MutCandle> │
                        │ tick.ts >= endTime   │
                        │   → publish + roll   │
                        └──────────────────────┘
```

### 4.2 One TickEvent that triggers a window roll

Tick at 12:01:14 arrives. Aggregator's open candle for that symbol is `[12:00, 12:01)`.

```
1. engine.onTick(tick)
   1a. priceTracker.update(symbol, price)
   1b. bus.publish(TickEvent(tick))                              ← bus stamps ts/seq
       2a. CandleAggregator subscriber runs:
             tick.timestamp (12:01:14) >= state.endTime (12:01:00) → roll
             builds Candle from open state
             bus.publish(CandleEvent(closedCandle))              ← bus stamps ts/seq
                3a. Strategy.onCandle(closedCandle, emit) runs (depth-first)
                    may emit Signal → bus.publish(SignalEvent(...))
                       (signal handler builds order, broker fills, trade logged)
                3b. CandleEvent logger logs at INFO
             [aggregator handler resumes]
             starts new MutableCandle for window [12:01:00, 12:02:00)
             updates new state with the current tick
       2b. Strategy.onTick(tick, emit) runs
           (sees the just-closed candle in its buffer; reacts to current tick)
       2c. (more strategies / subscribers in registration order)
```

The key invariant: aggregator runs first among `TickEvent` subscribers (because it subscribed first in `main()`). The depth-first `bus.publish(CandleEvent(...))` runs all `CandleEvent` subscribers to completion before control returns to the outer `forEach`. So strategies see `onCandle(closedCandle)` before `onTick(currentTick)`.

### 4.3 One TickEvent that does NOT trigger a roll

Tick at 12:00:45, inside the open `[12:00, 12:01)` window:

```
1. engine.onTick(tick)
   1a. priceTracker.update
   1b. bus.publish(TickEvent(tick))
       2a. CandleAggregator: state.update(tick) — high/low/close/volume update in place; no publish
       2b. Strategy.onTick(tick) runs
       2c. ...
```

No `CandleEvent`. Cheap fast path: one map lookup + in-place update.

### 4.4 Component dependencies (Phase 2b delta)

```
events    ──▶ marketdata, execution, strategy, common         # adds CandleEvent (already imports Candle)
strategy  ──▶ marketdata, common                              # interface adds onCandle(Candle, emit)
candles   ──▶ events, marketdata, common, bus                 # NEW package
app       ──▶ ... candles                                     # main() instantiates CandleAggregator
```

New package `com.qkt.candles` for `CandleAggregator` and `TimeWindow`. No cycles.

### 4.5 What changes from Phase 2a

- `events/Event.kt` — adds `CandleEvent(candle, timestamp = 0L, sequenceId = 0L)`. Sealed-class enforcement makes downstream `when (event)` exhaustive checks (in `EventBus.stamp`) compile-fail until the branch is added.
- `bus/EventBus.kt` — `stamp` adds `is CandleEvent ->` branch.
- `strategy/Strategy.kt` — adds `fun onCandle(candle, emit) {}` default no-op.
- `marketdata/MockTickFeed.kt` — gains `tickIntervalMs: Long = 1_000L` parameter; timestamps become `startTime + emitted * tickIntervalMs`.
- `marketdata/MockTickFeedTest.kt` — one assertion update (`each tick has clock's current timestamp` becomes `each tick has clock's start time plus interval offset`); two new tests for the timestamp progression.
- `app/Main.kt` — instantiates `CandleAggregator(bus, TimeWindow.ONE_MINUTE)` before strategy wiring; subscribes each strategy to `CandleEvent` alongside `TickEvent`; adds a `CandleEvent` logger subscriber.

### 4.6 What does NOT change

- `Tick`, `Candle`, `Order`, `OrderType`, `Trade`, `Side`, `Signal` data classes — unchanged.
- `Engine`, `MarketPriceTracker`, `MarketPriceProvider`, `Broker`, `MockBroker`, `Clock`, `IdGenerator`, `SequenceGenerator` — unchanged.
- `EveryNthTickBuyStrategy` — unchanged. Inherits the default no-op `onCandle`.
- `EngineTest`, `MockBrokerTest`, `MarketPriceTrackerTest`, `EveryNthTickBuyStrategyTest`, `MonotonicSequenceGeneratorTest` — unchanged.
- `EventBusTest` and `EndToEndTest` get *additions*, not modifications.

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/
│   └── Main.kt                                # extended — adds CandleAggregator wiring
├── broker/                                    # unchanged
├── bus/
│   └── EventBus.kt                            # extended — adds CandleEvent stamp branch
├── candles/                                   # NEW package
│   ├── CandleAggregator.kt                    # NEW
│   └── TimeWindow.kt                          # NEW
├── common/                                    # unchanged
├── engine/                                    # unchanged
├── events/
│   └── Event.kt                               # extended — adds CandleEvent variant
├── execution/                                 # unchanged
├── marketdata/
│   ├── Candle.kt                              # unchanged
│   ├── MarketPriceProvider.kt                 # unchanged
│   ├── MockTickFeed.kt                        # extended — adds tickIntervalMs
│   ├── Tick.kt                                # unchanged
│   └── TickFeed.kt                            # unchanged
└── strategy/
    ├── EveryNthTickBuyStrategy.kt             # unchanged
    ├── Signal.kt                              # unchanged
    └── Strategy.kt                            # extended — adds onCandle default no-op

src/test/kotlin/com/qkt/
├── app/
│   └── EndToEndTest.kt                        # extended — +3 candle integration tests
├── broker/MockBrokerTest.kt                   # unchanged
├── bus/
│   └── EventBusTest.kt                        # extended — +1 CandleEvent stamp test
├── candles/                                   # NEW
│   ├── CandleAggregatorTest.kt                # NEW (~10 tests)
│   └── TimeWindowTest.kt                      # NEW (~5 tests)
├── common/MonotonicSequenceGeneratorTest.kt   # unchanged
├── engine/EngineTest.kt                       # unchanged
├── marketdata/
│   ├── MarketPriceTrackerTest.kt              # unchanged
│   └── MockTickFeedTest.kt                    # extended — 1 modified, +2 new tests
└── strategy/EveryNthTickBuyStrategyTest.kt    # unchanged
```

Two new packages: `com.qkt.candles` (production) and `com.qkt.candles` (test).

## 6. Type & interface signatures

### 6.1 `candles.TimeWindow`

```kotlin
package com.qkt.candles

@JvmInline
value class TimeWindow(val durationMs: Long) {
    init { require(durationMs > 0L) { "durationMs must be > 0: $durationMs" } }

    companion object {
        val ONE_SECOND = TimeWindow(1_000L)
        val ONE_MINUTE = TimeWindow(60_000L)
        val FIVE_MINUTES = TimeWindow(300_000L)
        val FIFTEEN_MINUTES = TimeWindow(900_000L)
        val ONE_HOUR = TimeWindow(3_600_000L)
    }

    fun windowStartFor(timestamp: Long): Long = (timestamp / durationMs) * durationMs
    fun windowEndFor(timestamp: Long): Long = windowStartFor(timestamp) + durationMs
}
```

### 6.2 `candles.CandleAggregator`

```kotlin
package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

class CandleAggregator(
    private val bus: EventBus,
    private val window: TimeWindow,
) {
    private val open = mutableMapOf<String, MutableCandle>()

    init {
        bus.subscribe<TickEvent> { event -> handle(event.tick) }
    }

    private fun handle(tick: Tick) {
        val state = open[tick.symbol]
        if (state == null) {
            open[tick.symbol] = newState(tick)
            return
        }
        if (tick.timestamp >= state.endTime) {
            bus.publish(CandleEvent(state.toCandle()))
            open[tick.symbol] = newState(tick)
            return
        }
        state.update(tick)
    }

    private fun newState(tick: Tick): MutableCandle {
        val start = window.windowStartFor(tick.timestamp)
        val end = start + window.durationMs
        return MutableCandle(
            symbol = tick.symbol,
            open = tick.price,
            high = tick.price,
            low = tick.price,
            close = tick.price,
            volume = tick.volume ?: 0.0,
            startTime = start,
            endTime = end,
        )
    }

    private class MutableCandle(
        val symbol: String,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var volume: Double,
        val startTime: Long,
        val endTime: Long,
    ) {
        fun update(tick: Tick) {
            if (tick.price > high) high = tick.price
            if (tick.price < low) low = tick.price
            close = tick.price
            if (tick.volume != null) volume += tick.volume
        }

        fun toCandle(): Candle = Candle(symbol, open, high, low, close, volume, startTime, endTime)
    }
}
```

### 6.3 `events.Event` (extension)

```kotlin
data class CandleEvent(
    val candle: Candle,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()
```

Added alongside the existing `TickEvent`, `SignalEvent`, `OrderEvent`, `TradeEvent`.

### 6.4 `bus.EventBus` (extension)

`stamp` gains one branch:

```kotlin
private fun stamp(event: Event): Event {
    val ts = clock.now()
    val seq = sequencer.next()
    return when (event) {
        is TickEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is CandleEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is OrderEvent -> event.copy(timestamp = ts, sequenceId = seq)
        is TradeEvent -> event.copy(timestamp = ts, sequenceId = seq)
    }
}
```

Sealed-class exhaustiveness causes compile error if a future event variant is added without a stamp branch — desired property.

### 6.5 `strategy.Strategy` (extension)

```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(tick: Tick, emit: (Signal) -> Unit)
    fun onCandle(candle: Candle, emit: (Signal) -> Unit) {}
}
```

### 6.6 `marketdata.MockTickFeed` (extension)

```kotlin
class MockTickFeed(
    private val symbol: String,
    private val startPrice: Double,
    private val count: Int,
    private val clock: Clock,
    private val tickIntervalMs: Long = 1_000L,
    private val random: Random = Random(seed = 42L),
) : TickFeed {
    init {
        require(count >= 0) { "count must be >= 0: $count" }
        require(startPrice > 0.0) { "startPrice must be > 0: $startPrice" }
        require(tickIntervalMs > 0L) { "tickIntervalMs must be > 0: $tickIntervalMs" }
    }

    private val startTime = clock.now()
    private var emitted = 0
    private var price = startPrice

    override fun next(): Tick? {
        if (emitted >= count) return null
        price *= (1.0 + (random.nextDouble() - 0.5) * 0.01)
        val ts = startTime + emitted * tickIntervalMs
        emitted++
        return Tick(symbol, price, ts)
    }
}
```

### 6.7 `app.Main` (extension)

Adds CandleAggregator wiring + per-strategy CandleEvent subscription + CandleEvent logger.

```kotlin
fun main() {
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()

    val bus = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    CandleAggregator(bus, TimeWindow.ONE_MINUTE)   // subscribes BEFORE strategies

    val strategies: List<Strategy> =
        listOf(
            EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0),
        )

    strategies.forEach { strategy ->
        bus.subscribe<TickEvent> { e ->
            strategy.onTick(e.tick) { signal -> bus.publish(SignalEvent(signal)) }
        }
        bus.subscribe<CandleEvent> { e ->
            strategy.onCandle(e.candle) { signal -> bus.publish(SignalEvent(signal)) }
        }
    }

    bus.subscribe<SignalEvent> { e ->
        bus.publish(OrderEvent(e.signal.toOrder(ids.next(), clock.now())))
    }
    bus.subscribe<OrderEvent> { e ->
        broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
    }
    bus.subscribe<TradeEvent> { e ->
        val t = e.trade
        log.info("FILLED: {} {} {} @ {}", t.side, t.quantity, t.symbol, t.price)
    }
    bus.subscribe<CandleEvent> { e ->
        val c = e.candle
        log.info("CANDLE: {} O={} H={} L={} C={} V={} [{}, {})", c.symbol, c.open, c.high, c.low, c.close, c.volume, c.startTime, c.endTime)
    }

    val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock, tickIntervalMs = 1_000L)
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}
```

`Signal.toOrder` extension is unchanged from Phase 2a.

## 7. Build configuration changes

None. Phase 2b adds no new dependencies. SLF4J (Phase 2a), JUnit 5, AssertJ, ktlint already in place.

## 8. Error handling

| Condition | Behavior | Type |
|---|---|---|
| `TimeWindow(0L)` or negative | `require(...)` throws on construction | Crash (bug) |
| `MockTickFeed(tickIntervalMs <= 0)` | `require(...)` throws on construction | Crash (bug) |
| `Strategy.onCandle` throws | Exception propagates through aggregator's nested `bus.publish` → out of outer publish → out of engine.onTick → out of main loop | Crash (bug) |
| Tick with `volume = null` | Volume not added to candle; OHLC still updated | Documented |
| Tick with `tick.timestamp < state.startTime` (out-of-order, mid-window) | Folds into current candle (no roll, no special handling) | Documented |
| Tick with `tick.timestamp` in an already-closed window | Same as above — the aggregator does not retroactively reopen | Documented |
| Tick with NaN/Infinite price | High/low compare with NaN produces undefined behavior; aggregator does not validate | Tolerated (Phase 1 policy: trust callers) |
| `bus.publish(TickEvent)` with no aggregator subscribed | No-op — no candle ever produced. Aggregator must be wired in `main()`. | Documented |
| Multiple aggregators on the same bus with the same window | Each emits its own `CandleEvent` for the same closed window (duplicate events). Don't do this. | Documented |
| Strategy publishes `TickEvent` from inside `onCandle` | Re-entrant tick lands in newly created MutableCandle state. Handled honestly. | Tolerated |

No try/catch in Phase 2b production code. Production-grade isolation is deferred per Phase 2a D6.

## 9. Testing strategy

### 9.1 Test files

| File | Class under test | Test count |
|---|---|---|
| `TimeWindowTest.kt` | `TimeWindow` | ~5 |
| `CandleAggregatorTest.kt` | `CandleAggregator` | ~10 |
| `EventBusTest.kt` (extension) | `EventBus` adds CandleEvent stamp test | +1 |
| `MockTickFeedTest.kt` (extension) | `MockTickFeed` updated test + new timestamp progression | +2 |
| `EndToEndTest.kt` (extension) | full pipeline including candle path | +3 |

Existing Phase 2a test files for unrelated components (`MarketPriceTrackerTest`, `MockBrokerTest`, `EveryNthTickBuyStrategyTest`, `EngineTest`, `MonotonicSequenceGeneratorTest`) are unchanged.

### 9.2 TimeWindowTest cases

- `windowStartFor` rounds down to nearest multiple of `durationMs`.
- `windowEndFor` returns `windowStartFor + durationMs`.
- `windowStartFor` is idempotent on a boundary timestamp (`windowStartFor(60_000) == 60_000`).
- Throws on zero or negative `durationMs`.
- Named constants have expected duration values.

### 9.3 CandleAggregatorTest cases

- First tick for a symbol does not emit a `CandleEvent`.
- Tick within current window updates OHLC in place without emitting.
- Tick past current `endTime` emits `CandleEvent` for the closed window.
- Closed candle has correct OHLC computed from all ticks in the window (open, high, low, close).
- Closed candle's `startTime` is window-aligned, not first-tick timestamp (D12).
- Closed candle's `endTime == startTime + durationMs`.
- Volume sums only non-null tick volumes (D8).
- Windows for different symbols are tracked independently.
- Boundary timestamp triggers window roll (`tick.timestamp == state.endTime` rolls the candle).
- Multiple consecutive rolls each emit one `CandleEvent`.

Setup pattern (real types only, no mocks):

```kotlin
private val clock = FixedClock(0L)
private val sequencer = MonotonicSequenceGenerator()
private val bus = EventBus(clock, sequencer)
private val aggregator = CandleAggregator(bus, TimeWindow.ONE_MINUTE)
private val captured = mutableListOf<CandleEvent>()
init { bus.subscribe<CandleEvent> { captured.add(it) } }

private fun tick(symbol: String, price: Double, ts: Long, volume: Double? = null) =
    bus.publish(TickEvent(Tick(symbol, price, ts, volume)))
```

### 9.4 EventBusTest extension

- `bus stamps CandleEvent on publish` — same shape as existing TickEvent stamping test.

### 9.5 MockTickFeedTest extension

- `each tick has clock's start time plus interval offset` — modified existing test; asserts `tick0.timestamp == startTime`, `tick1.timestamp == startTime + 1_000L`, etc.
- `tickIntervalMs default is 1000L` — new test confirming default.
- `throws on non-positive tickIntervalMs` — new test for `0L` and `-1L`.

### 9.6 EndToEndTest extension

- Tick stream spanning a window boundary produces a `CandleEvent`.
- Strategy receiving `onCandle` can emit a signal that fills.
- Aggregator subscribes before strategies see the same tick (verifies D7 wiring invariant).

The third test is critical: a strategy captures the order of its own `onTick` and `onCandle` calls; publish a tick that triggers a window roll; assert order is `onCandle(closedCandle)` before `onTick(triggeringTick)`.

### 9.7 Total test count

| Source | Phase 2a | Phase 2b delta | Phase 2b total |
|---|---|---|---|
| `MarketPriceTrackerTest` | 4 | 0 | 4 |
| `MockTickFeedTest` | 6 | +2 | 8 |
| `EveryNthTickBuyStrategyTest` | 5 | 0 | 5 |
| `MockBrokerTest` | 8 | 0 | 8 |
| `EngineTest` | 2 | 0 | 2 |
| `MonotonicSequenceGeneratorTest` | 3 | 0 | 3 |
| `EventBusTest` | 10 | +1 | 11 |
| `EndToEndTest` | 6 | +3 | 9 |
| `TimeWindowTest` | 0 | +5 | 5 |
| `CandleAggregatorTest` | 0 | +10 | 10 |
| **Total** | **44** | **+21** | **65** |

### 9.8 Conventions

- JUnit 5 + AssertJ. No mocking framework.
- All deterministic: `FixedClock`, fresh `MonotonicSequenceGenerator` per test, `Random(seed = 42L)` defaults.
- Real types throughout. Anonymous objects for one-off `Strategy` impls.
- Capture lists (`mutableListOf<CandleEvent>()`) for assertions.
- ktlint runs as part of `./gradlew check`.

## 10. Build & run

Commands unchanged from Phase 2a:

```
./gradlew test     # 65 tests
./gradlew run      # FILLED + CANDLE log lines + Done.
./gradlew build    # compile + test + ktlintCheck + assemble
./scripts/precheck.sh
```

Expected `./gradlew run` output (sample, prices vary by seed):

```
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2382.18...
[main] INFO Main - CANDLE: XAUUSD O=2400.0 H=2401.5 L=2398.2 C=2400.7 V=0.0 [<startTs>, <endTs>)
... more FILLED and CANDLE lines ...
[main] INFO Main - Done.
```

100 ticks at 1s intervals = ~100 seconds of stream. With 60-second windows, expect ~1-2 `CANDLE` events plus the existing 10 `FILLED` events. Number of CANDLE events depends on where the SystemClock starts relative to a minute boundary — test runs may produce 1, 2, or 3 candles. The exact count is not asserted in tests; presence of at least one CANDLE event is the integration test.

## 11. Out of scope (deferred to later phases)

| Feature | Phase |
|---|---|
| Multi-window aggregation (1m + 5m simultaneously) | When a strategy needs both — not yet |
| Synthetic empty-window candles | Future, via `GapFillingAggregator` wrapper |
| Timer-based candle closures | Future, when a strategy needs time-driven indicators without ticks |
| bid/ask-aware OHLC | Real-broker phase |
| VWAP / TWAP per candle | Future, when strategies need it |
| Late-tick correction events | Real-broker phase (if reordering occurs) |
| Aggregator persistence | Future, with state snapshots |
| Multiple feeds / feed multiplexer | Cross-symbol Phase or beyond |
| Multi-symbol `MockTickFeed` natively | Future, when a strategy needs cross-symbol exercise beyond pre-seeded tracker tests |
| Async / coroutines | post-Phase 5 (or live broker phase) |
| Risk validation | Phase 3 |
| Position tracking, P&L | Phase 3 |
| `BigDecimal` for prices | Phase 3 |
| Backtest replay engine | Phase 4 |
| External DSL parser | Phase 5 |

## 12. Migration from Phase 2a

Phase 2a is on `main`. Phase 2b lands on a feature branch (`phase2b-candles` per the project skill), reviewed and merged.

The migration is internal and additive: no existing test or behavior breaks. Phase 2a acceptance criteria still hold; new criteria added for the candle layer.

## 13. Acceptance criteria (Phase 2b done means)

- [ ] `TimeWindow`, `CandleAggregator` exist in `com.qkt.candles`.
- [ ] `CandleEvent` exists in `com.qkt.events`. `EventBus.stamp` handles it (compile-enforced via sealed-class exhaustiveness).
- [ ] `Strategy.onCandle(candle, emit)` exists with default no-op body.
- [ ] `MockTickFeed` accepts `tickIntervalMs` (default `1_000L`); timestamps progress as `startTime + emitted * interval`.
- [ ] All 65 tests pass.
- [ ] `./gradlew build` green.
- [ ] `./gradlew run` produces at least one `CANDLE:` log line and the expected `FILLED:` lines + `Done.`.
- [ ] `EveryNthTickBuyStrategy` continues to work unchanged; existing Phase 2a tests pass without modification (other than the explicitly noted MockTickFeed test).
- [ ] Aggregator subscribes BEFORE strategies in `main()` (verified by EndToEndTest).
- [ ] `git log` clean history with conventional commit messages.
- [ ] No file exceeds ~200 lines.

When all boxes are checked, Phase 2b is done and the candle layer is ready for use. Strategies can subscribe to `CandleEvent` for time-based decisions.
