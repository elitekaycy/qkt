# Trading Engine Phase 2b Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tick-driven, clock-aligned candle aggregator. Strategies gain a `onCandle` callback. `MockTickFeed` gains tick-interval timestamps so streams span window boundaries. `Main.kt` wires the aggregator before strategy subscriptions to preserve depth-first ordering (closed candle reaches `Strategy.onCandle` before the triggering tick reaches `Strategy.onTick`).

**Architecture:** New `com.qkt.candles` package containing `TimeWindow` (`@JvmInline value class` with `windowStartFor`/`windowEndFor` helpers) and `CandleAggregator` (subscribes to `TickEvent`, holds per-symbol `MutableCandle` state, publishes `CandleEvent` on window roll). New `CandleEvent` variant of the existing `Event` sealed class. `Strategy` interface gains a default-no-op `onCandle`. `MockTickFeed` gains a `tickIntervalMs: Long = 1_000L` parameter.

**Tech Stack:** Kotlin 2.1.0, JDK 21 toolchain, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16. All already in place from Phase 2a — no new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase2b-design.md`](../specs/2026-05-03-trading-engine-phase2b-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase2b-candles`. Phase 2a is on `main` with the Phase 2b spec committed (`cf5c7f8`).

---

## Task ordering

```
1. TimeWindow + TimeWindowTest                                         [TDD]
2. CandleEvent variant + EventBus.stamp branch + EventBusTest +1
3. Strategy.onCandle default no-op
4. MockTickFeed tickIntervalMs + MockTickFeedTest changes              [TDD]
5. CandleAggregator + CandleAggregatorTest                             [TDD]
6. Main.kt wiring + EndToEndTest +3
7. Final verification                                                   [no new files]
```

7 tasks. Each ends in a commit. Tasks 1, 4, 5 are full TDD (red → green). Task 2 adds a data class and a `when` branch. Task 3 is a one-line interface extension. Task 6 wires `main()` and adds integration tests. Task 7 is verification.

---

## Task 1: TimeWindow + TimeWindowTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/candles/TimeWindowTest.kt`
- Create: `src/main/kotlin/com/qkt/candles/TimeWindow.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/candles/TimeWindowTest.kt`:

```kotlin
package com.qkt.candles

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeWindowTest {
    @Test
    fun `windowStartFor rounds down to nearest multiple of durationMs`() {
        val window = TimeWindow(60_000L)
        assertThat(window.windowStartFor(0L)).isEqualTo(0L)
        assertThat(window.windowStartFor(23_456L)).isEqualTo(0L)
        assertThat(window.windowStartFor(59_999L)).isEqualTo(0L)
        assertThat(window.windowStartFor(60_000L)).isEqualTo(60_000L)
        assertThat(window.windowStartFor(60_001L)).isEqualTo(60_000L)
        assertThat(window.windowStartFor(125_678L)).isEqualTo(120_000L)
    }

    @Test
    fun `windowEndFor returns windowStartFor plus durationMs`() {
        val window = TimeWindow(60_000L)
        assertThat(window.windowEndFor(23_456L)).isEqualTo(60_000L)
        assertThat(window.windowEndFor(60_000L)).isEqualTo(120_000L)
        assertThat(window.windowEndFor(125_678L)).isEqualTo(180_000L)
    }

    @Test
    fun `windowStartFor is idempotent on a boundary timestamp`() {
        val window = TimeWindow(60_000L)
        val boundary = 60_000L
        assertThat(window.windowStartFor(boundary)).isEqualTo(boundary)
        assertThat(window.windowStartFor(window.windowStartFor(boundary))).isEqualTo(boundary)
    }

    @Test
    fun `throws on zero or negative durationMs`() {
        assertThatThrownBy { TimeWindow(0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TimeWindow(-1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `named constants have expected duration values`() {
        assertThat(TimeWindow.ONE_SECOND.durationMs).isEqualTo(1_000L)
        assertThat(TimeWindow.ONE_MINUTE.durationMs).isEqualTo(60_000L)
        assertThat(TimeWindow.FIVE_MINUTES.durationMs).isEqualTo(300_000L)
        assertThat(TimeWindow.FIFTEEN_MINUTES.durationMs).isEqualTo(900_000L)
        assertThat(TimeWindow.ONE_HOUR.durationMs).isEqualTo(3_600_000L)
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.candles.TimeWindowTest"`
Expected: compile failure — `Unresolved reference 'TimeWindow'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement TimeWindow**

`src/main/kotlin/com/qkt/candles/TimeWindow.kt`:

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

Notes:
- `@JvmInline value class` makes this a zero-cost wrapper (compiles to a `Long` at runtime).
- `init { require(...) }` runs after the property initialization, throws `IllegalArgumentException` if duration is non-positive.
- The companion object holds named constants. They are themselves `TimeWindow` instances; their `durationMs` is the value tests assert on.

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.candles.TimeWindowTest"`
Expected: 5 tests pass.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean, all 49 tests pass (44 Phase 2a + 5 new).

