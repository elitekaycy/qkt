# Trading Engine Phase 2a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the qkt pipeline to dispatch typed events through a synchronous event bus instead of direct method calls. Engine becomes a thin tick-source; main() wires the pipeline by subscribing handlers to event types. Adds multi-strategy support and SLF4J logging.

**Architecture:** Type-keyed map event bus (`Map<KClass<Event>, List<handler>>`) with depth-first synchronous dispatch. Engine publishes `TickEvent`; subscribers in main() chain `TickEvent → SignalEvent → OrderEvent → TradeEvent`. All foundations (`Clock`, `IdGenerator`, `SequenceGenerator`) preserve Phase 4 backtest determinism. No async, no coroutines.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16 (api + simple).

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase2a-design.md`](../specs/2026-05-03-trading-engine-phase2a-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase2a-event-bus`. Phase 1 is on `main` at commit `265f867` (after the Phase 2a spec doc).

---

## Task ordering

```
1. Add SLF4J to version catalog + build
2. SequentialIdGenerator companion: add SequenceGenerator + Monotonic impl   [TDD]
3. Create Event sealed class + 4 variants
4. Create EventBus                                                            [TDD]
5. Refactor: rewrite Engine + EngineTest + Main.kt as one unit                [coordinated refactor]
6. Add EndToEndTest                                                           [integration tests]
7. Final verification                                                          [no new files]
```

7 tasks. Each ends in a commit. Tasks 2 and 4 follow strict TDD (red → green); Task 5 is a coordinated refactor that replaces three files together to keep the build green; Task 6 adds end-to-end coverage that mostly verifies wiring already in place.

---

## Task 1: Add SLF4J to version catalog and build

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Update `gradle/libs.versions.toml`**

The file currently looks like this:

```toml
[versions]
kotlin = "2.1.0"
junit = "5.11.4"
assertj = "3.27.0"
ktlint-plugin = "12.1.1"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-plugin" }
```

Replace with:

```toml
[versions]
kotlin = "2.1.0"
junit = "5.11.4"
assertj = "3.27.0"
ktlint-plugin = "12.1.1"
slf4j = "2.0.16"

[libraries]
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-plugin" }
```

- [ ] **Step 2: Update `build.gradle.kts` `dependencies` block**

Find the `dependencies { ... }` block (currently 5 lines). Replace with:

```kotlin
dependencies {
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

- [ ] **Step 3: Verify build still passes**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. SLF4J is on the classpath but unused by code yet — Gradle compiles, ktlint checks, all 31 tests still pass.

If the build fails because of SLF4J resolution, **report BLOCKED**.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "build: add slf4j-api and slf4j-simple dependencies"
```

Verify with `git log --oneline -2` that the commit was created with subject only.

---

