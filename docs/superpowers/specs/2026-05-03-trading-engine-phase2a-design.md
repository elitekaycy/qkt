# qkt — Trading Engine Phase 2a Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 2a of the qkt trading platform. Replaces direct method calls between engine, strategy, and broker with a synchronous, type-keyed event bus. Adds multi-strategy support and SLF4J logging. Phase 2b (candle aggregator + `Strategy.onCandle`) is a separate, later spec.
**Phase 1 baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase1-design.md`](2026-05-03-trading-engine-phase1-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. The system supports market-data ingestion, strategy execution, risk management, order execution, and deterministic backtesting.

This document is **Phase 2a only**. Each phase has its own spec.

## 2. Phase 2a — what we are building

A refactor of the Phase 1 pipeline so that all inter-component communication runs through an event bus instead of direct method calls. The user-visible behavior is unchanged: `./gradlew run` still produces fills for the sample strategy. Internally:

1. The engine becomes a tiny tick-source — it updates the price tracker and publishes a `TickEvent`. Nothing else.
2. A new `EventBus` dispatches typed events to subscribers registered at startup.
3. New `Event` sealed class with variants `TickEvent`, `SignalEvent`, `OrderEvent`, `TradeEvent`. Each carries a payload (the existing Phase 1 type) plus `timestamp` and `sequenceId` stamped by the bus on publish.
4. `main()` wires the pipeline by registering subscribers: strategies on `TickEvent`, a signal-to-order builder on `SignalEvent`, the broker on `OrderEvent`, a printer on `TradeEvent`.
5. Multi-strategy support comes for free — each strategy is its own `TickEvent` subscriber.
6. SLF4J replaces `println` for system-level logging. Strategies, broker, tracker stay silent.

End condition: Phase 1 acceptance criteria still hold (`./gradlew build` green, `./gradlew run` produces ~10 `FILLED` lines + `Done.`, all tests pass) with the new architecture in place. Test count grows from 31 to ~44.

**Phase 2a does NOT include** (deferred):
- `Strategy.onCandle` interface extension — Phase 2b
- Candle aggregator — Phase 2b
- Risk validation — Phase 3
- Position tracking, P&L — Phase 3
- Coroutines / async event bus — post-Phase 5 (or live broker phase)
- Backtest replay (`subscribeAll`, event log) — Phase 4
- `BigDecimal` for prices — Phase 3

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Phase 2a scope = event bus + multi-strategy + SLF4J only.** Phase 2b (candles) is a separate cycle. | The bus is a refactor; candles are a feature. Mixing them would couple a refactor PR with a feature PR; harder to review, harder to revert. |
| D2 | **Stay single-threaded.** Synchronous publish-subscribe; no coroutines. | Backtest determinism (a Phase 4 hard requirement) is much harder under concurrent dispatch. No real parallelism win in Phase 2 since all components are mock or in-process. Coroutines arrive when there is a forcing reason, not by default. |
| D3 | **Type-keyed map bus.** `Map<KClass<out Event>, List<(Event) -> Unit>>`. `subscribe<T>` adds to the bucket for `T::class`; `publish(event)` dispatches to `subscribers[event::class]`. | Idiomatic event-bus pattern. Compile-time call-site type safety via `inline reified`. Cheap dispatch — subscribers to other event types are never touched. One central event vocabulary (`Event` sealed class). |
| D4 | **Engine becomes thin.** Owns the bus and price tracker. Receives ticks, updates tracker, publishes `TickEvent`. No `Strategy`, `Broker`, `Clock`, or `IdGenerator` constructor params anymore. | Routing logic was Phase 1 engine's responsibility because there was no bus. With a bus, routing moves to subscribers. Keeping the engine thin makes the tracker-update-before-publish invariant a code-level guarantee, not a registration-order convention. |
| D5 | **No `StrategyRegistry` class.** `main()` subscribes each strategy with a `forEach`. | YAGNI. Adding a registry is ceremony for the same outcome (`forEach { bus.subscribe<TickEvent> { ... } }`). Per-strategy subscription gives us natural isolation — one strategy throwing affects only its subscription chain. Registry earns its keep when registration becomes dynamic (Phase 5 DSL or live broker). |
| D6 | **Subscriber exceptions propagate.** Bus does not try/catch around handlers. | Phase 1's policy is "crash on bug, reject on valid runtime condition." A subscriber that throws is a code bug; bugs should crash loudly. Defensive isolation comes when there is a real production deployment to protect. |
| D7 | **SLF4J at engine + bus + main only.** Strategies, broker, tracker stay silent. `slf4j-api` 2.0.16 + `slf4j-simple` runtime. | Bus and engine are the system seams that benefit most from logging. Domain code stays clean. Default `slf4j-simple` config is zero-setup; logback can replace it later without code change. |
| D8 | **Events are wrappers + bus-stamped metadata.** `Event` sealed class with `timestamp: Long` and `sequenceId: Long`, both defaulted to `0L` and overwritten by the bus on publish. | `sequenceId` is load-bearing for Phase 4 backtest replay (Long timestamps aren't unique; sequence id is). Event-published `timestamp` is distinct from `Tick.timestamp` (market time vs publish time). Adding both later would break every event signature; cheaper to add now. |
| D9 | **Tracker update happens in `engine.onTick`, before `bus.publish(TickEvent)`.** Tracker is not a subscriber. | Order is enforced by code structure, not subscription registration order. A bug where someone reorders `main()` lines and the tracker subscribes after a strategy = silent stale-price reads — avoided entirely with this shape. Same invariant as Phase 1. |
| D10 | **`SequenceGenerator` is a new interface.** `MonotonicSequenceGenerator` returns `0, 1, 2, ...`. Separate from `IdGenerator` (which returns `String`). | Different concept (event sequence) from order ID. Bus needs `Long`; orders need `String`. Two interfaces are clean; one interface forced into both shapes is not. |
| D11 | **Re-entrant publication is supported, no nesting guard.** A subscriber publishing a different event type during dispatch is the intended pattern. A subscriber publishing the same event type → infinite loop → `StackOverflowError`. Acceptable failure mode for Phase 2a. | Re-entrancy across types is the whole pipeline (TickEvent → SignalEvent → OrderEvent → TradeEvent). Loop detection adds machinery for a problem that's a code bug — same logic as fail-fast for subscribers (D6). |
| D12 | **Subscribers must register before any publish.** No runtime registration. Mutating the subscriber list during `forEach` would CME. | All wiring happens in `main()` before the loop starts. Phase 2a has no use case for runtime registration. Phase 5 DSL may force this; backward-compatible to add (copy-on-write or registration queue). |

## 4. Architecture

### 4.1 Pipeline

```
                                 ┌──────────────────────────────────────┐
                                 │             EventBus                 │
                                 │  Map<KClass<Event>, List<handler>>   │
                                 └──────────────────────────────────────┘
                                  ▲                                    │ dispatch
                                  │ publish                            ▼