If ktlint fails on the new file, run `./gradlew ktlintFormat` and re-run. Accept its formatting.

- [ ] **Step 6: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/candles/TimeWindow.kt src/test/kotlin/com/qkt/candles/TimeWindowTest.kt
git commit -m "feat(candles): add TimeWindow value class with helpers"
```

---

## Task 2: CandleEvent variant + EventBus.stamp branch + EventBusTest +1

**Files:**
- Modify: `src/main/kotlin/com/qkt/events/Event.kt` (add variant)
- Modify: `src/main/kotlin/com/qkt/bus/EventBus.kt` (add `when` branch)
- Modify: `src/test/kotlin/com/qkt/bus/EventBusTest.kt` (add 1 test)

- [ ] **Step 1: Add `CandleEvent` to `Event.kt`**

Current `src/main/kotlin/com/qkt/events/Event.kt` (read first, append):

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

data class TickEvent(...) : Event()
data class SignalEvent(...) : Event()
data class OrderEvent(...) : Event()
data class TradeEvent(...) : Event()
```

Replace the entire file with:

```kotlin
package com.qkt.events

import com.qkt.execution.Order
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
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

data class CandleEvent(
    val candle: Candle,
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

- [ ] **Step 2: Compile — expect failure on EventBus.stamp**

Run: `./gradlew compileKotlin`
Expected: compile failure in `EventBus.kt` — the `when (event)` in `stamp` is not exhaustive because `CandleEvent` was added to the sealed hierarchy. Error message will be like:

```
src/main/kotlin/com/qkt/bus/EventBus.kt:39:16 'when' expression must be exhaustive. Add the 'is CandleEvent' branch or 'else' branch.
```

This is the desired behavior — sealed-class enforcement catches the missing branch at compile time.

- [ ] **Step 3: Add `is CandleEvent ->` branch to `EventBus.stamp`**

Current `src/main/kotlin/com/qkt/bus/EventBus.kt` `stamp` method (read first, find this section):

```kotlin
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
```

Add `import com.qkt.events.CandleEvent` to the imports near the top, and add the `is CandleEvent ->` branch in alphabetical order (or matching the order in `Event.kt`):

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

Do not change anything else in `EventBus.kt`.

- [ ] **Step 4: Verify compile**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add the EventBusTest case for CandleEvent stamping**

Current `src/test/kotlin/com/qkt/bus/EventBusTest.kt` already has 10 tests. Add an 11th test inside the class body (e.g., right after the existing `bus stamps timestamp from clock on publish` test) — and add the necessary import for `Candle` to the imports near the top.

Add this import (alongside the existing ones):

```kotlin
import com.qkt.marketdata.Candle
```

Add this test (anywhere in the class body):

```kotlin
@Test
fun `bus stamps CandleEvent on publish`() {
    val bus = newBus()
    val received = mutableListOf<CandleEvent>()
    bus.subscribe<CandleEvent> { received.add(it) }

    clock.time = 7777L
    bus.publish(
        CandleEvent(
            Candle("XAUUSD", open = 100.0, high = 101.0, low = 99.0, close = 100.5, volume = 12.0, startTime = 0L, endTime = 60_000L),
        ),
    )

    assertThat(received).hasSize(1)
    assertThat(received[0].timestamp).isEqualTo(7777L)
    assertThat(received[0].sequenceId).isEqualTo(0L)
    assertThat(received[0].candle.symbol).isEqualTo("XAUUSD")
}
```

- [ ] **Step 6: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. ktlint clean, 50 tests pass (44 Phase 2a + 5 TimeWindow + 1 new EventBus).

If ktlint fails, run `./gradlew ktlintFormat`, re-run.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/events/Event.kt src/main/kotlin/com/qkt/bus/EventBus.kt src/test/kotlin/com/qkt/bus/EventBusTest.kt
git commit -m "feat(events): add CandleEvent variant and stamp branch"
```