## Task 2: SequenceGenerator + MonotonicSequenceGenerator [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/common/MonotonicSequenceGeneratorTest.kt`
- Modify: `src/main/kotlin/com/qkt/common/IdGenerator.kt` (extend with new types)

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/common/MonotonicSequenceGeneratorTest.kt`:

```kotlin
package com.qkt.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MonotonicSequenceGeneratorTest {
    @Test
    fun `next returns 0 first`() {
        val sequencer = MonotonicSequenceGenerator()
        assertThat(sequencer.next()).isEqualTo(0L)
    }

    @Test
    fun `next is monotonically increasing`() {
        val sequencer = MonotonicSequenceGenerator()
        assertThat(sequencer.next()).isEqualTo(0L)
        assertThat(sequencer.next()).isEqualTo(1L)
        assertThat(sequencer.next()).isEqualTo(2L)
        assertThat(sequencer.next()).isEqualTo(3L)
    }

    @Test
    fun `independent instances have independent counters`() {
        val a = MonotonicSequenceGenerator()
        val b = MonotonicSequenceGenerator()
        assertThat(a.next()).isEqualTo(0L)
        assertThat(a.next()).isEqualTo(1L)
        assertThat(b.next()).isEqualTo(0L)
        assertThat(a.next()).isEqualTo(2L)
        assertThat(b.next()).isEqualTo(1L)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.common.MonotonicSequenceGeneratorTest"`
Expected: compile failure — `Unresolved reference 'MonotonicSequenceGenerator'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Extend `IdGenerator.kt` with the new types**

Current `src/main/kotlin/com/qkt/common/IdGenerator.kt`:

```kotlin
package com.qkt.common

interface IdGenerator { fun next(): String }

class SequentialIdGenerator(
    private val prefix: String = "ORD",
) : IdGenerator {
    private var counter = 0L

    override fun next(): String = "$prefix-${counter++}"
}
```

Replace the entire file with:

```kotlin
package com.qkt.common

interface IdGenerator { fun next(): String }

class SequentialIdGenerator(
    private val prefix: String = "ORD",
) : IdGenerator {
    private var counter = 0L

    override fun next(): String = "$prefix-${counter++}"
}

interface SequenceGenerator { fun next(): Long }

class MonotonicSequenceGenerator : SequenceGenerator {
    private var counter = 0L

    override fun next(): Long = counter++
}
```

Note: ktlint formatting matches the existing trailing-comma style.

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.common.MonotonicSequenceGeneratorTest"`
Expected: 3 tests pass. Output shows:

```
MonotonicSequenceGeneratorTest > next returns 0 first() PASSED
MonotonicSequenceGeneratorTest > next is monotonically increasing() PASSED
MonotonicSequenceGeneratorTest > independent instances have independent counters() PASSED
```

- [ ] **Step 5: Run ktlint and full build**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint passes, all 34 tests pass (31 Phase 1 + 3 new).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/common/IdGenerator.kt src/test/kotlin/com/qkt/common/MonotonicSequenceGeneratorTest.kt
git commit -m "feat(common): add SequenceGenerator with MonotonicSequenceGenerator"
```

---

## Task 3: Create Event sealed class

**Files:**
- Create: `src/main/kotlin/com/qkt/events/Event.kt`

- [ ] **Step 1: Create the file**

`src/main/kotlin/com/qkt/events/Event.kt`:

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

No dedicated tests — these are pure data classes. They'll be exercised by `EventBusTest` (Task 4) and `EndToEndTest` (Task 6).

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full check (ktlint + tests)**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean. All 34 tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/events/Event.kt
git commit -m "feat(events): add Event sealed class with TickEvent SignalEvent OrderEvent TradeEvent"
```

---

## Task 4: Create EventBus [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/bus/EventBusTest.kt`
- Create: `src/main/kotlin/com/qkt/bus/EventBus.kt`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/qkt/bus/EventBusTest.kt`:

```kotlin
package com.qkt.bus

import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.Event
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventBusTest {
    private val clock = FixedClock(time = 1000L)
    private val sequencer = MonotonicSequenceGenerator()

    private fun newBus() = EventBus(clock, sequencer)

    private fun tick(symbol: String = "XAUUSD", price: Double = 2400.0) =
        Tick(symbol, price, 999L)

    @Test
    fun `publish with no subscribers is a no-op`() {
        val bus = newBus()
        bus.publish(TickEvent(tick()))
        // no exception, no observable effect
    }

    @Test
    fun `subscribe then publish invokes the handler with the event`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        val event = TickEvent(tick("XAUUSD", 2400.5))
        bus.publish(event)

        assertThat(received).hasSize(1)
        assertThat(received[0].tick).isEqualTo(event.tick)
    }

    @Test
    fun `bus stamps timestamp from clock on publish`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        clock.time = 12345L
        bus.publish(TickEvent(tick()))

        assertThat(received[0].timestamp).isEqualTo(12345L)
    }

    @Test
    fun `bus stamps monotonic sequenceId on publish`() {
        val bus = newBus()
        val received = mutableListOf<Event>()
        bus.subscribe<TickEvent> { received.add(it) }
        bus.subscribe<SignalEvent> { received.add(it) }

        bus.publish(TickEvent(tick()))
        bus.publish(SignalEvent(Signal.Buy("XAUUSD", 1.0)))
        bus.publish(TickEvent(tick()))

        assertThat(received.map { it.sequenceId }).containsExactly(0L, 1L, 2L)
    }

    @Test
    fun `multiple subscribers to same event run in registration order`() {
        val bus = newBus()
        val order = mutableListOf<String>()
        bus.subscribe<TickEvent> { order.add("first") }
        bus.subscribe<TickEvent> { order.add("second") }
        bus.subscribe<TickEvent> { order.add("third") }

        bus.publish(TickEvent(tick()))

        assertThat(order).containsExactly("first", "second", "third")
    }

    @Test
    fun `subscribers to different event types are isolated`() {
        val bus = newBus()
        val ticks = mutableListOf<TickEvent>()
        val signals = mutableListOf<SignalEvent>()
        bus.subscribe<TickEvent> { ticks.add(it) }
        bus.subscribe<SignalEvent> { signals.add(it) }

        bus.publish(TickEvent(tick()))

        assertThat(ticks).hasSize(1)
        assertThat(signals).isEmpty()
    }

    @Test
    fun `subscriber publishing different event type runs depth-first`() {
        val bus = newBus()
        val sequence = mutableListOf<String>()
        bus.subscribe<TickEvent> {
            sequence.add("tick-A-start")
            bus.publish(SignalEvent(Signal.Buy("XAUUSD", 1.0)))
            sequence.add("tick-A-end")
        }
        bus.subscribe<TickEvent> { sequence.add("tick-B") }
        bus.subscribe<SignalEvent> { sequence.add("signal-handler") }

        bus.publish(TickEvent(tick()))

        // Depth-first: tick-A pushes signal, signal-handler runs to completion,
        // then tick-A finishes, then tick-B runs.
        assertThat(sequence).containsExactly("tick-A-start", "signal-handler", "tick-A-end", "tick-B")
    }

    @Test
    fun `subscriber exception propagates out of publish`() {
        val bus = newBus()
        bus.subscribe<TickEvent> { error("boom") }

        assertThatThrownBy { bus.publish(TickEvent(tick())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")
    }

    @Test
    fun `published event default timestamp and sequenceId are overwritten`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        clock.time = 5555L
        // construct event with explicit non-zero values; bus should overwrite both
        bus.publish(TickEvent(tick(), timestamp = 999L, sequenceId = 999L))

        assertThat(received[0].timestamp).isEqualTo(5555L)
        assertThat(received[0].sequenceId).isEqualTo(0L)
    }

    @Test
    fun `each event type retains its own subscriber list`() {
        val bus = newBus()
        val tickHandlers = mutableListOf<String>()
        val orderHandlers = mutableListOf<String>()
        val tradeHandlers = mutableListOf<String>()
        bus.subscribe<TickEvent> { tickHandlers.add("t1") }
        bus.subscribe<OrderEvent> { orderHandlers.add("o1") }
        bus.subscribe<OrderEvent> { orderHandlers.add("o2") }
        bus.subscribe<TradeEvent> { tradeHandlers.add("tr1") }

        bus.publish(OrderEvent(Order("ORD-0", "XAUUSD", Side.BUY, 1.0, OrderType.MARKET, null, 1000L)))

        assertThat(tickHandlers).isEmpty()
        assertThat(orderHandlers).containsExactly("o1", "o2")
        assertThat(tradeHandlers).isEmpty()
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.bus.EventBusTest"`
Expected: compile failure — `Unresolved reference 'EventBus'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement EventBus**

`src/main/kotlin/com/qkt/bus/EventBus.kt`:

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
            is TickEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is SignalEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is OrderEvent -> event.copy(timestamp = ts, sequenceId = seq)
            is TradeEvent -> event.copy(timestamp = ts, sequenceId = seq)
        }
    }
}
```

Notes for the implementer:
- `inline fun <reified T : Event> subscribe(...)` requires `inline` so `reified T` works (Kotlin's compile-time generic specialization).
- The `noinline` on the parameter is required because we store the lambda in a list — `inline` can't propagate into stored references.
- The `@Suppress("UNCHECKED_CAST")` is on the cast inside the captured lambda. Safe by construction (bucket is keyed by `T::class`).
- The exhaustive `when (event)` over the sealed `Event` hierarchy means adding a new event type will produce a compile error here, forcing the implementer to handle it.

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.bus.EventBusTest"`
Expected: 10 tests pass. Output shows each `EventBusTest > <name>() PASSED`.

If a test fails, **report BLOCKED**. Do not modify the test.

- [ ] **Step 5: Run ktlint and full build**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean, all 44 tests pass (31 Phase 1 + 3 sequence + 10 bus).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/bus/EventBus.kt src/test/kotlin/com/qkt/bus/EventBusTest.kt
git commit -m "feat(bus): add EventBus with type-keyed dispatch"
```

---

## Task 5: Refactor — rewrite Engine + EngineTest + Main as one unit

This task rewrites three files together because they're tightly coupled by the new Engine API. After this task, the build is green and `./gradlew run` produces 10 FILLED lines as before — but internally the pipeline runs through the bus.

The 6 EngineTest cases that test routing/conversion (currently in `EngineTest.kt`) are deliberately removed — their concerns move to `EndToEndTest` in Task 6, where they are tested at the integration level. We are NOT dropping coverage; we're moving it to the layer where the logic now lives.

**Files:**
- Modify (replace): `src/test/kotlin/com/qkt/engine/EngineTest.kt`
- Modify (replace): `src/main/kotlin/com/qkt/engine/Engine.kt`
- Modify (replace): `src/main/kotlin/com/qkt/app/Main.kt`

- [ ] **Step 1: Replace `EngineTest.kt` with the thin-engine tests**

Current file has 8 tests against the Phase 1 Engine API. Replace the entire contents with:

```kotlin
package com.qkt.engine

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EngineTest {
    private val clock = FixedClock(time = 1000L)
    private val sequencer = MonotonicSequenceGenerator()
    private val tracker = MarketPriceTracker()
    private val bus = EventBus(clock, sequencer)
    private val engine = Engine(bus, tracker)

    @Test
    fun `onTick updates priceTracker before publishing TickEvent`() {
        val seenPrices = mutableListOf<Double?>()
        bus.subscribe<TickEvent> { seenPrices.add(tracker.lastPrice("XAUUSD")) }

        engine.onTick(Tick("XAUUSD", 2400.5, 999L))

        assertThat(seenPrices).containsExactly(2400.5)
    }

    @Test
    fun `onTick publishes TickEvent carrying the same Tick`() {
        val received = mutableListOf<Tick>()
        bus.subscribe<TickEvent> { received.add(it.tick) }

        val tick = Tick("XAUUSD", 2400.0, 999L)
        engine.onTick(tick)

        assertThat(received).containsExactly(tick)
    }
}
```

- [ ] **Step 2: Run tests, confirm RED**

Run: `./gradlew test --tests "com.qkt.engine.EngineTest"`
Expected: compile failure. The new test references `Engine(bus, tracker)` constructor signature that doesn't exist yet (Phase 1 Engine has 6 constructor params).

- [ ] **Step 3: Replace `Engine.kt` with the thin-engine impl**

Current file has the full Phase 1 Engine with `Strategy`, `Broker`, `Clock`, `IdGenerator`, `priceTracker`, `onTrade` constructor params plus `route` and `Signal.toOrder` private members. Replace entire contents with:

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

- [ ] **Step 4: Run EngineTest only, expect GREEN, but full build will still fail**

Run: `./gradlew test --tests "com.qkt.engine.EngineTest"`
Expected: 2 tests pass.

If you run `./gradlew build` now, it will FAIL because `Main.kt` still constructs the old Engine with 6 params. That's expected — Step 5 fixes Main.

- [ ] **Step 5: Replace `Main.kt` with the bus-wired version**

Current file constructs Engine with 6 params and runs the loop. Replace entire contents with:

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
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()

    val bus = EventBus(clock, sequencer)
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
    is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
    is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
}
```

- [ ] **Step 6: Run full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. ktlint passes, all 38 tests pass (31 Phase 1 unchanged + 3 SeqGen + 10 EventBus + 2 thin EngineTest − 8 old EngineTest = 38 total).

If the build fails on ktlint, **run** `./gradlew ktlintFormat` **then re-run** `./gradlew build`. ktlint may need to apply the formatting rules to the new files.

- [ ] **Step 7: Run main, verify identical end-to-end behavior**

Run: `./gradlew run`
Expected output (10 FILLED lines + Done.; prices identical to Phase 1 because the seed and strategy are unchanged):

```
FILLED: BUY 1.0 XAUUSD @ 2382.1840422745718
FILLED: BUY 1.0 XAUUSD @ 2377.5263975412613
FILLED: BUY 1.0 XAUUSD @ 2392.7381744625472
FILLED: BUY 1.0 XAUUSD @ 2421.5490037283134
FILLED: BUY 1.0 XAUUSD @ 2461.666345450497
FILLED: BUY 1.0 XAUUSD @ 2461.791979355432
FILLED: BUY 1.0 XAUUSD @ 2477.0033009886542
FILLED: BUY 1.0 XAUUSD @ 2461.4515247907966
FILLED: BUY 1.0 XAUUSD @ 2471.6184153552363
FILLED: BUY 1.0 XAUUSD @ 2465.366907086234
Done.
```

Note: SLF4J Simple writes to stderr by default, not stdout. The lines may appear before/after Gradle's own output depending on terminal handling. As long as the 10 FILLED lines and `Done.` are produced, the run is correct.

If there are fewer/more than 10 FILLED lines, **report DONE_WITH_CONCERNS** with the actual output.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/engine/Engine.kt src/test/kotlin/com/qkt/engine/EngineTest.kt src/main/kotlin/com/qkt/app/Main.kt
git commit -m "refactor: route engine through event bus"
```

This is a single commit because the three files are coupled by the new Engine API — splitting them would leave intermediate broken builds.

---

## Task 6: Add EndToEndTest

These tests verify the wired pipeline behaves correctly. Each test wires a real bus + real broker + capturing trade list + real or anonymous strategies, then asserts on observable outcomes (the captured trades or orders). The 6 tests cover the concerns that were dropped from `EngineTest` in Task 5, plus the cross-symbol regression case.

**Files:**
- Create: `src/test/kotlin/com/qkt/app/EndToEndTest.kt`

- [ ] **Step 1: Create the test file**

`src/test/kotlin/com/qkt/app/EndToEndTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.engine.Engine
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndTest {
    private val clock = FixedClock(time = 1000L)
    private val ids = SequentialIdGenerator()
    private val sequencer = MonotonicSequenceGenerator()
    private val tracker = MarketPriceTracker()
    private val bus = EventBus(clock, sequencer)
    private val broker = MockBroker(clock, tracker)
    private val engine = Engine(bus, tracker)
    private val trades = mutableListOf<Trade>()
    private val orders = mutableListOf<Order>()

    private fun wirePipeline(strategies: List<Strategy>, captureOrders: Boolean = false) {
        strategies.forEach { s ->
            bus.subscribe<TickEvent> { e ->
                s.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val order = e.signal.toOrder(ids.next(), clock.now())
            if (captureOrders) orders.add(order)
            bus.publish(OrderEvent(order))
        }
        bus.subscribe<OrderEvent> { e ->
            broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
        }
        bus.subscribe<TradeEvent> { e -> trades.add(e.trade) }
    }

    private fun Signal.toOrder(id: String, ts: Long): Order = when (this) {
        is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }

    private fun buyEveryTick(symbol: String) = object : Strategy {
        override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
            if (tick.symbol == symbol) emit(Signal.Buy(symbol, 1.0))
        }
    }

    @Test
    fun `single strategy buy on every tick produces a fill`() {
        wirePipeline(listOf(buyEveryTick("XAUUSD")))

        engine.onTick(Tick("XAUUSD", 2400.5, 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].side).isEqualTo(Side.BUY)
        assertThat(trades[0].price).isEqualTo(2400.5)
    }

    @Test
    fun `signal for unknown symbol produces no fill`() {
        val emitForUnknown = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("BTCUSD", 1.0)) // tracker has no BTCUSD price
            }
        }
        wirePipeline(listOf(emitForUnknown))

        engine.onTick(Tick("XAUUSD", 2400.0, 999L))

        assertThat(trades).isEmpty()
    }

    @Test
    fun `multiple strategies all see the same tick`() {
        val seenByA = mutableListOf<Tick>()
        val seenByB = mutableListOf<Tick>()
        val a = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) { seenByA.add(tick) }
        }
        val b = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) { seenByB.add(tick) }
        }
        wirePipeline(listOf(a, b))

        val tick = Tick("XAUUSD", 2400.0, 999L)
        engine.onTick(tick)

        assertThat(seenByA).containsExactly(tick)
        assertThat(seenByB).containsExactly(tick)
    }

    @Test
    fun `order ids are sequential across multiple signals`() {
        val emitTwo = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("XAUUSD", 1.0))
                emit(Signal.Sell("XAUUSD", 1.0))
            }
        }
        wirePipeline(listOf(emitTwo), captureOrders = true)

        engine.onTick(Tick("XAUUSD", 2400.0, 999L))

        assertThat(orders.map { it.id }).containsExactly("ORD-0", "ORD-1")
    }

    @Test
    fun `multiple signals from one tick all fill at same tracker price`() {
        val emitTwoBuys = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                emit(Signal.Buy("XAUUSD", 1.0))
                emit(Signal.Buy("XAUUSD", 2.0))
            }
        }
        wirePipeline(listOf(emitTwoBuys))

        engine.onTick(Tick("XAUUSD", 2400.5, 999L))

        assertThat(trades).hasSize(2)
        assertThat(trades.map { it.price }).containsExactly(2400.5, 2400.5)
    }

    @Test
    fun `cross-symbol strategy emits signal for symbol B from tick of symbol A`() {
        // pre-seed the tracker so the broker has a price for the trade symbol
        tracker.update("XAUUSD", 2400.0)
        val watchEurTradeGold = object : Strategy {
            override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                if (tick.symbol == "EURUSD") emit(Signal.Buy("XAUUSD", 1.0))
            }
        }
        wirePipeline(listOf(watchEurTradeGold))

        engine.onTick(Tick("EURUSD", 1.0921, 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].price).isEqualTo(2400.0)
    }
}
```

Notes:
- The helper `wirePipeline` mirrors the wiring in `Main.kt` but exposes a `captureOrders` flag for the test that needs to assert on order ids.
- The `Signal.toOrder` extension is duplicated from `Main.kt` here. This is acceptable in Phase 2a — Phase 3 will extract this conversion to a proper home (`com.qkt.execution` or similar) and both files will use it.
- Each test is independent: fresh `bus`, fresh `tracker`, fresh `trades` capture list (provided by the per-test class fields, which are re-initialized for each `@Test` because JUnit 5 instantiates a fresh test class instance per test by default).

- [ ] **Step 2: Run new tests, expect PASS**

Run: `./gradlew test --tests "com.qkt.app.EndToEndTest"`
Expected: 6 tests pass.

If a test fails, the failure tells us a wiring bug exists. **Fix the bug** (in Engine.kt, Main.kt, or the test wiring helper) and re-run. Don't modify the test assertions — the assertions reflect required behavior from the spec.

- [ ] **Step 3: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. 44 tests pass total. ktlint clean.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/app/EndToEndTest.kt
git commit -m "test(app): add end-to-end tests for bus pipeline"
```