┌──────────────┐  pull  ┌─────────────┐                ┌───────────────────────────┐
│ MockTickFeed │───────▶│   Engine    │ TickEvent      │ subscribers (in main()):  │
└──────────────┘        │             │───────────────▶│  • each Strategy.onTick   │
                        │ updates     │                │    emits SignalEvent      │
                        │ tracker,    │                └───────────────────────────┘
                        │ publishes   │                              │
                        │ TickEvent   │                              ▼
                        └─────────────┘                ┌───────────────────────────┐
                              ▲                        │ subscribers:              │
                              │ feed.next()            │  • signal→order builder:  │
                              │                        │    publishes OrderEvent   │
                              │                        └───────────────────────────┘
                              │                                      │
                              │                                      ▼
                              │                        ┌───────────────────────────┐
                              │                        │ subscribers:              │
                              │                        │  • broker handler:        │
                              │                        │    broker.execute(order); │
                              │                        │    publishes TradeEvent   │
                              │                        └───────────────────────────┘
                              │                                      │
                              │                                      ▼
                              │                        ┌───────────────────────────┐
                              │                        │ subscribers:              │
                              │                        │  • println logger         │
                              │                        │  • (Phase 3+) more        │
                              │                        └───────────────────────────┘
                              │
                              └────── main() loop drives engine.onTick(...)