---

## Task 3: Strategy.onCandle default no-op

**Files:**
- Modify: `src/main/kotlin/com/qkt/strategy/Strategy.kt`

- [ ] **Step 1: Replace `Strategy.kt`**

Current `src/main/kotlin/com/qkt/strategy/Strategy.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(tick: Tick, emit: (Signal) -> Unit)
}
```

Replace with:

```kotlin
package com.qkt.strategy

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

interface Strategy {
    fun onTick(tick: Tick, emit: (Signal) -> Unit)
    fun onCandle(candle: Candle, emit: (Signal) -> Unit) {}
}
```

The `onCandle` method has a default empty body. `EveryNthTickBuyStrategy` and any test-only strategy implementations that previously implemented `Strategy` continue to compile unchanged.

- [ ] **Step 2: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. All 50 tests still pass. `EveryNthTickBuyStrategyTest` and `EngineTest` and `EndToEndTest` should be unaffected (the default no-op means existing strategies behave identically).

If ktlint fails, format and re-run.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/strategy/Strategy.kt
git commit -m "feat(strategy): add onCandle default no-op to Strategy interface"
```

---

## Task 4: MockTickFeed tickIntervalMs + MockTickFeedTest changes [TDD]

**Files:**
- Modify: `src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt`
- Modify: `src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt` (1 modified, 2 new)

- [ ] **Step 1: Modify the existing `each tick has clock's current timestamp` test**

Current `src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt` contains:

```kotlin
@Test
fun `each tick has clock's current timestamp`() {
    val clock = FixedClock(1714723200000L)
    val feed = MockTickFeed("XAUUSD", 2400.0, count = 1, clock = clock)
    val tick = feed.next()
    assertThat(tick?.timestamp).isEqualTo(1714723200000L)
}
```

Replace it with:

```kotlin
@Test
fun `each tick has clock's start time plus interval offset`() {
    val clock = FixedClock(1714723200000L)
    val feed = MockTickFeed("XAUUSD", 2400.0, count = 3, clock = clock, tickIntervalMs = 1_000L)
    val tick0 = feed.next()
    val tick1 = feed.next()
    val tick2 = feed.next()
    assertThat(tick0?.timestamp).isEqualTo(1714723200000L)
    assertThat(tick1?.timestamp).isEqualTo(1714723201000L)
    assertThat(tick2?.timestamp).isEqualTo(1714723202000L)
}
```

- [ ] **Step 2: Add two new tests in the same file**

Add inside the `MockTickFeedTest` class body:

```kotlin
@Test
fun `tickIntervalMs default is 1000L`() {
    val clock = FixedClock(0L)
    val feed = MockTickFeed("X", 100.0, count = 2, clock = clock)
    assertThat(feed.next()?.timestamp).isEqualTo(0L)
    assertThat(feed.next()?.timestamp).isEqualTo(1_000L)
}

@Test
fun `throws on non-positive tickIntervalMs`() {
    assertThatThrownBy { MockTickFeed("X", 100.0, 1, FixedClock(0L), tickIntervalMs = 0L) }
        .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { MockTickFeed("X", 100.0, 1, FixedClock(0L), tickIntervalMs = -1L) }
        .isInstanceOf(IllegalArgumentException::class.java)
}
```

- [ ] **Step 3: Run modified+new tests, expect FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.marketdata.MockTickFeedTest"`
Expected: compile failure or test failures. The constructor call `MockTickFeed("X", 100.0, 1, FixedClock(0L), tickIntervalMs = 0L)` references a `tickIntervalMs` parameter that the current `MockTickFeed` does not have. Compile fails.

- [ ] **Step 4: Modify `MockTickFeed` to add `tickIntervalMs`**

Current `src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt`:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Clock
import kotlin.random.Random

