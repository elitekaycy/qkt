# qkt — Trading Engine Phase 1 Design

**Date:** 2026-05-03
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 1 of a multi-phase trading platform. Lays foundations for Phases 2–5 without paying their cost today.

---

## 1. Vision (long horizon, for context only)

`qkt` is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for writing trading strategies (e.g., `WHEN EMA(9) > EMA(21) BUY XAUUSD 1`). The end-state system supports market-data ingestion, strategy execution, risk management, order execution, and deterministic backtesting.

**This document is Phase 1 only.** It defines the smallest piece of that runtime that produces a real end-to-end trade flow and proves the architecture. Each later phase has its own design.

## 2. Phase 1 — what we are building

A single-process Kotlin program that:

1. Generates a finite, deterministic stream of mock ticks (price updates).
2. Feeds those ticks into an `Engine`.
3. The Engine forwards each tick to a `Strategy`, which may emit zero or more `Signal`s.
4. The Engine converts each `Signal` to an `Order` (with id, timestamp, type=MARKET).
5. A `MockBroker` "executes" the order, returning a `Trade`.
6. The Engine prints the trade.

End condition: program runs `main()`, processes 100 ticks of XAUUSD, prints fills, exits cleanly. A comprehensive JUnit + AssertJ test suite exercises every production class.

**Phase 1 does NOT include:** risk validation, candles in the strategy interface, an event bus / `Event` sealed class, multiple strategies, position tracking, partial fills, persistence, concurrency, observability, the DSL.

## 3. Decisions log

Each foundational decision and the reasoning, so future-us can see why a thing is shaped the way it is.

| # | Decision | Why |
|---|---|---|
| D1 | **Gradle, single module, Kotlin DSL.** | Multi-module pays off when codebase is large or boundaries hurt. On day one, it's overhead. Splitting later is mechanical. |
| D2 | **Single-threaded blocking loop.** `while (true) { val t = feed.next() ?: break; engine.onTick(t) }`. | Backtest determinism falls out for free; nothing to debug w.r.t. async. Coroutines arrive in Phase 2 with the event bus. |
| D3 | **Phase 1 scope = engine + strategy + signals + mock broker + tests.** | Thinner scope (no `Signal`) teaches nothing about the architecture. Wider scope (event bus) is YAGNI. This middle ground exercises the *real* event flow with the smallest cast of components. |
| D4 | **JUnit 5 + AssertJ.** Add `kotest-property` as a library when risk engine arrives. | Industry-standard JVM testing. Best tooling, most stable, easiest hiring/onboarding. AssertJ closes Kotest's expressiveness gap without committing to a framework. |
| D5 | **Package-by-domain.** Packages mirror spec sections (`common`, `marketdata`, `strategy`, `execution`, `broker`, `engine`, `app`). | Trading concepts are the interesting axis, not technical role. Hexagonal layout (ports/adapters) earns its keep when there are many adapters per port — irrelevant in Phase 1. |
| D6 | **Strategy emits via callback.** `fun onTick(tick: Tick, emit: (Signal) -> Unit)`. | Supports zero or many signals per tick (pairs trades, bracket orders later). Engine owns routing — the lambda is the contract. Risk engine in Phase 3 swaps the lambda; strategy code unchanged. |
| D7 | **Signal → Order → Trade.** Broker is `fun execute(order: Order): Trade?`. | Models the spec's data model fully. Real-broker swap becomes mechanical. Risk engine in Phase 3 sits naturally between Signal-to-Order and execute. `Trade?` (nullable) lets us model rejections without changing the interface. |
| D8 | **`qkt.common.Clock` interface, `Long` ms.** `SystemClock` for prod, `FixedClock` for tests/backtest. | Determinism is a Phase 4 hard requirement; abstraction is justified on day one. `Long` matches spec's timestamp shape and what every market feed gives you in practice. |
| D9 | **`IdGenerator` interface, sequential default.** `"ORD-0"`, `"ORD-1"`, ... | Same determinism logic as Clock. UUIDs would make backtest output diff'ing impossible. Pluggable for distributed/global-id later. |
| D10 | **Pull-model `TickFeed.next(): Tick?`.** | Engine drives the loop → step/pause/replay are trivial → Phase 4 backtest engine is one component swap. Live websockets push, but adapters bridge push→pull when the time comes. |
| D11 | **`MarketPriceProvider` (read interface) + `MarketPriceTracker` (read+write impl).** Engine writes on every tick; broker, risk, future strategies read. | Multi-symbol from day one. Read/write split prevents broker from corrupting state via the type system. Scales across phases (Phase 3 risk and beyond also read). |
| D12 | **`Trade.orderId` exists** (one-line addition over original spec). | Real systems must correlate fills with the order that produced them. |
| D13 | **`Double` for prices.** Revisit when Phase 3 introduces P&L math. | Phase 1 doesn't accumulate enough math for float drift to matter. Switching to `BigDecimal` later is mechanical but touches every data class — defer. |
| D14 | **`println` for output.** SLF4J in Phase 2. | One file (`Main.kt`) prints; engine/broker take callbacks. No log framework needed yet. |
| D15 | **Kotlin 2.1.0, JDK 21, Gradle 8.10.** Tests on JUnit 5.11.4 + AssertJ 3.27.0. | Current stable everything as of 2026-05-03. |

