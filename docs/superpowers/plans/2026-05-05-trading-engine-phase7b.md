# Phase 7b — Live Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the live runtime on top of the Phase 7a primitives. Add the `TradingPipeline` warmup split, `WarmupSpec` + `Warmable`, `IndicatorWarmer`, the push→pull `LiveTickFeed`, and the `LiveSession` engine-thread driver. After this plan merges, qkt can run `LiveSession` against any `MarketSource` whose `liveTicks(...)` produces ticks — including the in-memory test source — without any vendor-specific code. TradingView lands in Plan 7c.

**Architecture:** Phase 7a delivered `MarketSource`, `LocalMarketSource`, `CompositeMarketSource`, `TimeMark`, `TradingCalendar`, `RangeAggregateIndicator`, `SessionAnchoredIndicator`, `Mode`, `SessionContext`, and `Strategy.onTickWithContext`. Plan 7b assumes those are merged. The live runtime uses two Phase 1–4 invariants: a single deterministic engine thread owns pipeline state, and live ticks adapt push→pull through a bounded `BlockingQueue`. Warmup runs the same `MarketSource.bars(...)` we already use in `RangeAggregateIndicator`, but pushes synthesized ticks through a new `pipeline.ingestForWarmup(...)` ingress that bypasses strategies and risk so warmup never produces fictitious trades.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5, AssertJ, kotlinx-serialization-json (already a dep). No new runtime dependencies in this plan; OkHttp is added by Plan 7c.

**Spec:** [`docs/superpowers/specs/2026-05-05-trading-engine-phase7-design.md`](../specs/2026-05-05-trading-engine-phase7-design.md)
**Predecessor:** [`docs/superpowers/plans/2026-05-05-trading-engine-phase7a.md`](2026-05-05-trading-engine-phase7a.md)

---

## File structure overview

### New files

```
src/main/kotlin/com/qkt/strategy/
    Warmable.kt                              — interface
    WarmupSpec.kt                            — sealed class + windowMs(now) ext

src/main/kotlin/com/qkt/app/
    IndicatorWarmer.kt                       — bar-driven warmup driver
    LiveSession.kt                           — engine-thread runtime
    LiveSessionHandle.kt                     — control + observation surface

src/main/kotlin/com/qkt/marketdata/live/
    LiveTickSource.kt                        — vendor-internal producer interface
    LiveTickFeed.kt                          — bounded queue push→pull TickFeed

src/test/kotlin/com/qkt/marketdata/source/
    InMemoryMarketSource.kt                  — test fixture
    InMemoryMarketSourceTest.kt

src/test/kotlin/com/qkt/strategy/
    WarmupSpecTest.kt

src/test/kotlin/com/qkt/app/
    TradingPipelineWarmupSplitTest.kt
    IndicatorWarmerTest.kt
    BacktestWarmupTest.kt
    LiveSessionTest.kt
    LiveSessionMultiVendorTest.kt

src/test/kotlin/com/qkt/marketdata/live/
    LiveTickFeedTest.kt
```

### Modified files

```
src/main/kotlin/com/qkt/app/TradingPipeline.kt   — split ingest into ingest + ingestForWarmup
src/main/kotlin/com/qkt/app/Backtest.kt          — add warmupSpec parameter; run IndicatorWarmer in fromSource path
```

### Deleted files

None.

---

## Task summary

| # | Group | Title |
|---|---|---|
| 1 | A | Split `TradingPipeline.ingest` into warmup + full ingress |
| 2 | A | Add `WarmupSpec` sealed class + `windowMs` |
| 3 | A | Add `Warmable` interface |
| 4 | B | Add `InMemoryMarketSource` test fixture |
| 5 | B | Add `IndicatorWarmer` |
| 6 | C | Plumb `warmupSpec` into `Backtest.fromSource` |
| 7 | C | Backtest warmup determinism test |
| 8 | D | Add `LiveTickSource` interface |
| 9 | D | Add `LiveTickFeed` (bounded queue, drop-oldest) |
| 10 | E | Add `LiveSessionHandle` interface |
| 11 | E | Add `LiveSession` runtime |
| 12 | E | Multi-vendor fan-in test for `LiveSession` |
| 13 | — | Final verification |

13 tasks. Cumulative test counts after each (assuming Phase 7a merged at ~310):

| After task | New tests | Cumulative |
|---|---|---|
| 1  | +3 | 313 |
| 2  | +6 | 319 |
| 3  | +1 | 320 |
| 4  | +5 | 325 |
| 5  | +6 | 331 |
| 6  | +2 | 333 |
| 7  | +1 | 334 |
| 8  | 0  | 334 |
| 9  | +6 | 340 |
| 10 | 0  | 340 |
| 11 | +5 | 345 |
| 12 | +2 | 347 |
| 13 | 0  | 347 |

Final target: **~347 tests**. Rough number; ±5 expected.

---

## Group A: Pipeline split + warmup primitives

### Task 1: Split `TradingPipeline.ingest` into `ingest` + `ingestForWarmup`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Create: `src/test/kotlin/com/qkt/app/TradingPipelineWarmupSplitTest.kt`

The current `ingest(tick)` calls `engine.onTick(tick)`, which publishes a `TickEvent` on the bus. All subscribers fire — strategies, risk, candle aggregator, price tracker. Warmup needs the price tracker and the candle aggregator to update (so indicators that consume candles see warmup data) but must NOT call strategies or risk. The cleanest split is to publish a separate `WarmupTickEvent` and have only the candle aggregator + price tracker subscribe to it; strategies and risk only subscribe to `TickEvent`.