```

### 4.2 One Engine cycle, step by step

For each `engine.onTick(tick)`:

1. Engine writes `priceTracker.update(tick.symbol, tick.price)`.
2. Engine logs `log.debug("ingested tick {} @ {}", tick.symbol, tick.price)`.
3. Engine calls `bus.publish(TickEvent(tick))`. Bus stamps `timestamp` and `sequenceId`, logs at TRACE.
4. Each `TickEvent` subscriber runs in registration order:
   - Each strategy calls `onTick(tick) { signal -> bus.publish(SignalEvent(signal)) }`.
   - Strategy may emit zero or more signals.
5. Each `SignalEvent` subscriber runs (just one in Phase 2a):
   - Builds `Order` from `Signal` (`Signal.toOrder(id, ts)`), publishes `OrderEvent(order)`.
6. Each `OrderEvent` subscriber runs (just one in Phase 2a):
   - `broker.execute(order)` returns `Trade?`. If non-null, `bus.publish(TradeEvent(trade))`.
7. Each `TradeEvent` subscriber runs:
   - Logs the trade at INFO. (Phase 3 adds position tracker, etc.)
8. Cycle ends. Next `feed.next()`.

**Depth-first traversal:** when a subscriber publishes during dispatch, the inner publish completes fully before control returns to the outer `forEach`. This is what makes Phase 4 backtest determinism trivial — events flow in a single, predictable order.

### 4.3 Component dependencies

```
app       ──▶ engine, strategy, broker, marketdata, common, bus, events, execution
engine    ──▶ marketdata, common, bus, events
bus       ──▶ events, common
events    ──▶ marketdata, execution, strategy, common
strategy  ──▶ marketdata, common
broker    ──▶ execution, marketdata, common
execution ──▶ common
marketdata ─▶ common
common    ──▶ (nothing)
```

`common` is still the leaf. `app` is still the root. Two new packages: `bus` and `events`. The engine's dependency surface shrinks (no longer depends on strategy, broker, execution).

### 4.4 What changes from Phase 1

- `Engine.kt` — heavily simplified. Loses `Strategy`, `Broker`, `Clock`, `IdGenerator`, `onTrade` constructor params. Loses `Signal.toOrder`, `route`. Now ~15 lines.
- `Main.kt` — grows. Adds bus wiring. Now ~50 lines.
- `IdGenerator.kt` — extended. Adds `SequenceGenerator` interface and `MonotonicSequenceGenerator` impl.
- `EngineTest.kt` — heavily reduced. Loses 6 of 8 tests; what's left covers the thin engine's invariants.
- `build.gradle.kts` — adds SLF4J dependencies via the version catalog.
- `gradle/libs.versions.toml` — adds `slf4j` version + `slf4j-api`/`slf4j-simple` libraries.

### 4.5 What does not change

- `Tick`, `Candle`, `Order`, `OrderType`, `Trade`, `Side`, `Signal` — unchanged.
- `Clock`, `MarketPriceProvider`, `MarketPriceTracker` — unchanged.
- `Broker`, `MockBroker`, `TickFeed`, `MockTickFeed` — unchanged.
- `EveryNthTickBuyStrategy` — unchanged.
- `Strategy` interface — unchanged. `onTick(tick, emit)` still callback-based; the lambda body changes (now publishes a `SignalEvent`) but the interface is identical.
- All Phase 1 tests for these components — pass unchanged.

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/
│   └── Main.kt                            # rewritten — bus wiring
├── bus/                                   # NEW package
│   └── EventBus.kt                        # NEW
├── common/
│   ├── Clock.kt                           # unchanged
│   ├── IdGenerator.kt                     # extended with SequenceGenerator
│   └── Side.kt                            # unchanged
├── events/                                # NEW package
│   └── Event.kt                           # NEW (sealed + 4 variants)
├── engine/
│   └── Engine.kt                          # rewritten, thin
├── execution/                             # unchanged
│   ├── Order.kt
│   ├── OrderType.kt
│   └── Trade.kt
├── marketdata/                            # unchanged
│   ├── Candle.kt
│   ├── MarketPriceProvider.kt
│   ├── MockTickFeed.kt
│   ├── Tick.kt
│   └── TickFeed.kt
├── strategy/                              # unchanged
│   ├── EveryNthTickBuyStrategy.kt
│   ├── Signal.kt
│   └── Strategy.kt
└── broker/                                # unchanged
    ├── Broker.kt
    └── MockBroker.kt

src/test/kotlin/com/qkt/
├── app/
│   └── EndToEndTest.kt                    # NEW (~6 tests)
├── bus/                                   # NEW
│   └── EventBusTest.kt                    # NEW (~10 tests)
├── common/                                # NEW (existing common types had no dedicated tests)
│   └── MonotonicSequenceGeneratorTest.kt  # NEW (~3 tests)
├── engine/
│   └── EngineTest.kt                      # rewritten — much smaller (~2 tests)
├── broker/MockBrokerTest.kt               # unchanged
├── strategy/EveryNthTickBuyStrategyTest.kt # unchanged
└── marketdata/                            # unchanged
    ├── MarketPriceTrackerTest.kt
    └── MockTickFeedTest.kt
```