## 4. Architecture

### 4.1 Pipeline

```
┌──────────────┐  pull   ┌────────────┐ onTick ┌────────────┐
│ MockTickFeed │────────▶│   Engine   │───────▶│  Strategy  │
└──────────────┘         │            │ emit() │            │
                         │            │◀───────│            │
                         │            │        └────────────┘
                         │ signal→order   execute   ┌────────────┐
                         │            │────────────▶│ MockBroker │
                         │            │   Trade?    │            │
                         │            │◀────────────│            │
                         └─────┬──────┘             └─────▲──────┘
                               │                          │
                               │ update     lastPrice     │
                               ▼                          │
                       ┌─────────────────────┐            │
                       │ MarketPriceTracker  │────────────┘
                       │  (impl Provider)    │  read-only view
                       └─────────────────────┘
```

### 4.2 Component dependencies

```
app       ──▶ engine, strategy, broker, marketdata, common
engine    ──▶ strategy, broker, execution, marketdata, common
strategy  ──▶ marketdata, execution, common
broker    ──▶ execution, marketdata, common
execution ──▶ common
marketdata ─▶ common
common    ──▶ (nothing)
```

`common` is the leaf. `app` is the root. No cycles. Import direction matches the arrows; importing "up" the graph is a design smell.

### 4.3 One Engine cycle, step by step

For each `engine.onTick(tick)`:

1. Engine writes `priceTracker.update(tick.symbol, tick.price)`.
2. Engine calls `strategy.onTick(tick) { signal -> route(signal) }`.
3. Strategy may invoke the `emit` lambda zero or more times. Each invocation is captured by `route(...)`.
4. For each signal, Engine calls `signal.toOrder(idGenerator.next(), clock.now())`. Order is MARKET, `price = null`.
5. Engine calls `broker.execute(order)`.
6. Broker reads `priceProvider.lastPrice(order.symbol)`. If null, returns null (rejection).
7. If null returned, Engine skips. If `Trade` returned, Engine calls `onTrade(trade)`.
8. Cycle ends. Next iteration of `main()`'s loop calls `feed.next()` → next tick.

Multi-signal-per-tick is supported: each signal gets its own ID, all fill at the same `lastPrice` (one tick = one market snapshot).

## 5. File layout

```
qkt/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/...
├── gradlew, gradlew.bat
├── .gitignore
├── docs/superpowers/specs/2026-05-03-trading-engine-phase1-design.md
├── src/main/kotlin/com/qkt/
│   ├── common/
│   │   ├── Clock.kt              # interface Clock + SystemClock + FixedClock
│   │   ├── IdGenerator.kt        # interface IdGenerator + SequentialIdGenerator
│   │   └── Side.kt               # enum Side { BUY, SELL }
│   ├── marketdata/
│   │   ├── Tick.kt               # data class Tick(symbol, price, timestamp, volume?)
│   │   ├── Candle.kt             # data class Candle (defined; unused in Phase 1)
│   │   ├── TickFeed.kt           # interface TickFeed { fun next(): Tick? }
│   │   ├── MockTickFeed.kt       # finite, seeded random walk
│   │   └── MarketPriceProvider.kt # interface + MarketPriceTracker
│   ├── execution/
│   │   ├── OrderType.kt          # enum { MARKET, LIMIT, STOP }
│   │   ├── Order.kt              # data class Order
│   │   └── Trade.kt              # data class Trade(orderId, ...)
│   ├── strategy/
│   │   ├── Signal.kt             # sealed class Signal { Buy, Sell }
│   │   ├── Strategy.kt           # interface Strategy { onTick(tick, emit) }
│   │   └── EveryNthTickBuyStrategy.kt
│   ├── broker/
│   │   ├── Broker.kt             # interface Broker { execute(order): Trade? }
│   │   └── MockBroker.kt
│   ├── engine/
│   │   └── Engine.kt
│   └── app/
│       └── Main.kt               # fun main()
└── src/test/kotlin/com/qkt/
    ├── engine/EngineTest.kt
    ├── broker/MockBrokerTest.kt
    ├── strategy/EveryNthTickBuyStrategyTest.kt
    ├── marketdata/MarketPriceTrackerTest.kt
    └── marketdata/MockTickFeedTest.kt
```