Anchor on D6 in the spec: "Strategies do not see warmup ticks. Only `IndicatorMap` consumes the warmup stream."

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/app/TradingPipelineWarmupSplitTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.engine.Engine
import com.qkt.events.RiskRejectedEvent
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineWarmupSplitTest {
    private fun newPipeline(strategies: List<Strategy>): TradingPipeline {
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        return TradingPipeline(
            clock = clock,
            ids = ids,
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            bus = bus,
            broker = broker,
            engine = engine,
            strategies = strategies,
            riskEngine = riskEngine,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `ingestForWarmup does not call onTick on strategies`() {
        val seen = mutableListOf<Tick>()
        val strategy =
            object : Strategy {
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                    seen.add(tick)
                }
            }
        val pipeline = newPipeline(listOf(strategy))

        pipeline.ingestForWarmup(Tick("X", Money.of("100"), 1_000L))
        pipeline.ingestForWarmup(Tick("X", Money.of("101"), 2_000L))

        assertThat(seen).isEmpty()
    }

    @Test
    fun `ingestForWarmup updates the candle aggregator`() {
        val candles = mutableListOf<com.qkt.marketdata.Candle>()
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = emptyList(),
                riskEngine = riskEngine,
                candleWindow = TimeWindow.ONE_MINUTE,
                onCandle = { c -> candles.add(c) },
            )

        pipeline.ingestForWarmup(Tick("X", Money.of("100"), 0L))
        pipeline.ingestForWarmup(Tick("X", Money.of("105"), 30_000L))
        pipeline.ingestForWarmup(Tick("X", Money.of("102"), 60_000L))

        assertThat(candles).hasSize(1)
        assertThat(candles[0].open).isEqualByComparingTo(Money.of("100"))
        assertThat(candles[0].high).isEqualByComparingTo(Money.of("105"))
        assertThat(candles[0].close).isEqualByComparingTo(Money.of("105"))
    }

    @Test
    fun `ingest still drives strategies after warmup`() {
        val seen = mutableListOf<Tick>()
        val strategy =
            object : Strategy {
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                    seen.add(tick)
                }
            }
        val pipeline = newPipeline(listOf(strategy))

        pipeline.ingestForWarmup(Tick("X", Money.of("100"), 1_000L))
        pipeline.ingest(Tick("X", Money.of("100"), 2_000L))

        assertThat(seen).hasSize(1)
        assertThat(seen[0].timestamp).isEqualTo(2_000L)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.TradingPipelineWarmupSplitTest"`
Expected: compile failure — `Unresolved reference: ingestForWarmup`.

- [ ] **Step 3: Add `WarmupTickEvent` to `events/Event.kt`**

Modify `src/main/kotlin/com/qkt/events/Event.kt` and append the new event variant alongside the others:

```kotlin
data class WarmupTickEvent(
    val tick: Tick,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event()
```

- [ ] **Step 4: Modify `TradingPipeline` to subscribe the price tracker + candle aggregator to both events; strategies + risk subscribe only to `TickEvent`**

Replace the body of `src/main/kotlin/com/qkt/app/TradingPipeline.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequenceGenerator
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.execution.toOrder
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Strategy
import java.math.BigDecimal

class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionTracker,
    val pnl: PnLCalculator,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Strategy>,
    val riskEngine: RiskEngine,
    val candleWindow: TimeWindow? = null,
    val onFilled: (Trade, BigDecimal) -> Unit = { _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
) {
    init {
        if (candleWindow != null) CandleAggregator(bus, candleWindow)

        bus.subscribe<WarmupTickEvent> { e -> priceTracker.onTick(e.tick) }

        strategies.forEach { strategy ->
            bus.subscribe<TickEvent> { e ->
                strategy.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
            }
            bus.subscribe<CandleEvent> { e ->
                strategy.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val order = e.signal.toOrder(ids.next(), clock.now())
            when (val decision = riskEngine.approve(order)) {
                is Decision.Approve -> bus.publish(OrderEvent(order))
                is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
            }
        }
        bus.subscribe<OrderEvent> { e ->
            broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
        }
        bus.subscribe<TradeEvent> { e ->
            val realized = positions.apply(e.trade)
            pnl.recordRealized(realized)
            onFilled(e.trade, realized)
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        engine.onTick(tick)
    }

    fun ingestForWarmup(tick: Tick) {
        bus.publish(WarmupTickEvent(tick))
    }
}
```

Note: `CandleAggregator` only subscribes to `TickEvent` today (Phase 2b). To get candles to update during warmup, we extend its subscription. Modify `src/main/kotlin/com/qkt/candles/CandleAggregator.kt`:

```kotlin
init {
    bus.subscribe<TickEvent> { event -> handle(event.tick) }
    bus.subscribe<WarmupTickEvent> { event -> handle(event.tick) }
}
```

Add the import: `import com.qkt.events.WarmupTickEvent`.

- [ ] **Step 5: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.TradingPipelineWarmupSplitTest"`
Expected: 3 tests PASS.

- [ ] **Step 6: Full check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL, ~313 tests, ktlint clean. Existing pipeline tests unchanged because `ingest` keeps the same behavior.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/events/Event.kt src/main/kotlin/com/qkt/candles/CandleAggregator.kt src/main/kotlin/com/qkt/app/TradingPipeline.kt src/test/kotlin/com/qkt/app/TradingPipelineWarmupSplitTest.kt
git commit -m "feat(app): split TradingPipeline into ingest and ingestForWarmup"
```

---

### Task 2: Add `WarmupSpec` sealed class + `windowMs` extension

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/WarmupSpec.kt`
- Create: `src/test/kotlin/com/qkt/strategy/WarmupSpecTest.kt`

`WarmupSpec` is a sealed class so that `LiveSession` can pattern-match on the variant when delegating to `IndicatorWarmer`. The `windowMs(now)` extension exposes a single comparable scalar — total warmup duration in ms — so multi-strategy aggregation is `maxByOrNull { it.windowMs(now) }` (per spec §7.3).

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/strategy/WarmupSpecTest.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.candles.TimeWindow
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarmupSpecTest {
    private val now = Instant.parse("2026-05-05T15:00:00Z")

    @Test
    fun `None windowMs is zero`() {
        assertThat(WarmupSpec.None.windowMs(now)).isEqualTo(0L)
    }

    @Test
    fun `Bars windowMs is window duration times count`() {
        val spec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30)
        assertThat(spec.windowMs(now)).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `Duration windowMs returns the duration in ms`() {
        val spec = WarmupSpec.Duration(TimeWindow.FIVE_MINUTES, Duration.ofHours(2))
        assertThat(spec.windowMs(now)).isEqualTo(2 * 3_600_000L)
    }

    @Test
    fun `Ticks windowMs returns the duration in ms`() {
        val spec = WarmupSpec.Ticks(Duration.ofMinutes(5))
        assertThat(spec.windowMs(now)).isEqualTo(5 * 60_000L)
    }

    @Test
    fun `Bars requires positive count`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `widest spec wins via maxByOrNull windowMs`() {
        val small = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 10)
        val medium = WarmupSpec.Duration(TimeWindow.ONE_MINUTE, Duration.ofHours(1))
        val large = WarmupSpec.Bars(TimeWindow.ONE_HOUR, count = 5)
        val widest = listOf(small, medium, large).maxByOrNull { it.windowMs(now) }
        assertThat(widest).isEqualTo(large)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.strategy.WarmupSpecTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `WarmupSpec`**

`src/main/kotlin/com/qkt/strategy/WarmupSpec.kt`:

```kotlin
package com.qkt.strategy

import com.qkt.candles.TimeWindow
import java.time.Duration
import java.time.Instant

sealed class WarmupSpec {
    object None : WarmupSpec()

    data class Bars(
        val window: TimeWindow,
        val count: Int,
    ) : WarmupSpec() {
        init {
            require(count > 0) { "WarmupSpec.Bars count must be > 0: $count" }
        }
    }

    data class Duration(
        val window: TimeWindow,
        val duration: java.time.Duration,
    ) : WarmupSpec() {
        init {
            require(!duration.isZero && !duration.isNegative) {
                "WarmupSpec.Duration must be positive: $duration"
            }
        }
    }

    data class Ticks(val duration: java.time.Duration) : WarmupSpec() {
        init {
            require(!duration.isZero && !duration.isNegative) {
                "WarmupSpec.Ticks duration must be positive: $duration"
            }
        }
    }
}

fun WarmupSpec.windowMs(now: Instant): Long =
    when (this) {
        is WarmupSpec.None -> 0L
        is WarmupSpec.Bars -> window.durationMs * count
        is WarmupSpec.Duration -> duration.toMillis()
        is WarmupSpec.Ticks -> duration.toMillis()
    }
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.strategy.WarmupSpecTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/WarmupSpec.kt src/test/kotlin/com/qkt/strategy/WarmupSpecTest.kt
git commit -m "feat(strategy): add WarmupSpec sealed class with windowMs"
```

---

### Task 3: Add `Warmable` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/strategy/Warmable.kt`

`Warmable` is a single-property mixin. Strategies that need warmup implement it; `LiveSession` filters strategies by type (`filterIsInstance<Warmable>()`) and reduces to the widest spec.

- [ ] **Step 1: Implement**

`src/main/kotlin/com/qkt/strategy/Warmable.kt`:

```kotlin
package com.qkt.strategy

interface Warmable {
    val warmup: WarmupSpec
}
```

- [ ] **Step 2: Tiny sanity test (verifies the interface compiles and a strategy can implement both)**

Append to `src/test/kotlin/com/qkt/strategy/WarmupSpecTest.kt`:

```kotlin
    @Test
    fun `strategy can implement Strategy and Warmable`() {
        val s =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 10)

                override fun onTick(
                    tick: com.qkt.marketdata.Tick,
                    emit: (Signal) -> Unit,
                ) {}
            }
        assertThat(s.warmup).isEqualTo(WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 10))
    }
```

- [ ] **Step 3: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/strategy/Warmable.kt src/test/kotlin/com/qkt/strategy/WarmupSpecTest.kt
git commit -m "feat(strategy): add Warmable mixin for indicator warmup"
```

---

## Group B: Test fixture + warmer

### Task 4: Add `InMemoryMarketSource` test fixture

**Files:**
- Create: `src/test/kotlin/com/qkt/marketdata/source/InMemoryMarketSource.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/source/InMemoryMarketSourceTest.kt`

A test-only `MarketSource` we use throughout 7b. Holds a queue of pre-loaded ticks per symbol; `liveTicks(symbols)` returns a `TickFeed` that drains them in order. `bars()` returns pre-loaded candles; `ticks()` is unsupported so we never accidentally route a backtest through it.

- [ ] **Step 1: Implement the fixture**

`src/test/kotlin/com/qkt/marketdata/source/InMemoryMarketSource.kt`:

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class InMemoryMarketSource(
    override val name: String = "InMemory",
) : MarketSource {
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    private val liveTicks: MutableMap<String, MutableList<Tick>> = mutableMapOf()
    private val bars: MutableMap<Pair<String, TimeWindow>, List<Candle>> = mutableMapOf()
    private val supportedSymbols: MutableSet<String> = mutableSetOf()

    fun seedLive(symbol: String, ticks: List<Tick>) {
        supportedSymbols.add(symbol)
        liveTicks.getOrPut(symbol) { mutableListOf() }.addAll(ticks)
    }

    fun seedBars(symbol: String, window: TimeWindow, candles: List<Candle>) {
        supportedSymbols.add(symbol)
        bars[symbol to window] = candles
    }

    override fun supports(symbol: String): Boolean = symbol in supportedSymbols

    override fun liveTicks(symbols: List<String>): TickFeed {
        val queues =
            symbols.associateWith {
                ConcurrentLinkedQueue<Tick>().apply {
                    liveTicks[it]?.forEach { tick -> offer(tick) }
                }
            }
        val merged: MutableList<Tick> = symbols.flatMap { queues[it]!!.toList() }.sortedBy { it.timestamp }.toMutableList()
        val closed = AtomicBoolean(false)

        return object : TickFeed {
            override fun next(): Tick? {
                if (closed.get()) return null
                if (merged.isEmpty()) return null
                return merged.removeAt(0)
            }

            override fun close() {
                closed.set(true)
            }
        }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val key = symbol to window
        val all = bars[key] ?: return emptySequence()
        return all
            .asSequence()
            .filter { it.startTime >= range.from.toEpochMilli() && it.endTime <= range.to.toEpochMilli() }
    }
}
```

- [ ] **Step 2: Write the test**

`src/test/kotlin/com/qkt/marketdata/source/InMemoryMarketSourceTest.kt`:

```kotlin
package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InMemoryMarketSourceTest {
    private val day15 = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    @Test
    fun `seedLive ticks are returned in timestamp order`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), day15 + 1_000L)))
        src.seedLive("X", listOf(Tick("X", Money.of("101"), day15 + 2_000L)))

        val feed = src.liveTicks(listOf("X"))
        val out = generateSequence { feed.next() }.toList()

        assertThat(out.map { it.timestamp }).containsExactly(day15 + 1_000L, day15 + 2_000L)
    }

    @Test
    fun `multi symbol live feed merges by timestamp`() {
        val src = InMemoryMarketSource()
        src.seedLive("A", listOf(Tick("A", Money.of("1"), day15 + 1_000L)))
        src.seedLive("B", listOf(Tick("B", Money.of("2"), day15 + 500L)))

        val feed = src.liveTicks(listOf("A", "B"))
        val symbols = generateSequence { feed.next() }.map { it.symbol }.toList()
        assertThat(symbols).containsExactly("B", "A")
    }

    @Test
    fun `bars returns candles inside range`() {
        val src = InMemoryMarketSource()
        val c1 = Candle("X", Money.of("100"), Money.of("105"), Money.of("99"), Money.of("104"), Money.ZERO, day15, day15 + 60_000L)
        val c2 = Candle("X", Money.of("104"), Money.of("110"), Money.of("103"), Money.of("108"), Money.ZERO, day15 + 60_000L, day15 + 120_000L)
        src.seedBars("X", TimeWindow.ONE_MINUTE, listOf(c1, c2))

        val r = TimeRange(Instant.ofEpochMilli(day15), Instant.ofEpochMilli(day15 + 120_000L))
        val out = src.bars("X", TimeWindow.ONE_MINUTE, r).toList()

        assertThat(out).containsExactly(c1, c2)
    }

    @Test
    fun `ticks throws because the fixture does not implement TICKS`() {
        val src = InMemoryMarketSource()
        val r = TimeRange(Instant.ofEpochMilli(day15), Instant.ofEpochMilli(day15 + 60_000L))
        assertThatThrownBy { src.ticks("X", r).toList() }
            .isInstanceOf(UnsupportedDataException::class.java)
    }

    @Test
    fun `supports only seeded symbols`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", emptyList())
        assertThat(src.supports("X")).isTrue()
        assertThat(src.supports("Y")).isFalse()
    }
}
```

- [ ] **Step 3: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/test/kotlin/com/qkt/marketdata/source/InMemoryMarketSource.kt src/test/kotlin/com/qkt/marketdata/source/InMemoryMarketSourceTest.kt
git commit -m "test(marketdata): add InMemoryMarketSource test fixture"
```

---

### Task 5: Add `IndicatorWarmer`

**Files:**
- Create: `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt`
- Create: `src/test/kotlin/com/qkt/app/IndicatorWarmerTest.kt`

`IndicatorWarmer` queries `MarketSource.bars(symbol, window, range)` for each symbol and pushes one synthetic tick per bar through `pipeline.ingestForWarmup(tick)`. Synthetic-tick timestamp is `bar.endTime - 1` (per the brief: just before the bar closes, so the look-ahead-bias guard `tick.timestamp < now` is satisfied trivially). Range is bounded to bars STRICTLY before the current incomplete bar (spec D5 + open-question 5): the upper bound is `window.windowStartFor(now)` (the start of the in-progress bar), so the last included bar is the one ending at that boundary.

For `WarmupSpec.Ticks`: if the source advertises `MarketSourceCapability.TICKS` we'd use `source.ticks(...)` directly; this plan ships only the bar fallback because `TICKS` is rare in live vendors. We log a warning and synthesize from bars at `TimeWindow.ONE_MINUTE` (the granularity tracked in the spec for tick-precision warmup). For `WarmupSpec.None` we return immediately.

- [ ] **Step 1: Write failing test**

`src/test/kotlin/com/qkt/app/IndicatorWarmerTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.engine.Engine
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IndicatorWarmerTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val candleStart = Instant.parse("2024-01-15T14:00:00Z").toEpochMilli()

    private fun newPipeline(strategies: List<Strategy>): TradingPipeline {
        val clock = FixedClock(time = now.toEpochMilli())
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        return TradingPipeline(
            clock = clock,
            ids = ids,
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            bus = bus,
            broker = broker,
            engine = engine,
            strategies = strategies,
            riskEngine = riskEngine,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    private fun candle(close: String, startMs: Long): Candle =
        Candle("X", Money.of(close), Money.of(close), Money.of(close), Money.of(close), Money.of("1"), startMs, startMs + 60_000L)

    @Test
    fun `Bars warmup pushes one synthetic tick per bar through ingestForWarmup`() {
        val source = InMemoryMarketSource()
        val candles =
            (0 until 30).map { i -> candle((100 + i).toString(), candleStart + i * 60_000L) }
        source.seedBars("X", TimeWindow.ONE_MINUTE, candles)

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        val warmer = IndicatorWarmer(source, pipeline)
        warmer.warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30), now)

        assertThat(captured).hasSize(30)
        assertThat(captured.map { it.symbol }).allMatch { it == "X" }
        assertThat(captured.first().price).isEqualByComparingTo(Money.of("100"))
        assertThat(captured.last().price).isEqualByComparingTo(Money.of("129"))
    }

    @Test
    fun `synthetic tick timestamp is bar endTime minus one`() {
        val source = InMemoryMarketSource()
        val candles = listOf(candle("100", candleStart))
        source.seedBars("X", TimeWindow.ONE_MINUTE, candles)

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        val warmer = IndicatorWarmer(source, pipeline)
        warmer.warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1), now)

        assertThat(captured).hasSize(1)
        assertThat(captured[0].timestamp).isEqualTo(candleStart + 60_000L - 1)
    }

    @Test
    fun `None spec is a no-op`() {
        val source = InMemoryMarketSource()
        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline).warmup(listOf("X"), WarmupSpec.None, now)

        assertThat(captured).isEmpty()
    }

    @Test
    fun `strategies do not see warmup ticks`() {
        val source = InMemoryMarketSource()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(candle("100", candleStart), candle("101", candleStart + 60_000L)),
        )

        val seen = mutableListOf<Tick>()
        val strategy =
            object : Strategy {
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                    seen.add(tick)
                }
            }
        val pipeline = newPipeline(listOf(strategy))

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 2), now)

        assertThat(seen).isEmpty()
    }

    @Test
    fun `warmup range upper bound excludes the current incomplete bar`() {
        val source = InMemoryMarketSource()
        val rightBeforeNow = Instant.parse("2024-01-15T14:59:00Z").toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(
                candle("100", rightBeforeNow),
                candle("999", Instant.parse("2024-01-15T15:00:00Z").toEpochMilli()),
            ),
        )

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 2), now)

        assertThat(captured.map { it.price }).noneMatch { it.compareTo(Money.of("999")) == 0 }
    }

    @Test
    fun `Duration spec converts duration to bar count`() {
        val source = InMemoryMarketSource()
        val candles =
            (0 until 60).map { i -> candle((100 + i).toString(), candleStart + i * 60_000L) }
        source.seedBars("X", TimeWindow.ONE_MINUTE, candles)

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        val warmer = IndicatorWarmer(source, pipeline)
        warmer.warmup(
            symbols = listOf("X"),
            spec = WarmupSpec.Duration(TimeWindow.ONE_MINUTE, Duration.ofMinutes(15)),
            now = now,
        )

        assertThat(captured).hasSize(15)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.IndicatorWarmerTest"`
Expected: compile failure — `Unresolved reference: IndicatorWarmer`.

- [ ] **Step 3: Implement `IndicatorWarmer`**

`src/main/kotlin/com/qkt/app/IndicatorWarmer.kt`:

```kotlin
package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.slf4j.LoggerFactory

class IndicatorWarmer(
    private val source: MarketSource,
    private val pipeline: TradingPipeline,
) {
    private val log = LoggerFactory.getLogger(IndicatorWarmer::class.java)

    fun warmup(
        symbols: List<String>,
        spec: WarmupSpec,
        now: Instant,
    ) {
        val resolved = resolveBarSpec(spec) ?: return
        for (symbol in symbols) {
            warmupSymbol(symbol, resolved, now)
        }
    }

    private fun warmupSymbol(
        symbol: String,
        bars: BarSpec,
        now: Instant,
    ) {
        val upperMs = bars.window.windowStartFor(now.toEpochMilli())
        val totalMs = bars.window.durationMs * bars.count
        val lowerMs = upperMs - totalMs
        require(upperMs > lowerMs) {
            "warmup range degenerate: lower=$lowerMs upper=$upperMs symbol=$symbol"
        }
        val range = TimeRange(Instant.ofEpochMilli(lowerMs), Instant.ofEpochMilli(upperMs))

        for (candle in source.bars(symbol, bars.window, range)) {
            val syntheticTs = candle.endTime - 1
            require(syntheticTs < now.toEpochMilli()) {
                "look-ahead bias: warmup tick beyond now=$now, requested to=${Instant.ofEpochMilli(syntheticTs)}; symbol=$symbol"
            }
            val tick =
                Tick(
                    symbol = symbol,
                    price = candle.close,
                    timestamp = syntheticTs,
                    volume = candle.volume,
                )
            pipeline.ingestForWarmup(tick)
        }
    }

    private fun resolveBarSpec(spec: WarmupSpec): BarSpec? =
        when (spec) {
            is WarmupSpec.None -> null
            is WarmupSpec.Bars -> BarSpec(spec.window, spec.count)
            is WarmupSpec.Duration -> {
                val count = (spec.duration.toMillis() / spec.window.durationMs).toInt()
                require(count > 0) {
                    "WarmupSpec.Duration too short for window: duration=${spec.duration} window=${spec.window}"
                }
                BarSpec(spec.window, count)
            }
            is WarmupSpec.Ticks -> {
                if (MarketSourceCapability.TICKS in source.capabilities) {
                    log.warn("WarmupSpec.Ticks honored by tick source not yet wired in 7b; falling back to bars at ONE_MINUTE")
                }
                val window = TimeWindow.ONE_MINUTE
                val count = (spec.duration.toMillis() / window.durationMs).toInt()
                require(count > 0) {
                    "WarmupSpec.Ticks duration too short to derive bar count: duration=${spec.duration}"
                }
                BarSpec(window, count)
            }
        }

    private data class BarSpec(
        val window: TimeWindow,
        val count: Int,
    )
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.IndicatorWarmerTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/app/IndicatorWarmer.kt src/test/kotlin/com/qkt/app/IndicatorWarmerTest.kt
git commit -m "feat(app): add IndicatorWarmer for bar-driven warmup"
```

---

## Group C: Backtest warmup integration

### Task 6: Plumb `warmupSpec` into `Backtest.fromSource`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/Backtest.kt`
- Create: `src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt`

After 7a, `Backtest` carries an optional `MarketSource` (for `SessionContext`) and a `calendar`. We add `warmupSpec: WarmupSpec = WarmupSpec.None`. When non-None and a `MarketSource` is in scope (the `fromSource` factory path), `IndicatorWarmer.warmup(...)` runs before the main feed loop. The `fromStore` legacy path with `WarmupSpec.None` is unchanged — no warmer constructed, behavior bit-identical to today.

The "now" passed to `IndicatorWarmer` is the start of the backtest range (`request.from`) — that's the wall time the strategy thinks it is at warmup, ensuring deterministic behavior.

- [ ] **Step 1: Write failing test**

`src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestWarmupTest {
    private val day14 = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()
    private val day15 = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()

    private fun candle(close: String, startMs: Long): Candle =
        Candle("X", Money.of(close), Money.of(close), Money.of(close), Money.of(close), Money.of("1"), startMs, startMs + 60_000L)

    private class CountingStrategy : Strategy {
        var ticks: Int = 0
        override fun onTick(tick: Tick, emit: (Signal) -> Unit) { ticks++ }
    }

    private fun seedSource(): InMemoryMarketSource {
        val src =
            object : InMemoryMarketSource() {
                override val capabilities: Set<MarketSourceCapability> =
                    setOf(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS, MarketSourceCapability.TICKS)
            }
        val warmupCandles =
            (0 until 30).map { i -> candle((100 + i).toString(), day14 + i * 60_000L) }
        src.seedBars("X", TimeWindow.ONE_MINUTE, warmupCandles)
        // also seed live ticks for the backtest range so SequenceTickFeed has data
        src.seedLive("X", listOf(Tick("X", Money.of("130"), day15 + 1_000L)))
        return src
    }

    @Test
    fun `WarmupSpec None backtest is bit identical to no warmup`() {
        val src = seedSource()
        val request = MarketRequest(symbols = listOf("X"), from = Instant.ofEpochMilli(day15), to = Instant.ofEpochMilli(day15 + 60_000L))
        val a =
            Backtest.fromSource(
                strategies = listOf(CountingStrategy()),
                source = src,
                request = request,
                warmupSpec = WarmupSpec.None,
            ).run()
        val b =
            Backtest.fromSource(
                strategies = listOf(CountingStrategy()),
                source = src,
                request = request,
                warmupSpec = WarmupSpec.None,
            ).run()
        assertThat(a.tradeCount).isEqualTo(b.tradeCount)
        assertThat(a.totalPnL).isEqualByComparingTo(b.totalPnL)
    }

    @Test
    fun `WarmupSpec Bars feeds warmup ticks before main loop`() {
        val src = seedSource()
        val strategy = CountingStrategy()
        val request = MarketRequest(symbols = listOf("X"), from = Instant.ofEpochMilli(day15), to = Instant.ofEpochMilli(day15 + 60_000L))

        Backtest.fromSource(
            strategies = listOf(strategy),
            source = src,
            request = request,
            warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
        ).run()

        // Strategy sees only the live-range ticks (1 tick), never the warmup bars (D6 invariant).
        assertThat(strategy.ticks).isEqualTo(1)
    }
}
```

The test references a subclass that adds `TICKS` to the in-memory source's capabilities so `Backtest.fromSource`'s `require(TICKS in source.capabilities)` (added in 7a Task 9) passes. This is a workaround for the test fixture; the production fixture only advertises `BARS + LIVE_TICKS`.

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.BacktestWarmupTest"`
Expected: compile failure — `warmupSpec` not a parameter of `fromSource`.

- [ ] **Step 3: Modify `Backtest.kt`**

Locate the `fromSource` factory in `src/main/kotlin/com/qkt/app/Backtest.kt` (added in 7a Task 9). Add `warmupSpec: WarmupSpec = WarmupSpec.None` to its signature, propagate it to the `Backtest` constructor, then run the warmer at the start of `run()` when the source is non-null and the spec is non-None.

Smallest precise diff:

```kotlin
// At the imports, add:
import com.qkt.strategy.WarmupSpec

// Add field to the primary constructor (immediately after `private val source: MarketSource? = null`):
class Backtest(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val source: MarketSource? = null,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    private val warmupSpec: WarmupSpec = WarmupSpec.None,
)

// Inside `run()`, AFTER constructing `pipeline` but BEFORE the `feed.use { ... }` loop, add:
if (source != null && warmupSpec !is WarmupSpec.None) {
    val symbols = strategies.flatMap { collectSymbols(it) }.toSet().toList()
    if (symbols.isNotEmpty()) {
        IndicatorWarmer(source, pipeline).warmup(
            symbols = symbols,
            spec = warmupSpec,
            now = Instant.ofEpochMilli(initialTimestamp),
        )
    }
}
```

For symbol discovery, since strategies don't expose their symbol set today, we accept the symbols on the request. Plumb the request's symbols through to `Backtest`'s constructor as `private val symbols: List<String> = emptyList()`. The cleanest way is to keep the existing per-symbol feed construction in `fromSource` and pass `request.symbols` into `Backtest(...)`:

```kotlin
fun fromSource(
    strategies: List<Strategy>,
    rules: List<RiskRule> = emptyList(),
    source: MarketSource,
    request: MarketRequest,
    candleWindow: TimeWindow? = null,
    warmupSpec: WarmupSpec = WarmupSpec.None,
): Backtest {
    require(MarketSourceCapability.TICKS in source.capabilities) {
        "Backtest requires a MarketSource that supports TICKS; ${source.name} has ${source.capabilities}"
    }
    val from = request.from ?: error("Backtest.fromSource requires explicit MarketRequest.from")
    val to = request.to ?: error("Backtest.fromSource requires explicit MarketRequest.to")
    val range = TimeRange(from, to)
    val perSymbolFeeds: List<TickFeed> =
        request.symbols.map { sym -> SequenceTickFeed(source.ticks(sym, range)) }
    val feed: TickFeed = if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
    return Backtest(
        strategies = strategies,
        rules = rules,
        feed = feed,
        candleWindow = candleWindow,
        initialTimestamp = from.toEpochMilli(),
        source = source,
        warmupSpec = warmupSpec,
        symbols = request.symbols,
    )
}
```

Add `private val symbols: List<String> = emptyList()` to `Backtest`'s primary constructor and reference it in the warmup block instead of `collectSymbols(it)`:

```kotlin
if (source != null && warmupSpec !is WarmupSpec.None && symbols.isNotEmpty()) {
    IndicatorWarmer(source, pipeline).warmup(
        symbols = symbols,
        spec = warmupSpec,
        now = Instant.ofEpochMilli(initialTimestamp),
    )
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.BacktestWarmupTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Full check**

Run: `./gradlew ktlintFormat check`
Expected: BUILD SUCCESSFUL. The `fromStore` legacy path keeps its `WarmupSpec.None` default and the behavior is unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/Backtest.kt src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt
git commit -m "feat(app): plumb WarmupSpec into Backtest.fromSource"
```

---

### Task 7: Backtest warmup determinism test

**Files:**
- Modify: `src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt`

Spec acceptance criterion: "backtest with `WarmupSpec.Bars(...)` runs twice against the same `LocalMarketSource` produces bit-identical `BacktestResult`."

- [ ] **Step 1: Append the determinism test**

Append to `BacktestWarmupTest`:

```kotlin
    @Test
    fun `Bars warmup backtest is deterministic across two runs`() {
        val request = MarketRequest(symbols = listOf("X"), from = Instant.ofEpochMilli(day15), to = Instant.ofEpochMilli(day15 + 60_000L))

        fun runOnce(): com.qkt.app.BacktestResult {
            val src = seedSource()
            return Backtest.fromSource(
                strategies = listOf(CountingStrategy()),
                source = src,
                request = request,
                warmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
            ).run()
        }

        val a = runOnce()
        val b = runOnce()
        assertThat(a.tradeCount).isEqualTo(b.tradeCount)
        assertThat(a.totalPnL).isEqualByComparingTo(b.totalPnL)
        assertThat(a.realizedTotal).isEqualByComparingTo(b.realizedTotal)
        assertThat(a.unrealizedTotal).isEqualByComparingTo(b.unrealizedTotal)
        assertThat(a.maxDrawdown).isEqualByComparingTo(b.maxDrawdown)
    }
```

- [ ] **Step 2: Run check + commit**

```bash
./gradlew test --tests "com.qkt.app.BacktestWarmupTest"
./gradlew ktlintFormat check
git add src/test/kotlin/com/qkt/app/BacktestWarmupTest.kt
git commit -m "test(app): add Bars warmup backtest determinism check"
```

---

## Group D: Live tick plumbing

### Task 8: Add `LiveTickSource` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/LiveTickSource.kt`

The vendor-internal producer interface used by `LiveTickFeed` to abstract the actual tick producer. TradingView (Plan 7c), Binance (later), and the in-memory test producer all implement this. The contract is push-style: the producer calls `onTick(tick)` for each tick, `onError(t)` for transient errors, and `onDisconnect()` when the underlying connection drops.

- [ ] **Step 1: Implement**

`src/main/kotlin/com/qkt/marketdata/live/LiveTickSource.kt`:

```kotlin
package com.qkt.marketdata.live

import com.qkt.marketdata.Tick

interface LiveTickSource {
    fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    )

    fun stop()
}
```

- [ ] **Step 2: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/LiveTickSource.kt
git commit -m "feat(marketdata): add LiveTickSource interface"
```

---

### Task 9: Add `LiveTickFeed` (bounded queue, drop-oldest)

**Files:**
- Create: `src/main/kotlin/com/qkt/marketdata/live/LiveTickFeed.kt`
- Create: `src/test/kotlin/com/qkt/marketdata/live/LiveTickFeedTest.kt`

`LiveTickFeed` adapts a push-style `LiveTickSource` to qkt's pull-style `TickFeed.next()`. Wraps a bounded `LinkedBlockingQueue<Tick>`. The producer's `onTick(tick)` enqueues with drop-oldest overflow: if the queue is at capacity, we `poll()` once to drop the oldest and then `offer(tick)`. Drops are counted in `droppedTicks: AtomicLong`. `next()` blocks on `poll(timeoutMs)` so `close()` can interrupt cleanly. Errors and disconnects are surfaced via callback constructors so `LiveSession` can publish them on its handle.

Spec D3: drop-oldest is the right policy for live trading — stale ticks are worse than missing ticks.

- [ ] **Step 1: Write failing test**

`src/test/kotlin/com/qkt/marketdata/live/LiveTickFeedTest.kt`:

```kotlin
package com.qkt.marketdata.live

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveTickFeedTest {
    private fun tick(symbol: String, ts: Long, price: String) =
        Tick(symbol, Money.of(price), ts)

    private class FakeSource : LiveTickSource {
        var onTick: ((Tick) -> Unit)? = null
        var onError: ((Throwable) -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null
        var stopped: Boolean = false

        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
        ) {
            this.onTick = onTick
            this.onError = onError
            this.onDisconnect = onDisconnect
        }

        override fun stop() {
            stopped = true
        }
    }

    @Test
    fun `next returns ticks in arrival order`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 100)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onTick!!(tick("X", 3L, "102"))
        feed.close()

        val out = generateSequence { feed.next() }.toList()
        assertThat(out.map { it.timestamp }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `drop oldest when queue is full`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 2)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onTick!!(tick("X", 3L, "102"))
        feed.close()

        val out = generateSequence { feed.next() }.toList()
        assertThat(out.map { it.timestamp }).containsExactly(2L, 3L)
        assertThat(feed.droppedTicks.get()).isEqualTo(1L)
    }

    @Test
    fun `next blocks until tick arrives or close is signaled`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 50)

        val latch = CountDownLatch(1)
        val received = mutableListOf<Tick?>()
        val t =
            Thread {
                received.add(feed.next())
                latch.countDown()
            }
        t.start()

        Thread.sleep(50)
        src.onTick!!(tick("X", 99L, "100"))
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(received.single()).isNotNull()
        assertThat(received.single()!!.timestamp).isEqualTo(99L)

        feed.close()
    }

    @Test
    fun `close causes next to return null after pending drains`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10, pollIntervalMs = 25)
        src.onTick!!(tick("X", 1L, "100"))
        feed.close()

        val first = feed.next()
        val second = feed.next()
        assertThat(first).isNotNull()
        assertThat(second).isNull()
    }

    @Test
    fun `close calls underlying source stop`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 10)
        feed.close()
        assertThat(src.stopped).isTrue()
    }

    @Test
    fun `dropped count is observable through droppedTicks`() {
        val src = FakeSource()
        val feed = LiveTickFeed(src, queueCapacity = 1)
        src.onTick!!(tick("X", 1L, "100"))
        src.onTick!!(tick("X", 2L, "101"))
        src.onTick!!(tick("X", 3L, "102"))

        assertThat(feed.droppedTicks.get()).isEqualTo(2L)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.marketdata.live.LiveTickFeedTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `LiveTickFeed`**

`src/main/kotlin/com/qkt/marketdata/live/LiveTickFeed.kt`:

```kotlin
package com.qkt.marketdata.live

import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

class LiveTickFeed(
    private val source: LiveTickSource,
    queueCapacity: Int = 10_000,
    private val pollIntervalMs: Long = 200L,
) : TickFeed {
    private val log = LoggerFactory.getLogger(LiveTickFeed::class.java)

    private val queue: LinkedBlockingQueue<Tick> = LinkedBlockingQueue(queueCapacity)
    private val closed: AtomicBoolean = AtomicBoolean(false)

    val droppedTicks: AtomicLong = AtomicLong(0)

    init {
        source.start(
            onTick = { tick ->
                if (!queue.offer(tick)) {
                    queue.poll()
                    droppedTicks.incrementAndGet()
                    queue.offer(tick)
                }
            },
            onError = { t -> log.warn("LiveTickFeed source error: ${t.message}", t) },
            onDisconnect = { log.warn("LiveTickFeed source disconnected") },
        )
    }

    override fun next(): Tick? {
        while (!closed.get()) {
            val t = queue.poll(pollIntervalMs, TimeUnit.MILLISECONDS)
            if (t != null) return t
        }
        return queue.poll()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { source.stop() }
        }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.marketdata.live.LiveTickFeedTest"`
Expected: 6 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/live/LiveTickFeed.kt src/test/kotlin/com/qkt/marketdata/live/LiveTickFeedTest.kt
git commit -m "feat(marketdata): add LiveTickFeed with bounded queue and drop-oldest"
```

---

## Group E: LiveSession

### Task 10: Add `LiveSessionHandle` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/app/LiveSessionHandle.kt`

The handle exposes `running`, `stop()`, `droppedTicks`, `awaitTermination(timeout)`, and a synchronous `recentTrades(): List<Trade>` snapshot. Observable `Flow<Trade>` streams are deferred to 7c per the brief — they require coroutines and their first real consumer is the live demo, which lands in 7c.

- [ ] **Step 1: Implement**

`src/main/kotlin/com/qkt/app/LiveSessionHandle.kt`:

```kotlin
package com.qkt.app

import com.qkt.execution.Trade
import java.time.Duration

interface LiveSessionHandle {
    val running: Boolean
    val droppedTicks: Long

    fun stop()

    fun awaitTermination(timeout: Duration): Boolean

    fun recentTrades(): List<Trade>
}
```

- [ ] **Step 2: Run check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/app/LiveSessionHandle.kt
git commit -m "feat(app): add LiveSessionHandle interface"
```

---

### Task 11: Add `LiveSession` runtime

**Files:**
- Create: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Create: `src/test/kotlin/com/qkt/app/LiveSessionTest.kt`

`LiveSession` is the live counterpart to `Backtest`. Same `TradingPipeline` underneath; differs only on `Clock` (defaults to `SystemClock`) and `TickFeed` (driven by `MarketSource.liveTicks(symbols)`). The session runs on a single dedicated engine thread per spec D9.

`start()` flow:
1. Compute effective `WarmupSpec`: `warmupOverride ?: strategies.filterIsInstance<Warmable>().maxByOrNull { it.warmup.windowMs(now) }?.warmup ?: WarmupSpec.None`.
2. Run `IndicatorWarmer.warmup(symbols, effective, now)`.
3. Open `feed = source.liveTicks(symbols)`.
4. Spawn one engine thread that loops `feed.next()` until interrupted; for each tick call `pipeline.ingest(tick)`.
5. Return a `LiveSessionHandle` that exposes status, stops the thread, drains gracefully, and reports `droppedTicks` (delegating to the feed if it's a `LiveTickFeed`).

The pipeline runs synchronously on the engine thread — only `feed.next()` blocks (on the queue, when the source is `LiveTickFeed`).

- [ ] **Step 1: Write failing test**

`src/test/kotlin/com/qkt/app/LiveSessionTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val day14 = Instant.parse("2024-01-14T00:00:00Z").toEpochMilli()

    private fun candle(close: String, startMs: Long): com.qkt.marketdata.Candle =
        com.qkt.marketdata.Candle("X", Money.of(close), Money.of(close), Money.of(close), Money.of(close), Money.of("1"), startMs, startMs + 60_000L)

    private class CapturingStrategy : Strategy {
        val seen: MutableList<Tick> = mutableListOf()

        override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
            seen.add(tick)
        }
    }

    @Test
    fun `start drives strategies with live ticks`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val strategy = CapturingStrategy()
        val clock = FixedClock(time = now.toEpochMilli())
        val session =
            LiveSession(
                strategies = listOf(strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = clock,
                calendar = TradingCalendar.crypto(),
            )

        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(strategy.seen.map { it.price.toPlainString() }).containsExactly("100.00000", "101.00000")
    }

    @Test
    fun `running becomes false after stop`() {
        val src = InMemoryMarketSource()
        src.seedLive("X", listOf(Tick("X", Money.of("100"), now.toEpochMilli())))
        val session =
            LiveSession(
                strategies = emptyList(),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            )

        val handle = session.start()
        handle.stop()
        assertThat(handle.awaitTermination(Duration.ofSeconds(2))).isTrue()
        assertThat(handle.running).isFalse()
    }

    @Test
    fun `effective warmup spec is widest among Warmable strategies`() {
        val src = InMemoryMarketSource()
        src.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            (0 until 10).map { i -> candle((100 + i).toString(), day14 + i * 60_000L) },
        )
        src.seedLive("X", listOf(Tick("X", Money.of("999"), now.toEpochMilli())))

        val small =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 3)
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {}
            }
        val large =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 10)
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {}
            }

        val seenWarmup = mutableListOf<Tick>()

        val session =
            LiveSession(
                strategies = listOf(small, large),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                onWarmupTick = { t -> seenWarmup.add(t) },
            )

        val handle = session.start()
        handle.awaitTermination(Duration.ofSeconds(2))

        assertThat(seenWarmup).hasSize(10)
    }

    @Test
    fun `warmupOverride beats inferred Warmable specs`() {
        val src = InMemoryMarketSource()
        src.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            (0 until 50).map { i -> candle((100 + i).toString(), day14 + i * 60_000L) },
        )
        src.seedLive("X", listOf(Tick("X", Money.of("999"), now.toEpochMilli())))

        val warm =
            object : Strategy, Warmable {
                override val warmup: WarmupSpec = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 5)
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {}
            }

        val seenWarmup = mutableListOf<Tick>()
        val session =
            LiveSession(
                strategies = listOf(warm),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                warmupOverride = WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30),
                onWarmupTick = { t -> seenWarmup.add(t) },
            )
        session.start().awaitTermination(Duration.ofSeconds(2))

        assertThat(seenWarmup).hasSize(30)
    }

    @Test
    fun `recentTrades returns the trades captured so far`() {
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val strategy =
            object : Strategy {
                override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
                    emit(Signal.Buy("X", Money.of("1")))
                }
            }
        val handle =
            LiveSession(
                strategies = listOf(strategy),
                rules = emptyList(),
                source = src,
                symbols = listOf("X"),
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            ).start()
        handle.awaitTermination(Duration.ofSeconds(2))

        assertThat(handle.recentTrades().size).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests "com.qkt.app.LiveSessionTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `LiveSession`**