class MockTickFeed(
    private val symbol: String,
    private val startPrice: Double,
    private val count: Int,
    private val clock: Clock,
    private val random: Random = Random(seed = 42L),
) : TickFeed {
    init {
        require(count >= 0) { "count must be >= 0: $count" }
        require(startPrice > 0.0) { "startPrice must be > 0: $startPrice" }
    }

    private var emitted = 0
    private var price = startPrice

    override fun next(): Tick? {
        if (emitted >= count) return null
        price *= (1.0 + (random.nextDouble() - 0.5) * 0.01)
        emitted++
        return Tick(symbol, price, clock.now())
    }
}
```

Replace with:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Clock
import kotlin.random.Random

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

Critical changes:
- `tickIntervalMs: Long = 1_000L` is the new constructor parameter, ordered before `random` (which already has a default). Existing call sites in `Main.kt` and tests can omit it (default applies) or pass it explicitly.
- New `init` `require` for `tickIntervalMs > 0L`.
- `startTime = clock.now()` captured ONCE at construction (in property initializer).
- Each tick's timestamp is `startTime + emitted * tickIntervalMs`, deterministic from the start.

- [ ] **Step 5: Run all `MockTickFeedTest` cases, confirm PASS**

Run: `./gradlew test --tests "com.qkt.marketdata.MockTickFeedTest"`
Expected: 8 tests PASS (the original 6 had 1 modified, plus 2 new = 8 total). Output should show eight `MockTickFeedTest > ...() PASSED` lines.

If a test fails, **report BLOCKED** with output. Do not modify a test to make it pass.

- [ ] **Step 6: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. 52 tests total (50 from Phase 2a+1+2 minus original `each tick has clock's current timestamp` plus the 3 new MockTickFeed tests = 52). ktlint clean.