The pre-existing flat directories (`engine/`, `common/`, `market-data/`) are migrated under `src/main/kotlin/com/qkt/...`. The pre-existing `Candle.kt` content is reused (just gets a `package` declaration). The pre-existing `Tick.kt` is replaced — it has a syntax error (`data class Tick {` should be `(`).

## 6. Type & interface signatures

Definitive signatures every implementation must match. Bodies are sketched in section 7.

### 6.1 `common`

```kotlin
package com.qkt.common

interface Clock { fun now(): Long }
class SystemClock : Clock { override fun now() = System.currentTimeMillis() }
class FixedClock(var time: Long = 0L) : Clock { override fun now() = time }

interface IdGenerator { fun next(): String }
class SequentialIdGenerator(private val prefix: String = "ORD") : IdGenerator {
    private var counter = 0L
    override fun next(): String = "$prefix-${counter++}"
}

enum class Side { BUY, SELL }
```

### 6.2 `marketdata`

```kotlin
package com.qkt.marketdata

data class Tick(
    val symbol: String,
    val price: Double,
    val timestamp: Long,
    val volume: Double? = null
)

data class Candle(
    val symbol: String,
    val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Double,
    val startTime: Long, val endTime: Long
)

interface TickFeed { fun next(): Tick? }

class MockTickFeed(
    private val symbol: String,
    private val startPrice: Double,
    private val count: Int,
    private val clock: Clock,
    private val random: Random = Random(seed = 42L)
) : TickFeed { /* finite seeded random walk; see §7 */ }

interface MarketPriceProvider {
    fun lastPrice(symbol: String): Double?
}

class MarketPriceTracker : MarketPriceProvider {
    private val prices = mutableMapOf<String, Double>()
    fun update(symbol: String, price: Double) { prices[symbol] = price }
    override fun lastPrice(symbol: String): Double? = prices[symbol]
}
```

### 6.3 `execution`

```kotlin
package com.qkt.execution

enum class OrderType { MARKET, LIMIT, STOP }

data class Order(
    val id: String,
    val symbol: String,
    val side: Side,
    val quantity: Double,
    val type: OrderType,
    val price: Double? = null,    // null for MARKET, required for LIMIT/STOP
    val timestamp: Long
)

data class Trade(
    val orderId: String,            // links back to the Order
    val symbol: String,
    val price: Double,              // actual fill price
    val quantity: Double,
    val side: Side,
    val timestamp: Long
)
```

### 6.4 `strategy`

```kotlin
package com.qkt.strategy

sealed class Signal {
    data class Buy(val symbol: String, val size: Double) : Signal()
    data class Sell(val symbol: String, val size: Double) : Signal()
}

interface Strategy {
    fun onTick(tick: Tick, emit: (Signal) -> Unit)
}

class EveryNthTickBuyStrategy(
    private val symbol: String,
    private val n: Int = 10,
    private val size: Double = 1.0
) : Strategy { /* see §7 */ }
```

### 6.5 `broker`

```kotlin
package com.qkt.broker

interface Broker {
    fun execute(order: Order): Trade?    // null = rejected/unfilled
}

class MockBroker(
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider
) : Broker { /* see §7 */ }
```

### 6.6 `engine`

```kotlin
package com.qkt.engine

class Engine(
    private val strategy: Strategy,
    private val broker: Broker,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val priceTracker: MarketPriceTracker,
    private val onTrade: (Trade) -> Unit = {}
) {
    fun onTick(tick: Tick) { /* see §7 */ }
}
```

### 6.7 `app`

```kotlin
package com.qkt.app

fun main() { /* wires everything together; see §7 */ }
```

## 7. Implementation sketches

Reference bodies. The implementation plan (next document) drives actual writing.

### 7.1 `MockTickFeed`

```kotlin
class MockTickFeed(
    private val symbol: String,
    private val startPrice: Double,
    private val count: Int,
    private val clock: Clock,
    private val random: Random = Random(seed = 42L)
) : TickFeed {
    init {
        require(count >= 0) { "count must be >= 0: $count" }
        require(startPrice > 0.0) { "startPrice must be > 0: $startPrice" }
    }
    private var emitted = 0
    private var price = startPrice
    override fun next(): Tick? {
        if (emitted >= count) return null
        price *= (1.0 + (random.nextDouble() - 0.5) * 0.01)   // ±0.5% step
        emitted++
        return Tick(symbol, price, clock.now())
    }
}
```