`src/main/kotlin/com/qkt/app/LiveSession.kt`:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.strategy.Strategy
import com.qkt.strategy.Warmable
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.windowMs
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class LiveSession(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule> = emptyList(),
    private val source: MarketSource,
    private val symbols: List<String>,
    private val candleWindow: TimeWindow? = null,
    private val clock: Clock = SystemClock(),
    private val calendar: TradingCalendar = TradingCalendar.fxDefault(),
    private val warmupOverride: WarmupSpec? = null,
    private val queueCapacity: Int = 10_000,
    private val onWarmupTick: (Tick) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    fun start(): LiveSessionHandle {
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules, positions)

        val trades: MutableList<Trade> = CopyOnWriteArrayList()

        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = strategies,
                riskEngine = riskEngine,
                candleWindow = candleWindow,
                onFilled = { trade, _ -> trades.add(trade) },
            )

        bus.subscribe<WarmupTickEvent> { e -> onWarmupTick(e.tick) }

        val now = Instant.ofEpochMilli(clock.now())
        val effectiveWarmup =
            warmupOverride
                ?: strategies
                    .filterIsInstance<Warmable>()
                    .maxByOrNull { it.warmup.windowMs(now) }
                    ?.warmup
                ?: WarmupSpec.None
        IndicatorWarmer(source, pipeline).warmup(symbols, effectiveWarmup, now)

        val feed = source.liveTicks(symbols)

        val running = AtomicBoolean(true)
        val terminated = CountDownLatch(1)

        val thread =
            Thread({
                try {
                    while (running.get()) {
                        val tick = feed.next() ?: break
                        pipeline.ingest(tick)
                    }
                } catch (e: InterruptedException) {
                    log.info("LiveSession engine thread interrupted")
                    Thread.currentThread().interrupt()
                } finally {
                    runCatching { feed.close() }
                    running.set(false)
                    terminated.countDown()
                }
            }, "qkt-live-engine")
        thread.isDaemon = true
        thread.start()

        return object : LiveSessionHandle {
            override val running: Boolean get() = running.get()

            override val droppedTicks: Long
                get() = if (feed is LiveTickFeed) feed.droppedTicks.get() else 0L

            override fun stop() {
                running.set(false)
                thread.interrupt()
            }

            override fun awaitTermination(timeout: Duration): Boolean =
                terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

            override fun recentTrades(): List<Trade> = trades.toList()
        }
    }
}
```

- [ ] **Step 4: Confirm GREEN**

Run: `./gradlew test --tests "com.qkt.app.LiveSessionTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Full check + commit**