## 6. Type & interface signatures

### 6.1 `events.Event`

```kotlin
package com.qkt.events

import com.qkt.execution.Order
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal

sealed class Event {
    abstract val timestamp: Long
    abstract val sequenceId: Long
}

data class TickEvent(
    val tick: Tick,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class SignalEvent(
    val signal: Signal,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class OrderEvent(
    val order: Order,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()

data class TradeEvent(
    val trade: Trade,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()
```

### 6.2 `bus.EventBus`

```kotlin
package com.qkt.bus

import com.qkt.common.Clock
import com.qkt.common.SequenceGenerator
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

class EventBus(
    private val clock: Clock,
    private val sequencer: SequenceGenerator,
) {
    private val log = LoggerFactory.getLogger(EventBus::class.java)
    private val subscribers = mutableMapOf<KClass<out Event>, MutableList<(Event) -> Unit>>()

    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        subscribers
            .getOrPut(T::class) { mutableListOf() }
            .add { event -> handler(event as T) }
    }

    fun publish(event: Event) {
        val stamped = stamp(event)
        log.trace("publish {} seq={} ts={}", stamped::class.simpleName, stamped.sequenceId, stamped.timestamp)
        subscribers[stamped::class]?.forEach { it(stamped) }
    }

    private fun stamp(event: Event): Event {
        val ts = clock.now()
        val seq = sequencer.next()
        return when (event) {
            is TickEvent   -> event.copy(timestamp = ts, sequenceId = seq)
            is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is OrderEvent  -> event.copy(timestamp = ts, sequenceId = seq)
            is TradeEvent  -> event.copy(timestamp = ts, sequenceId = seq)
        }
    }
}
```

### 6.3 `common.IdGenerator` (extended)

```kotlin
package com.qkt.common

interface IdGenerator { fun next(): String }
class SequentialIdGenerator(private val prefix: String = "ORD") : IdGenerator {
    private var counter = 0L
    override fun next(): String = "$prefix-${counter++}"
}

interface SequenceGenerator { fun next(): Long }
class MonotonicSequenceGenerator : SequenceGenerator {
    private var counter = 0L
    override fun next(): Long = counter++
}
```

### 6.4 `engine.Engine` (rewritten)

```kotlin
package com.qkt.engine

import com.qkt.bus.EventBus
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.slf4j.LoggerFactory

class Engine(
    private val bus: EventBus,
    private val priceTracker: MarketPriceTracker,
) {
    private val log = LoggerFactory.getLogger(Engine::class.java)

    fun onTick(tick: Tick) {
        priceTracker.update(tick.symbol, tick.price)
        log.debug("ingested tick {} @ {}", tick.symbol, tick.price)
        bus.publish(TickEvent(tick))
    }
}
```

### 6.5 `app.Main` (rewritten)

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val clock        = SystemClock()
    val ids          = SequentialIdGenerator()
    val sequencer    = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()

    val bus    = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    val strategies: List<Strategy> = listOf(
        EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0),
    )

    strategies.forEach { strategy ->
        bus.subscribe<TickEvent> { e ->
            strategy.onTick(e.tick) { signal -> bus.publish(SignalEvent(signal)) }
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

    val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock)
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}

private fun Signal.toOrder(id: String, ts: Long): Order = when (this) {
    is Signal.Buy  -> Order(id, symbol, Side.BUY,  size, OrderType.MARKET, null, ts)
    is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
}
```

## 7. Build configuration changes

### 7.1 `gradle/libs.versions.toml` (additions)

```toml
[versions]
# existing entries unchanged
slf4j = "2.0.16"