### 7.2 `EveryNthTickBuyStrategy`

```kotlin
class EveryNthTickBuyStrategy(
    private val symbol: String,
    private val n: Int = 10,
    private val size: Double = 1.0
) : Strategy {
    init {
        require(n > 0) { "n must be > 0: $n" }
        require(size > 0.0) { "size must be > 0: $size" }
    }
    private var counter = 0
    override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
        if (tick.symbol != symbol) return
        counter++
        if (counter % n == 0) emit(Signal.Buy(symbol, size))
    }
}
```

### 7.3 `MockBroker`

```kotlin
class MockBroker(
    private val clock: Clock,
    private val priceProvider: MarketPriceProvider
) : Broker {
    override fun execute(order: Order): Trade? {
        require(order.quantity > 0.0) { "Order quantity must be > 0: $order" }
        val fillPrice = when (order.type) {
            OrderType.MARKET -> priceProvider.lastPrice(order.symbol) ?: return null
            OrderType.LIMIT, OrderType.STOP ->
                order.price ?: error("LIMIT/STOP requires price: $order")
        }
        return Trade(
            orderId = order.id,
            symbol = order.symbol,
            price = fillPrice,
            quantity = order.quantity,
            side = order.side,
            timestamp = clock.now()
        )
    }
}
```

### 7.4 `Engine`

```kotlin
class Engine(
    private val strategy: Strategy,
    private val broker: Broker,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val priceTracker: MarketPriceTracker,
    private val onTrade: (Trade) -> Unit = {}
) {
    fun onTick(tick: Tick) {
        priceTracker.update(tick.symbol, tick.price)
        strategy.onTick(tick) { signal -> route(signal) }
    }

    private fun route(signal: Signal) {
        val order = signal.toOrder(idGenerator.next(), clock.now())
        val trade = broker.execute(order) ?: return
        onTrade(trade)
    }

    private fun Signal.toOrder(id: String, ts: Long): Order = when (this) {
        is Signal.Buy  -> Order(id, symbol, Side.BUY,  size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
}
```

### 7.5 `Main`

```kotlin
fun main() {
    val clock        = SystemClock()
    val ids          = SequentialIdGenerator()
    val priceTracker = MarketPriceTracker()
    val strategy     = EveryNthTickBuyStrategy("XAUUSD", n = 10, size = 1.0)
    val broker       = MockBroker(clock, priceTracker)        // Kotlin upcasts to MarketPriceProvider
    val engine       = Engine(
        strategy, broker, clock, ids, priceTracker,
        onTrade = { t -> println("FILLED: ${t.side} ${t.quantity} ${t.symbol} @ ${t.price}") }
    )
    val feed = MockTickFeed("XAUUSD", startPrice = 2400.0, count = 100, clock = clock)

    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    println("Done.")
}
```

## 8. Error handling

| Condition | Behavior | Type |
|---|---|---|
| `Signal.size <= 0` | `require(...)` throws `IllegalArgumentException` | Crash (bug) |
| `Order.quantity <= 0` | `require(...)` throws | Crash (bug) |
| LIMIT/STOP with null `price` | `error(...)` throws `IllegalStateException` | Crash (bug) |
| MARKET order, no price seen for symbol | Broker returns `null` | Reject (valid runtime condition) |
| `MockTickFeed(count < 0)` | `require(...)` throws on construction | Crash (bug) |
| `MockTickFeed(startPrice <= 0)` | `require(...)` throws on construction | Crash (bug) |
| `EveryNthTickBuyStrategy(n <= 0)` or `(size <= 0)` | `require(...)` throws on construction | Crash (bug) |
| Strategy throws inside `onTick` | Propagates out of `engine.onTick`, crashes `main()` | Crash (bug) — desirable in Phase 1 |

**Crash vs. reject:** Crash = bug (the system received data it doesn't model). Reject = valid runtime condition (broker can't fill). Crash hard, fix the bug. Don't paper over.

No try/catch in Phase 1 production code. We add per-strategy isolation in Phase 2 when there are multiple strategies.

## 9. Testing strategy

Per CLAUDE.md, every production class has a test. Real types only — no Mockito, no MockK. The few "fakes" we use are anonymous objects (`object : Strategy { ... }`) or `MutableList<Trade>` capture lists. These are not mocks; they're real code.

### Test files