```bash
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/app/LiveSession.kt src/test/kotlin/com/qkt/app/LiveSessionTest.kt
git commit -m "feat(app): add LiveSession runtime on single engine thread"
```

---

### Task 12: Multi-vendor fan-in test for `LiveSession`

**Files:**
- Create: `src/test/kotlin/com/qkt/app/LiveSessionMultiVendorTest.kt`

Spec acceptance criterion (D11): `LiveSession` accepts a `CompositeMarketSource` and demonstrably routes per-symbol live subscriptions to the right vendor; tick arrival into the engine is in arrival order across vendors.

We don't need a separate fan-in queue at the `LiveSession` layer for this plan — `CompositeMarketSource.liveTicks(symbols)` returns one `TickFeed` per backing source when the route splits, and we drain them. Phase 7a's `CompositeMarketSource.liveTicks(symbols)` throws when symbols span multiple sources (the comment says "planned for Phase 7b"). We fix that here with a tiny merging adapter inside `CompositeMarketSource`. Smallest precise diff.

- [ ] **Step 1: Modify `CompositeMarketSource.liveTicks` to return a fan-in `TickFeed`**

In `src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt`, replace the body of `liveTicks` with:

```kotlin
override fun liveTicks(symbols: List<String>): TickFeed {
    val grouped = symbols.groupBy { sourceFor(it) }
    if (grouped.isEmpty()) {
        throw UnsupportedDataException(
            MarketSourceCapability.LIVE_TICKS,
            "CompositeMarketSource: no symbols supplied",
        )
    }
    if (grouped.size == 1) {
        return grouped.entries.first().key.liveTicks(symbols)
    }
    val perVendor: List<TickFeed> = grouped.map { (vendor, syms) -> vendor.liveTicks(syms) }
    return FanInTickFeed(perVendor)
}
```