---

## Task 7: Final verification

**Files:** No new files.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Compile + ktlintCheck + 44 tests + assemble.

- [ ] **Step 2: Run main, confirm same output as Phase 1**

Run: `./gradlew run`
Expected: 10 `FILLED` lines + `Done.`. Specific prices (deterministic from seed 42):

```
FILLED: BUY 1.0 XAUUSD @ 2382.1840422745718
... (8 more lines)
FILLED: BUY 1.0 XAUUSD @ 2465.366907086234
Done.
```

- [ ] **Step 3: Verify test count**

Run: `./gradlew test 2>&1 | grep -E "tests completed|tests, [0-9]+ failures"`

Or count by file:

```bash
find src/test -name "*Test.kt" -exec grep -c "@Test" {} +
```

Expected: 44 total tests across the 8 test files.

- [ ] **Step 4: Verify file counts and structure**

Run: `find src/main -name "*.kt" -type f | sort`
Expected output:

```
src/main/kotlin/com/qkt/app/Main.kt
src/main/kotlin/com/qkt/broker/Broker.kt
src/main/kotlin/com/qkt/broker/MockBroker.kt
src/main/kotlin/com/qkt/bus/EventBus.kt
src/main/kotlin/com/qkt/common/Clock.kt
src/main/kotlin/com/qkt/common/IdGenerator.kt
src/main/kotlin/com/qkt/common/Side.kt
src/main/kotlin/com/qkt/engine/Engine.kt
src/main/kotlin/com/qkt/events/Event.kt
src/main/kotlin/com/qkt/execution/Order.kt
src/main/kotlin/com/qkt/execution/OrderType.kt
src/main/kotlin/com/qkt/execution/Trade.kt
src/main/kotlin/com/qkt/marketdata/Candle.kt
src/main/kotlin/com/qkt/marketdata/MarketPriceProvider.kt
src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt
src/main/kotlin/com/qkt/marketdata/Tick.kt
src/main/kotlin/com/qkt/marketdata/TickFeed.kt
src/main/kotlin/com/qkt/strategy/EveryNthTickBuyStrategy.kt
src/main/kotlin/com/qkt/strategy/Signal.kt
src/main/kotlin/com/qkt/strategy/Strategy.kt
```