| File | Class under test | Test count |
|---|---|---|
| `EngineTest.kt` | `Engine` | 8 |
| `MockBrokerTest.kt` | `MockBroker` | 8 |
| `MarketPriceTrackerTest.kt` | `MarketPriceTracker` | 4 |
| `EveryNthTickBuyStrategyTest.kt` | `EveryNthTickBuyStrategy` | 5 |
| `MockTickFeedTest.kt` | `MockTickFeed` | 6 |

Trivial classes (`SystemClock`, `FixedClock`, `SequentialIdGenerator`) are exercised inline by other tests; no separate file.

### Assertion style

```kotlin
@Test fun `forwards tick to strategy with emit lambda`() {
    val ticks = mutableListOf<Tick>()
    val strategy = object : Strategy {
        override fun onTick(tick: Tick, emit: (Signal) -> Unit) { ticks.add(tick) }
    }
    val engine = newEngine(strategy, mutableListOf())
    val tick = Tick("XAUUSD", 2400.0, 1000L)

    engine.onTick(tick)

    assertThat(ticks).containsExactly(tick)
}
```

Backtick test names (Kotlin feature: `fun \`spaces in name\`()`) for readability — the spec syntax for tests preserves intent. Disabled per-package via Gradle config if you find them ugly.

### Determinism

`MockTickFeed` uses a seeded `Random(42L)`. Two runs with the same seed produce identical tick sequences. `MockTickFeedTest` asserts this property — it's the muscle for Phase 4 backtest determinism.

## 10. Build & run

### `build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.qkt"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.qkt.app.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

kotlin { jvmToolchain(21) }
```

### `settings.gradle.kts`

```kotlin
rootProject.name = "qkt"
```

### `.gitignore`

```
.gradle/
build/
.idea/
*.iml
local.properties
.DS_Store
```

### Commands

```
./gradlew test          # run all tests
./gradlew run           # run main()
./gradlew build         # compile + test + assemble
```

### Pinned versions

- Kotlin `2.1.0`
- JDK `21` (LTS)
- Gradle `8.10`
- JUnit Jupiter `5.11.4`
- AssertJ `3.27.0`
- (later) `kotest-property` for risk-engine property tests in Phase 3

## 11. Out of scope (deferred to later phases)

| Feature | Phase |
|---|---|
| Risk engine (max position, max loss, trade approval) | 3 |
| `Strategy.onCandle` + candle aggregator | 2 |
| `Event` sealed class + event bus | 2 |
| Multiple strategies / strategy registry | 2 |
| Position tracking, P&L | 3 |
| LIMIT/STOP signals (broker handles them; no strategy emits them yet) | 2/3 |
| Partial fills, order rejection beyond no-price | Real-broker phase |
| Order cancellation / amendment | Future |
| Persistence (state survives restart) | Future |
| Concurrency (multiple feeds, async strategy execution) | 2+ |
| SLF4J logging | 2 |
| Backtest replay engine | 4 |
| `BigDecimal` for prices | 3 (when P&L math compounds) |
| Bid/ask spread, order book modeling | Real-broker phase |
| Symbol whitelist / instrument registry | Real-broker phase |
| Metrics, observability, dashboards | 4+ |
| External DSL parser | 5 |

## 12. Migration from existing files

Two files exist today:

- `market-data/Tick.kt` — has a syntax error (`data class Tick {` should be `(`). Replace with the corrected version under `src/main/kotlin/com/qkt/marketdata/Tick.kt`.
- `market-data/Candle.kt` — clean. Move under `src/main/kotlin/com/qkt/marketdata/Candle.kt`, add `package com.qkt.marketdata`.

The flat directories (`common/`, `engine/`, `market-data/`) get removed once their content is migrated. The new layout is `src/main/kotlin/...` per Gradle convention.

## 13. Acceptance criteria (Phase 1 done means)

- [ ] `./gradlew build` passes (compile + test).
- [ ] `./gradlew run` produces ~10 lines of `FILLED: BUY 1.0 XAUUSD @ <price>` output, then `Done.`.
- [ ] `./gradlew test` runs all ~30 tests, all green.
- [ ] Same `MockTickFeed` seed produces the same ticks across runs (verified by test).
- [ ] No production class is mocked in any test (verified by grep — no Mockito/MockK on the classpath).
- [ ] All decisions in §3 are visible in code (e.g., `MarketPriceProvider` interface exists; broker depends on it; engine depends on `MarketPriceTracker`).
- [ ] No file exceeds ~150 lines. If one does, it is doing too much.
- [ ] `git log` shows a clean history starting from a `git init` commit.

When all boxes are checked, Phase 1 is done and Phase 2 (event bus + candles) can begin from a known-good baseline.