Add a small file-private fan-in adapter at the bottom of the same file:

```kotlin
private class FanInTickFeed(
    private val feeds: List<TickFeed>,
) : TickFeed {
    private val cursor: java.util.ArrayDeque<TickFeed> = java.util.ArrayDeque(feeds)

    override fun next(): Tick? {
        while (cursor.isNotEmpty()) {
            val first = cursor.peekFirst()
            val t = first.next()
            if (t != null) {
                cursor.removeFirst()
                cursor.addLast(first)
                return t
            }
            cursor.removeFirst()
        }
        return null
    }

    override fun close() {
        feeds.forEach { runCatching { it.close() } }
    }
}
```

This implementation polls feeds in round-robin: if a feed has a tick we return it and rotate that feed to the back; if it's drained we drop it. For real WS-backed `LiveTickFeed`s this means we drain whoever's queue is non-empty, in round-robin arrival order. For the in-memory fixture it gives a deterministic interleave that the test below relies on.

- [ ] **Step 2: Write the multi-vendor test**

`src/test/kotlin/com/qkt/app/LiveSessionMultiVendorTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionMultiVendorTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    private class CapturingStrategy : Strategy {
        val seen: MutableList<Tick> = mutableListOf()

        override fun onTick(tick: Tick, emit: (Signal) -> Unit) {
            seen.add(tick)
        }
    }

    @Test
    fun `LiveSession routes per-symbol subscriptions to the right vendor`() {
        val tv = InMemoryMarketSource(name = "TV")
        tv.seedLive("OANDA:EURUSD", listOf(Tick("OANDA:EURUSD", Money.of("1.10"), now.toEpochMilli())))

        val binance = InMemoryMarketSource(name = "Binance")
        binance.seedLive("BINANCE:BTCUSDT", listOf(Tick("BINANCE:BTCUSDT", Money.of("60000"), now.plus(Duration.ofSeconds(1)).toEpochMilli())))

        val composite =
            CompositeMarketSource(
                routes = listOf(
                    SymbolPattern.prefix("OANDA:") to tv,
                    SymbolPattern.prefix("BINANCE:") to binance,
                ),
                fallback = tv,
            )

        val strategy = CapturingStrategy()
        LiveSession(
            strategies = listOf(strategy),
            rules = emptyList(),
            source = composite,
            symbols = listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = FixedClock(time = now.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
        ).start().awaitTermination(Duration.ofSeconds(2))

        assertThat(strategy.seen.map { it.symbol }).containsExactlyInAnyOrder("OANDA:EURUSD", "BINANCE:BTCUSDT")
    }

    @Test
    fun `multi vendor fan in returns ticks in arrival order across vendors`() {
        val tv = InMemoryMarketSource(name = "TV")
        tv.seedLive(
            "OANDA:EURUSD",
            listOf(
                Tick("OANDA:EURUSD", Money.of("1.10"), now.toEpochMilli()),
                Tick("OANDA:EURUSD", Money.of("1.11"), now.plus(Duration.ofSeconds(2)).toEpochMilli()),
            ),
        )

        val binance = InMemoryMarketSource(name = "Binance")
        binance.seedLive(
            "BINANCE:BTCUSDT",
            listOf(
                Tick("BINANCE:BTCUSDT", Money.of("60000"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
                Tick("BINANCE:BTCUSDT", Money.of("60500"), now.plus(Duration.ofSeconds(3)).toEpochMilli()),
            ),
        )

        val composite =
            CompositeMarketSource(
                routes = listOf(
                    SymbolPattern.prefix("OANDA:") to tv,
                    SymbolPattern.prefix("BINANCE:") to binance,
                ),
                fallback = tv,
            )

        val strategy = CapturingStrategy()
        LiveSession(
            strategies = listOf(strategy),
            rules = emptyList(),
            source = composite,
            symbols = listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = FixedClock(time = now.toEpochMilli()),
            calendar = TradingCalendar.crypto(),
        ).start().awaitTermination(Duration.ofSeconds(2))

        // Round-robin fan-in alternates vendors when both have data ready, then drains the leftover.
        // The exact interleave is OANDA, BINANCE, OANDA, BINANCE because both queues are non-empty
        // at start and the round-robin is deterministic for the in-memory fixture.
        assertThat(strategy.seen.map { it.symbol }).containsExactly(
            "OANDA:EURUSD",
            "BINANCE:BTCUSDT",
            "OANDA:EURUSD",
            "BINANCE:BTCUSDT",
        )
    }
}
```