[libraries]
# existing entries unchanged
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
```

### 7.2 `build.gradle.kts` `dependencies` block

```kotlin
dependencies {
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

`slf4j-simple` defaults: stderr, INFO level, no timestamps. Adjustable via JVM system properties (`-Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG`, `-Dorg.slf4j.simpleLogger.showDateTime=true`).

## 8. Error handling

| Condition | Behavior | Type |
|---|---|---|
| Subscriber lambda throws | Exception propagates out of `bus.publish` and out of caller (engine, main loop). JVM crashes with stack trace. | Crash (bug) |
| `bus.publish` on event with no subscribers | No-op. Logged at TRACE. | Valid |
| Subscriber publishes same event type recursively | Infinite recursion → `StackOverflowError`. | Crash (bug) |
| Subscriber registration during dispatch | `ConcurrentModificationException` on the `forEach`. | Crash (programming error) |
| `MonotonicSequenceGenerator` overflow at `Long.MAX_VALUE` | Wraps to negative. Not a Phase 2 concern (~292 million years at 1B events/sec). | Tolerated |
| Engine receives null tick | Compile error — `onTick(tick: Tick)` is non-nullable. | n/a |
| `Event.timestamp` defaulted `0L` left unstamped | Only possible if event is constructed but never published through the bus. Tests that construct events directly will see `0L`. | Documented behavior |

No try/catch is added in Phase 2a. Production-grade subscriber isolation is deferred until there is a real production deployment to protect.

## 9. Testing strategy

### 9.1 Test files

| File | Class under test | Test count |
|---|---|---|
| `EventBusTest.kt` | `EventBus` | ~10 |
| `MonotonicSequenceGeneratorTest.kt` | `MonotonicSequenceGenerator` | ~3 |
| `EngineTest.kt` (rewritten) | `Engine` (thin) | ~2 |
| `EndToEndTest.kt` (in `app/`) | full pipeline | ~6 |

Existing Phase 1 test files (`MockBrokerTest`, `EveryNthTickBuyStrategyTest`, `MarketPriceTrackerTest`, `MockTickFeedTest`) are **unchanged**.

### 9.2 EventBusTest cases

- Publish with no subscribers is a no-op.
- Subscribe + publish invokes the handler with the event.
- Bus stamps timestamp from clock on publish.
- Bus stamps monotonic sequenceId on publish (0, 1, 2, ...).
- Multiple subscribers to same event run in registration order.
- Subscribers to different event types are isolated.
- Subscriber publishing different event type runs depth-first.
- Subscriber exception propagates out of publish.
- Published event default timestamp and sequenceId are overwritten.
- Each event type retains its own subscriber list.

### 9.3 MonotonicSequenceGeneratorTest cases

- `next` returns 0 first.
- `next` is monotonically increasing.
- Independent instances have independent counters.

### 9.4 EngineTest cases (rewritten)

- `onTick` updates `priceTracker` before publishing `TickEvent` (subscribed handler reads `lastPrice` and sees the new value).
- `onTick` publishes `TickEvent` carrying the same `Tick`.

### 9.5 EndToEndTest cases

- Single-strategy buy on every tick produces a fill.
- Signal for unknown symbol produces no fill (broker returns null).
- Multiple strategies all see the same tick.
- Order ids are sequential across multiple signals.
- Multiple signals from one tick all fill at the same tracker price.
- Cross-symbol strategy emits a signal for symbol B from a tick of symbol A. Test pre-seeds the price tracker with symbol B's price before publishing a TickEvent for symbol A; asserts the resulting trade fills at symbol B's pre-seeded price.

### 9.6 Total test count

| Source | Phase 1 | Phase 2a delta | Phase 2a total |
|---|---|---|---|
| `MarketPriceTrackerTest` | 4 | 0 | 4 |
| `MockTickFeedTest` | 6 | 0 | 6 |
| `EveryNthTickBuyStrategyTest` | 5 | 0 | 5 |
| `MockBrokerTest` | 8 | 0 | 8 |
| `EngineTest` | 8 | -6 | 2 |
| `MonotonicSequenceGeneratorTest` | 0 | +3 | 3 |
| `EventBusTest` | 0 | +10 | 10 |
| `EndToEndTest` | 0 | +6 | 6 |
| **Total** | **31** | **+13** | **44** |

### 9.7 Conventions

- JUnit 5 + AssertJ. No mocking framework.
- All deterministic via `FixedClock` and fresh `MonotonicSequenceGenerator` per test.
- Real types throughout. Anonymous objects (`object : Strategy { ... }`) for one-off impls.
- Capture lists (`mutableListOf<Event>()`) for assertions on what fired.
- ktlint runs as part of `./gradlew check`.

## 10. Build & run

Commands unchanged from Phase 1:

```
./gradlew test    # 44 tests
./gradlew run     # 10 FILLED lines + Done.
./gradlew build   # compile + test + ktlintCheck + assemble
./scripts/precheck.sh   # full pre-push verification
```

`./gradlew run` output is byte-for-byte identical to Phase 1's because the user-visible behavior is unchanged. The internal pipeline differs, but the seeded random walk plus `EveryNthTickBuyStrategy` produces the same fills.

## 11. Out of scope (deferred to later phases)

| Feature | Phase |
|---|---|
| `Strategy.onCandle` interface extension | 2b |
| Candle aggregator | 2b |
| Multi-symbol `MockTickFeed` or feed multiplexer | 2b or beyond |
| Risk engine (`SignalEvent` → `OrderEvent` interceptor) | 3 |
| Position tracking, P&L | 3 |
| `BigDecimal` for prices | 3 |
| Backtest replay engine, `subscribeAll`, event log | 4 |
| Async / coroutines | post-Phase 5 (or live broker phase) |
| External DSL parser | 5 |
| Subscriber error isolation (try/catch around handlers) | future production phase |
| Subscriber registration after startup | when Phase 5 DSL needs dynamic loading |
| Runtime unsubscribe | YAGNI |
| Ordered-by-priority subscribers | YAGNI |
| Logback / structured logging | when production deployment forces it |
| Metrics / observability | 4+ |

## 12. Migration from Phase 1

Phase 1 code lives on `main` at the time this spec is written. Phase 2a implementation lands on a feature branch (`phase2a-event-bus` per the project skill), reviewed and merged.

The migration is internal-only: no public API changes (the project has no external consumers). Phase 1 acceptance criteria still hold after Phase 2a:

- `./gradlew build` passes (now also runs ktlint via `check`).
- `./gradlew run` produces 10 `FILLED` lines + `Done.` — same fills as Phase 1 because seeds and strategy are unchanged.
- `./gradlew test` runs 44 tests, all green.
- Same `MockTickFeed` seed produces same ticks.
- No production class is mocked in any test.
- No file exceeds ~150 lines (with possible exceptions documented as in Phase 1).
- `git log` clean history.

## 13. Acceptance criteria (Phase 2a done means)

- [ ] `EventBus`, `Event` sealed class, `TickEvent`/`SignalEvent`/`OrderEvent`/`TradeEvent`, `SequenceGenerator`/`MonotonicSequenceGenerator` exist at the paths in §5.
- [ ] `Engine.kt` is reduced to its thin form (no `Strategy`, `Broker`, `IdGenerator`, `Clock`, `onTrade` constructor params).
- [ ] `Main.kt` wires the pipeline through the bus exactly as described in §6.5.
- [ ] All 44 tests pass.
- [ ] `./gradlew build` green (compile + ktlint + tests + assemble).
- [ ] `./gradlew run` produces 10 `FILLED` lines + `Done.`.
- [ ] `slf4j-api` and `slf4j-simple` are added through the version catalog.
- [ ] `bus.publish` log lines appear at TRACE level under `-Dorg.slf4j.simpleLogger.defaultLogLevel=trace`.
- [ ] Cross-symbol strategy test in `EndToEndTest.kt` passes (regression coverage for the cross-symbol scenario).
- [ ] `git log` clean history with conventional commit messages, one feature per PR per the project skill §3 and §5.
- [ ] No file exceeds ~200 lines (Main.kt is closest, ~50 lines).

When all boxes are checked, Phase 2a is done and Phase 2b (candle aggregator + `Strategy.onCandle`) can begin from a known-good baseline.