If ktlint fails, format and re-run.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/marketdata/MockTickFeed.kt src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt
git commit -m "feat(marketdata): add tickIntervalMs to MockTickFeed"
```

---

## Task 5: CandleAggregator + CandleAggregatorTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt`
- Create: `src/main/kotlin/com/qkt/candles/CandleAggregator.kt`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt`:

```kotlin
package com.qkt.candles

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleAggregatorTest {
    private val clock = FixedClock(0L)
    private val sequencer = MonotonicSequenceGenerator()
    private val bus = EventBus(clock, sequencer)
    private val captured = mutableListOf<CandleEvent>()

    init {
        bus.subscribe<CandleEvent> { captured.add(it) }
    }

    private fun aggregator() = CandleAggregator(bus, TimeWindow.ONE_MINUTE)

    private fun publishTick(symbol: String, price: Double, ts: Long, volume: Double? = null) {
        bus.publish(TickEvent(Tick(symbol, price, ts, volume)))
    }

    @Test
    fun `first tick for a symbol does not emit a CandleEvent`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `tick within current window updates OHLC in place without emitting`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.5, 30_000L)
        publishTick("XAUUSD", 2399.5, 45_000L)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `tick past current endTime emits CandleEvent for the closed window`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 30_000L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.symbol).isEqualTo("XAUUSD")
    }

    @Test
    fun `closed candle has correct OHLC computed from all ticks in the window`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.5, 15_000L)
        publishTick("XAUUSD", 2399.5, 30_000L)
        publishTick("XAUUSD", 2400.8, 45_000L)
        publishTick("XAUUSD", 2402.0, 75_000L)
        assertThat(captured).hasSize(1)
        val c = captured[0].candle
        assertThat(c.open).isEqualTo(2400.0)
        assertThat(c.high).isEqualTo(2401.5)
        assertThat(c.low).isEqualTo(2399.5)
        assertThat(c.close).isEqualTo(2400.8)
    }

    @Test
    fun `closed candle's startTime is window-aligned not first-tick timestamp`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 23_456L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.startTime).isEqualTo(0L)
    }

    @Test
    fun `closed candle's endTime is startTime plus durationMs`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        val c = captured[0].candle
        assertThat(c.endTime).isEqualTo(c.startTime + 60_000L)
    }

    @Test
    fun `volume sums only non-null tick volumes`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L, volume = 1.5)
        publishTick("XAUUSD", 2400.5, 30_000L, volume = null)
        publishTick("XAUUSD", 2400.2, 45_000L, volume = 2.5)
        publishTick("XAUUSD", 2401.0, 75_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.volume).isEqualTo(4.0)
    }

    @Test
    fun `windows for different symbols are tracked independently`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("EURUSD", 1.0921, 30_000L)
        publishTick("XAUUSD", 2401.0, 75_000L)
        publishTick("EURUSD", 1.0930, 75_000L)
        assertThat(captured).hasSize(2)
        assertThat(captured.map { it.candle.symbol }).containsExactly("XAUUSD", "EURUSD")
    }

    @Test
    fun `boundary timestamp triggers window roll`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 30_000L)
        publishTick("XAUUSD", 2401.0, 60_000L)
        assertThat(captured).hasSize(1)
        assertThat(captured[0].candle.startTime).isEqualTo(0L)
        assertThat(captured[0].candle.endTime).isEqualTo(60_000L)
    }

    @Test
    fun `multiple consecutive rolls each emit one CandleEvent`() {
        aggregator()
        publishTick("XAUUSD", 2400.0, 0L)
        publishTick("XAUUSD", 2401.0, 60_000L)
        publishTick("XAUUSD", 2402.0, 120_000L)
        publishTick("XAUUSD", 2403.0, 180_000L)
        assertThat(captured).hasSize(3)
        assertThat(captured.map { it.candle.startTime }).containsExactly(0L, 60_000L, 120_000L)
    }
}
```

Notes:
- The test class uses real `EventBus`, real `FixedClock`, real `MonotonicSequenceGenerator`. No mocks per CLAUDE.md.
- Tests publish `TickEvent` directly through the bus, not through `Engine`. This isolates the aggregator's behavior.
- The capture list (`captured`) is populated by a `CandleEvent` subscriber registered in the test class's `init` block. The subscription is registered BEFORE the `aggregator()` factory method is called in each test, so the capture subscriber and the aggregator subscriber are both attached to the `TickEvent`'s downstream `CandleEvent` flow.
- `publishTick` helper avoids constructing `Tick` boilerplate in every assertion.

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.candles.CandleAggregatorTest"`
Expected: compile failure — `Unresolved reference 'CandleAggregator'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement CandleAggregator**

`src/main/kotlin/com/qkt/candles/CandleAggregator.kt`:

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

Critical implementation notes:
- The constructor's `init` block subscribes to `TickEvent`. Side-effecting constructor is intentional per spec D11.
- `MutableCandle` is a `private class` inside the aggregator. It has `var` fields for high/low/close/volume (in-place updates) and `val` for everything else.
- `newState` aligns `startTime` to `window.windowStartFor(tick.timestamp)`, NOT the tick's own timestamp.
- `state.update` implements volume mixed-mode (D8): OHLC always updated, volume only added when non-null.
- Roll trigger is `tick.timestamp >= state.endTime` (using the half-open `[start, end)` convention).

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.candles.CandleAggregatorTest"`
Expected: 10 tests PASS.

If a test fails, **report BLOCKED** with the failure output. Don't modify the test.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. 62 tests total (52 prior + 10 new). ktlint clean.

If ktlint fails, format and re-run.

- [ ] **Step 6: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/candles/CandleAggregator.kt src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt
git commit -m "feat(candles): add CandleAggregator with tick-driven window roll"
```

---

## Task 6: Main.kt wiring + EndToEndTest +3

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Main.kt`
- Modify: `src/test/kotlin/com/qkt/app/EndToEndTest.kt` (add 3 tests)

- [ ] **Step 1: Replace `Main.kt`**

Current `src/main/kotlin/com/qkt/app/Main.kt` was the Phase 2a version (no aggregator, no candle subscription). Replace the entire file with:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
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

    CandleAggregator(bus, TimeWindow.ONE_MINUTE)

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
        log.info(
            "CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
            c.symbol,
            c.open,
            c.high,
            c.low,
            c.close,
            c.volume,
            c.startTime,
            c.endTime,
        )
    }

    val feed =
        MockTickFeed(
            symbol = "XAUUSD",
            startPrice = 2400.0,
            count = 100,
            clock = clock,
            tickIntervalMs = 1_000L,
        )
    while (true) {
        val tick = feed.next() ?: break
        engine.onTick(tick)
    }
    log.info("Done.")
}