- [ ] **Step 3: Run check + commit**

```bash
./gradlew test --tests "com.qkt.app.LiveSessionMultiVendorTest"
./gradlew ktlintFormat check
git add src/main/kotlin/com/qkt/marketdata/source/CompositeMarketSource.kt src/test/kotlin/com/qkt/app/LiveSessionMultiVendorTest.kt
git commit -m "feat(marketdata): support multi-vendor fan-in in CompositeMarketSource liveTicks"
```

---

## Task 13: Final verification

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. ~347 tests pass.

- [ ] **Step 2: Run the demo**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: 10. The Phase 6 demo path is unaffected by the live runtime.

- [ ] **Step 3: Verify Phase 7a tests still pass alongside the new ones**

Run:
```bash
./gradlew test --tests "com.qkt.marketdata.source.*"
./gradlew test --tests "com.qkt.indicators.range.*"
./gradlew test --tests "com.qkt.app.*"
./gradlew test --tests "com.qkt.strategy.*"
./gradlew test --tests "com.qkt.marketdata.live.*"
```
Expected: all green.

- [ ] **Step 4: Verify acceptance criteria for Phase 7b**

- [ ] `TradingPipeline.ingestForWarmup(tick)` exists and only updates indicators + price tracker + candle aggregator (not strategies, not risk).
- [ ] `WarmupSpec` sealed class ships with `None`, `Bars`, `Duration`, `Ticks` variants. `windowMs(now)` extension returns total warmup duration in ms.
- [ ] `Warmable` interface ships; strategies optionally implement it.
- [ ] `IndicatorWarmer(source, pipeline).warmup(symbols, spec, now)` queries `MarketSource.bars(...)` and pushes synthetic ticks at `bar.endTime - 1` through `pipeline.ingestForWarmup(...)`.
- [ ] Warmup range upper bound excludes the in-progress bar (`window.windowStartFor(now)`).
- [ ] `WarmupSpec.Ticks` falls back to bar-driven warmup with a logged warning.
- [ ] `Backtest` accepts `warmupSpec: WarmupSpec = WarmupSpec.None`. Non-None warmup runs `IndicatorWarmer` before the main feed loop on the `fromSource` path.
- [ ] `WarmupSpec.None` backtest is bit-identical to Phase 7a (the legacy `fromStore` default unchanged).
- [ ] `WarmupSpec.Bars(...)` backtest is deterministic across two runs.
- [ ] `LiveTickSource` interface ships in `com.qkt.marketdata.live`.
- [ ] `LiveTickFeed` adapts a `LiveTickSource` to `TickFeed`, wrapping a bounded `LinkedBlockingQueue`. Drop-oldest overflow with a `droppedTicks: AtomicLong` counter.
- [ ] `LiveSession` runs the `TradingPipeline` on a dedicated engine thread; computes effective warmup spec; drives ticks via `source.liveTicks(symbols)`.
- [ ] `LiveSessionHandle` exposes `running`, `stop()`, `droppedTicks`, `awaitTermination(timeout)`, `recentTrades()`.
- [ ] `CompositeMarketSource.liveTicks(symbols)` fans in across multiple vendors when symbols route to different sources; ticks arrive in arrival order across vendors.
- [ ] `InMemoryMarketSource` test fixture exists under `src/test/kotlin/com/qkt/marketdata/source/`.
- [ ] All new tests pass; total ~347 tests.