20 production files (was 18; added `bus/EventBus.kt` and `events/Event.kt`).

```bash
find src/test -name "*.kt" -type f | sort
```

Expected:

```
src/test/kotlin/com/qkt/app/EndToEndTest.kt
src/test/kotlin/com/qkt/broker/MockBrokerTest.kt
src/test/kotlin/com/qkt/bus/EventBusTest.kt
src/test/kotlin/com/qkt/common/MonotonicSequenceGeneratorTest.kt
src/test/kotlin/com/qkt/engine/EngineTest.kt
src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt
src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt
src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt
```

8 test files (was 5; added `app/EndToEndTest.kt`, `bus/EventBusTest.kt`, `common/MonotonicSequenceGeneratorTest.kt`).

- [ ] **Step 5: Verify line counts under cap**

Run: `wc -l src/main/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest production file under 100 lines. `EventBus.kt` and `Main.kt` are the larger ones.

Run: `wc -l src/test/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest test file under 200 lines. `EndToEndTest.kt` and `EventBusTest.kt` will be the largest.

If any file exceeds 200 lines, that's a refactor signal — flag in the report but don't restructure unilaterally.

- [ ] **Step 6: Run precheck script**

Run: `./scripts/precheck.sh`
Expected: all 6 steps pass.

- [ ] **Step 7: Verify acceptance criteria**