private fun Signal.toOrder(
    id: String,
    ts: Long,
): Order =
    when (this) {
        is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
        is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
    }
```

Key changes from Phase 2a:
- New imports for `CandleAggregator`, `TimeWindow`, `CandleEvent`.
- `CandleAggregator(bus, TimeWindow.ONE_MINUTE)` instantiated BEFORE the strategy subscriptions register. This locks in "aggregator runs before strategies" ordering (D7).
- Each strategy gains a `bus.subscribe<CandleEvent>` alongside its existing `TickEvent` subscription.
- A new `bus.subscribe<CandleEvent>` logger that prints CANDLE lines at INFO.
- `MockTickFeed` constructor now passes `tickIntervalMs = 1_000L` (it's the default but explicit reads better).

- [ ] **Step 2: Compile and run main, confirm CANDLE output**

Run: `./gradlew run`
Expected: a mix of `FILLED:` and `CANDLE:` lines, ending with `Done.`. Example shape:

```
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2382.18...
[main] INFO Main - FILLED: BUY 1.0 XAUUSD @ 2377.52...
... more FILLED lines ...
[main] INFO Main - CANDLE: XAUUSD O=2400.0 H=240X.X L=23XX.X C=23XX.X V=0.0 [<startTs>, <endTs>)
... more FILLED and possibly more CANDLE lines ...
[main] INFO Main - Done.
```

The exact number of CANDLE lines depends on where the `SystemClock` starts relative to a minute boundary (could be 0, 1, or 2 candles in a 100-second stream). The exact count is not asserted.

If no CANDLE line appears at all, the run started so close to the next minute boundary that no roll occurred during 100 ticks — possible but unusual. Re-run to confirm. If CANDLE never appears, **report DONE_WITH_CONCERNS**.

If `./gradlew run` errors, **report BLOCKED**.

- [ ] **Step 3: Add 3 tests to `EndToEndTest.kt`**

Current `src/test/kotlin/com/qkt/app/EndToEndTest.kt` has 6 tests. Add 3 more inside the class body, plus the necessary imports.

Add these imports (alongside the existing ones):

```kotlin
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.events.CandleEvent
import com.qkt.marketdata.Candle
```

Add the following 3 tests inside `EndToEndTest`'s class body. They use a slightly different pattern than the existing tests because they need an aggregator wired:

```kotlin
@Test
fun `tick stream spanning a window boundary produces a CandleEvent`() {
    CandleAggregator(bus, TimeWindow.ONE_MINUTE)
    val captured = mutableListOf<Candle>()
    bus.subscribe<CandleEvent> { captured.add(it.candle) }
    wirePipeline(emptyList())

    engine.onTick(Tick("XAUUSD", 2400.0, 0L))
    engine.onTick(Tick("XAUUSD", 2401.0, 30_000L))
    engine.onTick(Tick("XAUUSD", 2402.0, 75_000L))

    assertThat(captured).hasSize(1)
    assertThat(captured[0].symbol).isEqualTo("XAUUSD")
    assertThat(captured[0].startTime).isEqualTo(0L)
}

@Test
fun `strategy receiving onCandle can emit a signal that fills`() {
    tracker.update("XAUUSD", 2400.0)
    CandleAggregator(bus, TimeWindow.ONE_MINUTE)
    val candleStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                emit: (Signal) -> Unit,
            ) {
            }

            override fun onCandle(
                candle: Candle,
                emit: (Signal) -> Unit,
            ) {
                emit(Signal.Buy(candle.symbol, 1.0))
            }
        }
    wirePipeline(listOf(candleStrategy))

    engine.onTick(Tick("XAUUSD", 2400.0, 30_000L))
    engine.onTick(Tick("XAUUSD", 2400.5, 75_000L))

    assertThat(trades).hasSize(1)
    assertThat(trades[0].symbol).isEqualTo("XAUUSD")
    assertThat(trades[0].side).isEqualTo(Side.BUY)
}