- [ ] **Step 5: No commit (verification only)**

When all boxes checked, Plan 7b is done. Plan 7c (TradingView, sample strategies, live demo, phase changelog) follows.

---

## Spec ambiguities encountered

1. **Where does `WarmupTickEvent` live?** The spec describes `pipeline.ingestForWarmup(tick)` but doesn't name the underlying event variant. We added a `WarmupTickEvent` peer to `TickEvent` so the candle aggregator and price tracker can subscribe (preserving the bus-based plumbing) without strategies hearing it. The alternative — bypass the bus entirely and directly call `priceTracker.onTick`/`candleAggregator.handle` — would couple `TradingPipeline` to subcomponents in ways the rest of the engine doesn't. The bus-based approach matches the existing pattern.

2. **Symbol discovery for `Backtest` warmup.** Strategies don't expose their symbol set, so we cannot derive warmup symbols from them alone. We pass `request.symbols` through to `Backtest` via the `fromSource` factory and use those — the backtest is by definition over the request's symbols. Open question for 7c/8: if strategies need to warm up on auxiliary symbols not in the request (e.g. an SPY-following strategy on AAPL needs SPY warmup), we'll add a per-strategy `warmupSymbols` hook. Out of scope for 7b.

3. **Round-robin vs strict arrival fan-in.** Spec D11 says "ticks arrive in arrival order across vendors, not timestamp order." For the WS-backed case, "arrival order" is naturally enforced by the bounded queue inside each `LiveTickFeed`. For the multi-vendor `CompositeMarketSource`, our 7b implementation drains feeds round-robin per `next()` call, which approximates arrival order when feeds produce at similar rates. A more sophisticated implementation would maintain a single shared fan-in queue at the `CompositeMarketSource` layer with each vendor's reader thread enqueuing into it. We deferred that because (a) round-robin is correct under the in-memory fixture used by 7b's tests, and (b) `LiveTickFeed`'s queue already provides per-vendor arrival ordering. If 7c's TV runs reveal starvation under bursty vendors we'll revisit.

4. **`pollIntervalMs` default for `LiveTickFeed`.** Spec doesn't pin a number. We chose 200 ms as a balance between shutdown latency and CPU wakeups; configurable for tests (we use 25–50 ms in tests to keep them fast).

5. **`onWarmupTick` callback on `LiveSession`.** Not mandated by the spec. We added it strictly for tests so they can observe warmup ticks without subscribing to the bus directly. Production uses ignore the default no-op. If this turns out to be load-bearing we'll make it part of the `LiveSessionHandle` surface.