Cross-check spec §13:

- [x] EventBus, Event sealed class, TickEvent/SignalEvent/OrderEvent/TradeEvent, SequenceGenerator/MonotonicSequenceGenerator exist at correct paths
- [x] Engine.kt is reduced (no Strategy, Broker, Clock, IdGenerator, onTrade params)
- [x] Main.kt wires the pipeline through the bus
- [x] All 44 tests pass
- [x] `./gradlew build` green
- [x] `./gradlew run` produces 10 FILLED lines + Done.
- [x] slf4j-api and slf4j-simple via the version catalog
- [x] Cross-symbol test in EndToEndTest.kt passes
- [x] git log is clean and uses conventional commit messages
- [x] No file exceeds 200 lines

When all boxes are checked, Phase 2a is done.

- [ ] **Step 8: No commit**

This task introduces no new files or changes — it's pure verification. If everything passes, no commit is needed. Phase 2a is complete on the `phase2a-event-bus` branch, ready for merge to `main` per the project skill (§5: PR with the standard description, `--no-ff` merge, delete branch).

---

## Notes for the implementing engineer

- **Run tests after every TDD task.** Don't batch.
- **Don't use mocks.** Anonymous objects (`object : Strategy { ... }`) and capture lists (`mutableListOf<Trade>()`) are sufficient. Per CLAUDE.md and the project skill, mocking internals is forbidden.
- **Follow ktlint output.** If ktlint fails after a code edit, run `./gradlew ktlintFormat` and re-stage the auto-formatted files. The conventions enforced by ktlint are not negotiable.
- **Match Kotlin style exactly.** No semicolons. No `public` modifier. No emojis. No useless comments.
- **Frequent small commits per task.** Each task ends in exactly one commit. The Task 5 refactor is the only multi-file commit; everything else is one or two files.
- **If a test fails unexpectedly:** read the error, fix the production code (or the test if the test was wrong by construction), re-run. Don't disable tests. Don't catch and swallow.
- **No emoji in code or commit messages.** Per CLAUDE.md and the project skill §3.
- **Commit message types:** use `feat`, `refactor`, `test`, `build`, `chore`, `docs`, `style` per the project skill §3. Subject only, no body, no footer.
- **The Task 5 refactor is intentional.** The 6 EngineTests removed are not lost coverage — their concerns are tested at the integration level in EndToEndTest (Task 6). This is documented in the spec §9 and reflected in the test count (-6 EngineTest, +6 EndToEndTest, net 0; plus +10 EventBus, +3 SeqGen, total +13 to 44).