@Test
fun `aggregator subscribes before strategies see the same tick`() {
    val sequence = mutableListOf<String>()
    val orderingStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                emit: (Signal) -> Unit,
            ) {
                sequence.add("onTick(${tick.timestamp})")
            }

            override fun onCandle(
                candle: Candle,
                emit: (Signal) -> Unit,
            ) {
                sequence.add("onCandle(${candle.startTime})")
            }
        }
    CandleAggregator(bus, TimeWindow.ONE_MINUTE)
    wirePipeline(listOf(orderingStrategy))

    engine.onTick(Tick("XAUUSD", 2400.0, 30_000L))
    engine.onTick(Tick("XAUUSD", 2401.0, 75_000L))

    assertThat(sequence).containsExactly(
        "onTick(30000)",
        "onCandle(0)",
        "onTick(75000)",
    )
}
```

The third test is the key wiring-order verification: the second tick (at 75_000L) triggers a window roll. The aggregator emits `CandleEvent` first (depth-first nested dispatch), so the strategy's `onCandle` runs BEFORE the strategy's own `onTick` for the triggering tick. Asserted by the exact sequence above.

The second test depends on the existing helper `wirePipeline` already wiring `CandleEvent` subscriptions for strategies. **VERIFY** that the existing `wirePipeline` from Phase 2a wires both `TickEvent` and `CandleEvent` for each strategy. If it doesn't, this is the place to extend it. The Phase 2a version of the helper looked like:

```kotlin
private fun wirePipeline(strategies: List<Strategy>, captureOrders: Boolean = false) {
    strategies.forEach { s ->
        bus.subscribe<TickEvent> { e ->
            s.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
        }
    }
    bus.subscribe<SignalEvent> { ... }
    bus.subscribe<OrderEvent> { ... }
    bus.subscribe<TradeEvent> { e -> trades.add(e.trade) }
}
```

Update it to also subscribe each strategy to `CandleEvent`:

```kotlin
private fun wirePipeline(strategies: List<Strategy>, captureOrders: Boolean = false) {
    strategies.forEach { s ->
        bus.subscribe<TickEvent> { e ->
            s.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
        }
        bus.subscribe<CandleEvent> { e ->
            s.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
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
```

- [ ] **Step 4: Run EndToEndTest, confirm 9 tests pass**

Run: `./gradlew test --tests "com.qkt.app.EndToEndTest"`
Expected: 9 tests PASS (6 from Phase 2a + 3 new). Each prints `EndToEndTest > ...() PASSED`.

If a test fails, **report BLOCKED**. The third (ordering) test failing would indicate the aggregator is NOT subscribing before strategies — debug by checking `Main.kt` and `wirePipeline` ordering.

- [ ] **Step 5: Run full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. 65 tests total. ktlint clean.

If ktlint fails, format and re-run.

- [ ] **Step 6: Run main one more time, capture output**

Run: `./gradlew run`
Expected: 10 `FILLED:` lines + at least 1 `CANDLE:` line + `Done.`. Capture output for verification.

- [ ] **Step 7: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/app/Main.kt src/test/kotlin/com/qkt/app/EndToEndTest.kt
git commit -m "feat(app): wire CandleAggregator and add candle integration tests"
```

---

## Task 7: Final verification

**Files:** No new files.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. Compile + ktlintCheck + 65 tests + assemble.

- [ ] **Step 2: Force test re-run, count PASSED**

Run: `./gradlew test --rerun-tasks 2>&1 | grep -cE "PASSED$"`
Expected: `65`.

- [ ] **Step 3: Run main, confirm CANDLE output**

Run: `./gradlew run 2>&1 | grep -E "FILLED|CANDLE|Done"`
Expected: 10 `FILLED:` lines, at least 1 `CANDLE:` line, then `Done.`.

If no CANDLE line appears across multiple runs, **report DONE_WITH_CONCERNS**. The most likely cause is `MockTickFeed` constructing on a system clock that lands very close to a minute boundary by chance — re-running should produce a different result.

- [ ] **Step 4: Verify file count and structure**

Run: `find src -name "*.kt" -type f | sort`
Expected: 32 files (22 production + 10 test).

```
src/main/kotlin/com/qkt/app/Main.kt
src/main/kotlin/com/qkt/broker/Broker.kt
src/main/kotlin/com/qkt/broker/MockBroker.kt
src/main/kotlin/com/qkt/bus/EventBus.kt
src/main/kotlin/com/qkt/candles/CandleAggregator.kt
src/main/kotlin/com/qkt/candles/TimeWindow.kt
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
src/test/kotlin/com/qkt/app/EndToEndTest.kt
src/test/kotlin/com/qkt/broker/MockBrokerTest.kt
src/test/kotlin/com/qkt/bus/EventBusTest.kt
src/test/kotlin/com/qkt/candles/CandleAggregatorTest.kt
src/test/kotlin/com/qkt/candles/TimeWindowTest.kt
src/test/kotlin/com/qkt/common/MonotonicSequenceGeneratorTest.kt
src/test/kotlin/com/qkt/engine/EngineTest.kt
src/test/kotlin/com/qkt/marketdata/MarketPriceTrackerTest.kt
src/test/kotlin/com/qkt/marketdata/MockTickFeedTest.kt
src/test/kotlin/com/qkt/strategy/EveryNthTickBuyStrategyTest.kt
```

Counts by package:
- Main: app(1) + broker(2) + bus(1) + candles(2) + common(3) + engine(1) + events(1) + execution(3) + marketdata(5) + strategy(3) = 22
- Test: app(1) + broker(1) + bus(1) + candles(2) + common(1) + engine(1) + marketdata(2) + strategy(1) = 10
- Total: 32 `.kt` files.

- [ ] **Step 5: Verify line counts**

Run: `wc -l src/main/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest production file under 100 lines. `CandleAggregator.kt` and `Main.kt` are likely the biggest.

Run: `wc -l src/test/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest test file under 200 lines. `EndToEndTest.kt` will probably be ~270 lines after the +3 — this is over the spec's "no file exceeds ~200 lines" guideline. **Report DONE_WITH_CONCERNS** noting the size; per the project skill it's a refactor signal but not a hard blocker for Phase 2b.

- [ ] **Step 6: Run precheck**

Run: `./scripts/precheck.sh`
Expected: all 6 steps PASS.

- [ ] **Step 7: Verify acceptance criteria from spec §13**

Cross-check each box:

- [x] `TimeWindow`, `CandleAggregator` exist in `com.qkt.candles`
- [x] `CandleEvent` exists in `com.qkt.events`; `EventBus.stamp` handles it
- [x] `Strategy.onCandle(candle, emit)` exists with default no-op body
- [x] `MockTickFeed` accepts `tickIntervalMs` (default `1_000L`); timestamps progress
- [x] All 65 tests pass
- [x] `./gradlew build` green
- [x] `./gradlew run` produces at least one `CANDLE:` log line
- [x] `EveryNthTickBuyStrategy` continues to work unchanged
- [x] Aggregator subscribes BEFORE strategies in `main()` (verified by EndToEndTest case 3)
- [x] git log clean and uses conventional commit messages

When all boxes are checked, Phase 2b is complete on the `phase2b-candles` branch, ready for merge to `main`.

- [ ] **Step 8: No commit needed**

Task 7 is verification only.

---

## Notes for the implementing engineer

- **Run tests after every TDD task.** Don't batch.
- **Don't use mocks.** Anonymous objects (`object : Strategy { ... }`) and capture lists are sufficient.
- **Follow ktlint output.** Run `./gradlew ktlintFormat` if `check` fails on it; re-stage the formatted files with the source changes in the same commit.
- **Match Kotlin style exactly.** No semicolons. No `public` modifier. No emojis. No useless comments.
- **Frequent small commits per task.** Each task ends in exactly one commit.
- **If a test fails unexpectedly:** read the error, fix the production code (or the test if it was wrong by construction), re-run. Don't disable tests. Don't catch and swallow.
- **No emoji in code or commit messages.** Per CLAUDE.md and the project skill §3.
- **Commit message types:** use `feat`, `refactor`, `test`, `build`, `chore`, `docs`, `style` per the project skill §3. Subject only, no body, no footer, no AI references.
- **Task 6's `EndToEndTest.kt` will likely exceed 200 lines.** Per the project skill, that's a refactor signal but not a hard blocker for Phase 2b. Flag in Task 7 Step 5; Phase 3 may motivate splitting (e.g., into `EndToEndTest` + `EndToEndCandleTest`).
