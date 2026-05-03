# Trading Engine Phase 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a strategy-backtest harness that drives the engine deterministically over historical tick data, capturing trades + risk rejections + mark-to-market P&L into a `BacktestResult`. Extract a shared `TradingPipeline` so live (`Main.kt`) and backtest construct the same wiring; differ only in feed source and clock.

**Architecture:** New `TradingPipeline` class in `com.qkt.app` owns all subscriber wiring previously inline in `Main.kt`. Constructor takes all components + observation callbacks; `init` registers every `bus.subscribe<...>`; public `ingest(tick)` calls `engine.onTick(tick)`. Backtest constructs `TradingPipeline` with capture callbacks, registers a mark-to-market drawdown subscriber LAST, runs `for (tick in ticks) { clock.time = tick.timestamp; pipeline.ingest(tick) }`, computes metrics, returns `BacktestResult`. New `HistoricalTickFeed(List<Tick>)` mirrors `MockTickFeed`'s `TickFeed` interface for backtest input.

**Tech Stack:** Kotlin 2.1.0, JDK 21, Gradle 8.14.4, JUnit 5.11.4, AssertJ 3.27.0, SLF4J 2.0.16. No new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase4-design.md`](../specs/2026-05-03-trading-engine-phase4-design.md)

**Working directory:** `/home/dickson/Desktop/personal/qkt` on feature branch `phase4-backtest`. Phase 3b is on `main` with 107 tests; Phase 4 spec is committed.

---

## Task ordering

```
1. HistoricalTickFeed + HistoricalTickFeedTest                       [TDD]
2. TradeRecord + BacktestResult (data classes; no own tests)
3. TradingPipeline + Main.kt restructure                             [refactor; ./gradlew run regression check]
4. Backtest + BacktestTest                                           [TDD, 6 tests]
5. Final verification                                                 [no new files]
```

5 tasks. After each:
- Task 1 done: 110 tests
- Task 2 done: 110 (data classes exercised by Task 4)
- Task 3 done: 110 (Main restructure preserves behavior; no test changes)
- Task 4 done: 116 tests
- Task 5: verify 116

---

## Task 1: HistoricalTickFeed + HistoricalTickFeedTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/marketdata/HistoricalTickFeedTest.kt`
- Create: `src/main/kotlin/com/qkt/marketdata/HistoricalTickFeed.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/qkt/marketdata/HistoricalTickFeedTest.kt`:

```kotlin
package com.qkt.marketdata

import com.qkt.common.Money
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HistoricalTickFeedTest {
    @Test
    fun `next returns ticks in order then null`() {
        val ticks = listOf(
            Tick("XAUUSD", Money.of("100"), 1L),
            Tick("XAUUSD", Money.of("110"), 2L),
            Tick("XAUUSD", Money.of("105"), 3L),
        )
        val feed = HistoricalTickFeed(ticks)

        assertThat(feed.next()?.timestamp).isEqualTo(1L)
        assertThat(feed.next()?.timestamp).isEqualTo(2L)
        assertThat(feed.next()?.timestamp).isEqualTo(3L)
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `empty list returns null on first call`() {
        val feed = HistoricalTickFeed(emptyList())
        assertThat(feed.next()).isNull()
    }

    @Test
    fun `repeated next calls past end keep returning null`() {
        val feed = HistoricalTickFeed(listOf(Tick("XAUUSD", Money.of("100"), 1L)))
        feed.next()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
        assertThat(feed.next()).isNull()
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.marketdata.HistoricalTickFeedTest"`
Expected: compile failure — `Unresolved reference 'HistoricalTickFeed'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement `HistoricalTickFeed.kt`**

`src/main/kotlin/com/qkt/marketdata/HistoricalTickFeed.kt`:

```kotlin
package com.qkt.marketdata

class HistoricalTickFeed(
    private val ticks: List<Tick>,
) : TickFeed {
    private var index = 0

    override fun next(): Tick? = if (index < ticks.size) ticks[index++] else null
}
```

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.marketdata.HistoricalTickFeedTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 110 tests total. ktlint clean (run `ktlintFormat` if needed).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/marketdata/HistoricalTickFeed.kt src/test/kotlin/com/qkt/marketdata/HistoricalTickFeedTest.kt
git commit -m "feat(marketdata): add HistoricalTickFeed for backtest input"
```

---

## Task 2: TradeRecord + BacktestResult data classes

**Files:**
- Create: `src/main/kotlin/com/qkt/app/TradeRecord.kt`
- Create: `src/main/kotlin/com/qkt/app/BacktestResult.kt`

These are data classes — no behavior, no logic. Tests come via `BacktestTest` in Task 4 which constructs `BacktestResult` instances and asserts on their fields.

- [ ] **Step 1: Create `TradeRecord.kt`**

`src/main/kotlin/com/qkt/app/TradeRecord.kt`:

```kotlin
package com.qkt.app

import com.qkt.execution.Trade
import java.math.BigDecimal

data class TradeRecord(
    val trade: Trade,
    val realized: BigDecimal,
)
```

- [ ] **Step 2: Create `BacktestResult.kt`**

`src/main/kotlin/com/qkt/app/BacktestResult.kt`:

```kotlin
package com.qkt.app

import com.qkt.events.RiskRejectedEvent
import com.qkt.positions.Position
import java.math.BigDecimal

data class BacktestResult(
    val trades: List<TradeRecord>,
    val rejections: List<RiskRejectedEvent>,
    val finalPositions: Map<String, Position>,
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    val winRate: BigDecimal,
    val maxDrawdown: BigDecimal,
)
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, 110 tests still pass. ktlint clean (run `ktlintFormat` if needed).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/TradeRecord.kt src/main/kotlin/com/qkt/app/BacktestResult.kt
git commit -m "feat(app): add TradeRecord and BacktestResult data classes"
```

---

## Task 3: TradingPipeline + Main.kt restructure

This task extracts all subscriber wiring from `Main.kt` into a new `TradingPipeline` class, then restructures `Main.kt` to use it. Behavior is preserved — `./gradlew run` produces byte-for-byte identical output (3 FILLED + 7 REJECTED + Done.).

**Files:**
- Create: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify (replace): `src/main/kotlin/com/qkt/app/Main.kt`

- [ ] **Step 1: Create `TradingPipeline.kt`**

`src/main/kotlin/com/qkt/app/TradingPipeline.kt`:

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
}
```

Critical wiring-order notes:
- `CandleAggregator` (if `candleWindow != null`) constructor subscribes to `TickEvent` FIRST. Same as Phase 2b's invariant.
- The `onFilled` callback fires inside the same TradeEvent subscriber that does `positions.apply(e.trade)` and `pnl.recordRealized(realized)`. The realized value flows to the callback alongside the trade. Subsequent observers (the standalone `bus.subscribe<TradeEvent> { e -> onFilled(...) }` line is removed — `onFilled` is called inline in the data-update subscriber).
- Subscriber registration order: aggregator (if any) → strategy ticks/candles → signal-to-order → order-to-broker → tradeEvent (data update + onFilled) → riskRejected → candleEvent.

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. The new file compiles in isolation.

- [ ] **Step 3: Replace `Main.kt`**

Replace `src/main/kotlin/com/qkt/app/Main.kt` with:

```kotlin
package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.SystemClock
import com.qkt.engine.Engine
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.MockTickFeed
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.EveryNthTickBuyStrategy
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val clock = SystemClock()
    val ids = SequentialIdGenerator()
    val sequencer = MonotonicSequenceGenerator()
    val priceTracker = MarketPriceTracker()
    val positions = PositionTracker()
    val pnl = PnLCalculator(positions, priceTracker)
    val bus = EventBus(clock, sequencer)
    val broker = MockBroker(clock, priceTracker)
    val engine = Engine(bus, priceTracker)

    val strategies: List<Strategy> = listOf(
        EveryNthTickBuyStrategy("XAUUSD", n = 10, size = Money.of("1")),
    )
    val rules: List<RiskRule> = listOf(
        MaxPositionSize(symbol = "XAUUSD", maxQty = Money.of("3")),
    )
    val riskEngine = RiskEngine(rules, positions)

    val pipeline = TradingPipeline(
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
        onFilled = { trade, _ ->
            val pos = positions.positionFor(trade.symbol)?.quantity ?: Money.ZERO
            log.info(
                "FILLED: {} {} {} @ {} (position: {}, realized: {}, unrealized: {})",
                trade.side,
                trade.quantity.stripTrailingZeros().toPlainString(),
                trade.symbol,
                trade.price.stripTrailingZeros().toPlainString(),
                pos.stripTrailingZeros().toPlainString(),
                pnl.realizedTotal().setScale(2, Money.ROUNDING),
                pnl.unrealizedTotal().setScale(2, Money.ROUNDING),
            )
        },
        onRejected = { e ->
            val o = e.order
            log.info(
                "REJECTED: {} {} {} ({})",
                o.side,
                o.quantity.stripTrailingZeros().toPlainString(),
                o.symbol,
                e.reason,
            )
        },
        onCandle = { c ->
            log.info(
                "CANDLE: {} O={} H={} L={} C={} V={} [{}, {})",
                c.symbol,
                c.open.stripTrailingZeros().toPlainString(),
                c.high.stripTrailingZeros().toPlainString(),
                c.low.stripTrailingZeros().toPlainString(),
                c.close.stripTrailingZeros().toPlainString(),
                c.volume.stripTrailingZeros().toPlainString(),
                c.startTime,
                c.endTime,
            )
        },
    )

    val feed = MockTickFeed(
        symbol = "XAUUSD",
        startPrice = Money.of("2400"),
        count = 100,
        clock = clock,
        tickIntervalMs = 1_000L,
    )
    while (true) {
        val tick = feed.next() ?: break
        pipeline.ingest(tick)
    }
    log.info("Done.")
}
```

Critical: the local `private fun Signal.toOrder(...)` declaration that was in the Phase 3b version is REMOVED. The extension is provided by `com.qkt.execution.toOrder` (imported by `TradingPipeline.kt`).

- [ ] **Step 4: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All 110 existing tests pass. ktlint clean (run `ktlintFormat` if needed).

If ktlint reformats either file, accept its formatting and re-run `./gradlew build`.

- [ ] **Step 5: Run main, verify identical output to Phase 3b**

Run: `./gradlew run 2>&1 | tail -25`
Expected: 3 FILLED + 7 REJECTED + (1-3 CANDLE) + Done. Same prices as Phase 3b (deterministic from seed 42). FILLED lines show `(position, realized, unrealized)` cleanly.

If FILLED count is not 3 or REJECTED is not 7, **report DONE_WITH_CONCERNS** with the actual output.

- [ ] **Step 6: Commit**

```bash
git status
git add src/main/kotlin/com/qkt/app/TradingPipeline.kt src/main/kotlin/com/qkt/app/Main.kt
git commit -m "refactor(app): extract TradingPipeline and use it from Main"
```

---

## Task 4: Backtest + BacktestTest [TDD]

**Files:**
- Create: `src/test/kotlin/com/qkt/app/BacktestTest.kt`
- Create: `src/main/kotlin/com/qkt/app/Backtest.kt`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/com/qkt/app/BacktestTest.kt`:

```kotlin
package com.qkt.app

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BacktestTest {
    private fun tick(symbol: String, price: String, ts: Long) =
        Tick(symbol, Money.of(price), ts)

    private fun buyEveryTickStrategy(symbol: String, size: String) =
        object : Strategy {
            override fun onTick(
                t: Tick,
                emit: (Signal) -> Unit,
            ) {
                emit(Signal.Buy(symbol, Money.of(size)))
            }
        }

    private fun buyThenSellStrategy(symbol: String, size: String) =
        object : Strategy {
            private var step = 0

            override fun onTick(
                t: Tick,
                emit: (Signal) -> Unit,
            ) {
                when (step++) {
                    0 -> emit(Signal.Buy(symbol, Money.of(size)))
                    1 -> emit(Signal.Sell(symbol, Money.of(size)))
                }
            }
        }

    @Test
    fun `empty ticks produces empty result with zero metrics`() {
        val result = Backtest(
            strategies = emptyList(),
            ticks = emptyList(),
        ).run()

        assertThat(result.trades).isEmpty()
        assertThat(result.rejections).isEmpty()
        assertThat(result.finalPositions).isEmpty()
        assertThat(result.realizedTotal).isEqualByComparingTo(Money.ZERO)
        assertThat(result.unrealizedTotal).isEqualByComparingTo(Money.ZERO)
        assertThat(result.totalPnL).isEqualByComparingTo(Money.ZERO)
        assertThat(result.tradeCount).isEqualTo(0)
        assertThat(result.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(result.maxDrawdown).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `single buy produces one trade and zero realized`() {
        val result = Backtest(
            strategies = listOf(buyEveryTickStrategy("XAUUSD", "1")),
            ticks = listOf(tick("XAUUSD", "100", 1L)),
        ).run()

        assertThat(result.trades).hasSize(1)
        assertThat(result.trades[0].realized).isEqualByComparingTo(Money.ZERO)
        assertThat(result.tradeCount).isEqualTo(1)
        assertThat(result.winRate).isEqualByComparingTo(Money.ZERO)
        assertThat(result.finalPositions["XAUUSD"]?.quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `buy then sell produces realized PnL and increments win rate`() {
        val result = Backtest(
            strategies = listOf(buyThenSellStrategy("XAUUSD", "1")),
            ticks = listOf(
                tick("XAUUSD", "100", 1L),
                tick("XAUUSD", "110", 2L),
            ),
        ).run()

        assertThat(result.trades).hasSize(2)
        assertThat(result.trades[0].realized).isEqualByComparingTo(Money.ZERO)
        assertThat(result.trades[1].realized).isEqualByComparingTo(Money.of("10"))
        assertThat(result.tradeCount).isEqualTo(2)
        assertThat(result.winRate).isEqualByComparingTo(Money.of("1"))
        assertThat(result.realizedTotal).isEqualByComparingTo(Money.of("10"))
        assertThat(result.finalPositions).isEmpty()
    }

    @Test
    fun `risk-rejected order appears in rejections, not trades`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("0.5")))
        val result = Backtest(
            strategies = listOf(buyEveryTickStrategy("XAUUSD", "1")),
            rules = rules,
            ticks = listOf(tick("XAUUSD", "100", 1L)),
        ).run()

        assertThat(result.trades).isEmpty()
        assertThat(result.rejections).hasSize(1)
        assertThat(result.rejections[0].order.symbol).isEqualTo("XAUUSD")
        assertThat(result.rejections[0].reason).contains("MaxPositionSize")
        assertThat(result.tradeCount).isEqualTo(0)
    }

    @Test
    fun `mark-to-market drawdown captures unrealized swings on open positions`() {
        val result = Backtest(
            strategies = listOf(
                object : Strategy {
                    private var done = false

                    override fun onTick(
                        t: Tick,
                        emit: (Signal) -> Unit,
                    ) {
                        if (!done) {
                            emit(Signal.Buy("XAUUSD", Money.of("1")))
                            done = true
                        }
                    }
                },
            ),
            ticks = listOf(
                tick("XAUUSD", "100", 1L),
                tick("XAUUSD", "90", 2L),
                tick("XAUUSD", "110", 3L),
            ),
        ).run()

        // Buy at 100; equity sequence:
        //   tick 1: position opens at 100, unrealized = 0
        //   tick 2: price = 90, unrealized = (90-100)*1 = -10
        //   tick 3: price = 110, unrealized = (110-100)*1 = 10
        // peakEquity progression: 0 → 0 → 10
        // drawdown progression:    0 → 10 → 0
        // maxDrawdown = 10
        assertThat(result.maxDrawdown).isEqualByComparingTo(Money.of("10"))
    }

    @Test
    fun `max position size rule rejects subsequent buys after limit reached`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("2")))
        val result = Backtest(
            strategies = listOf(buyEveryTickStrategy("XAUUSD", "1")),
            rules = rules,
            ticks = listOf(
                tick("XAUUSD", "100", 1L),
                tick("XAUUSD", "100", 2L),
                tick("XAUUSD", "100", 3L),
                tick("XAUUSD", "100", 4L),
                tick("XAUUSD", "100", 5L),
            ),
        ).run()

        // 5 buy attempts; cap = 2. First 2 fill, last 3 reject.
        assertThat(result.trades).hasSize(2)
        assertThat(result.rejections).hasSize(3)
        assertThat(result.finalPositions["XAUUSD"]?.quantity).isEqualByComparingTo(Money.of("2"))
    }
}
```

- [ ] **Step 2: Run test, confirm FAIL (RED)**

Run: `./gradlew test --tests "com.qkt.app.BacktestTest"`
Expected: compile failure — `Unresolved reference 'Backtest'`. Build fails at `:compileTestKotlin`.

- [ ] **Step 3: Implement `Backtest.kt`**

`src/main/kotlin/com/qkt/app/Backtest.kt`:

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
import com.qkt.events.TickEvent
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.strategy.Strategy
import java.math.BigDecimal

class Backtest(
    private val strategies: List<Strategy>,
    private val rules: List<RiskRule> = emptyList(),
    private val ticks: List<Tick>,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
) {
    fun run(): BacktestResult {
        val clock = FixedClock(time = initialTimestamp)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = MockBroker(clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules, positions)

        val tradeRecords = mutableListOf<TradeRecord>()
        val rejections = mutableListOf<RiskRejectedEvent>()
        var peakEquity: BigDecimal = Money.ZERO
        var maxDrawdown: BigDecimal = Money.ZERO

        val pipeline = TradingPipeline(
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
            onFilled = { trade, realized -> tradeRecords.add(TradeRecord(trade, realized)) },
            onRejected = { e -> rejections.add(e) },
            onCandle = {},
        )

        bus.subscribe<TickEvent> {
            val equity = pnl.totalPnL()
            if (equity > peakEquity) peakEquity = equity
            val drawdown = peakEquity.subtract(equity)
            if (drawdown > maxDrawdown) maxDrawdown = drawdown
        }

        val feed = HistoricalTickFeed(ticks)
        while (true) {
            val tick = feed.next() ?: break
            clock.time = tick.timestamp
            pipeline.ingest(tick)
        }

        return BacktestResult(
            trades = tradeRecords.toList(),
            rejections = rejections.toList(),
            finalPositions = positions.allPositions(),
            realizedTotal = pnl.realizedTotal(),
            unrealizedTotal = pnl.unrealizedTotal(),
            totalPnL = pnl.totalPnL(),
            tradeCount = tradeRecords.size,
            winRate = computeWinRate(tradeRecords),
            maxDrawdown = maxDrawdown,
        )
    }

    private fun computeWinRate(records: List<TradeRecord>): BigDecimal {
        val closing = records.filter { it.realized.signum() != 0 }
        if (closing.isEmpty()) return Money.ZERO
        val wins = closing.count { it.realized.signum() > 0 }
        return BigDecimal(wins)
            .divide(BigDecimal(closing.size), Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

Critical implementation notes:
- The drawdown subscriber `bus.subscribe<TickEvent> { ... }` is registered AFTER `TradingPipeline` is constructed. Subscriber registration order ensures TradingPipeline's TickEvent subscribers (aggregator, strategies) fire FIRST, depth-first dispatch completes (signals → orders → trades update positions/pnl), THEN this drawdown subscriber reads the post-tick `pnl.totalPnL()`.
- `pipeline.ingest(tick)` is the public API for advancing the engine; equivalent to `engine.onTick(tick)` but routed through the pipeline's contract for consistency with `Main.kt`.
- `tradeRecords.toList()` and `rejections.toList()` make defensive immutable copies in the result.
- `onCandle = {}` — Phase 4 doesn't capture candles; backtest is about P&L outcomes.

- [ ] **Step 4: Run test, confirm PASS (GREEN)**

Run: `./gradlew test --tests "com.qkt.app.BacktestTest"`
Expected: 6 tests PASS.

If a test fails, **report BLOCKED** with the failing output. The most likely failures:
- The drawdown test failing → check that the `bus.subscribe<TickEvent>` for the drawdown subscriber is registered AFTER `TradingPipeline` (so it runs LAST in registration order).
- The buy-then-sell test failing on `winRate` → check `computeWinRate` filter (`realized.signum() != 0`).

- [ ] **Step 5: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. 116 tests total (110 + 6 new). ktlint clean (run `ktlintFormat` if needed).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/Backtest.kt src/test/kotlin/com/qkt/app/BacktestTest.kt
git commit -m "feat(app): add Backtest harness with mark-to-market drawdown"
```

---

## Task 5: Final verification

**Files:** No new files.

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Compile + ktlintCheck + 116 tests + assemble.

- [ ] **Step 2: Force test re-run, count PASSED**

Run: `./gradlew test --rerun-tasks 2>&1 | grep -cE "PASSED$"`
Expected: `116`.

- [ ] **Step 3: Run main, confirm Phase 3b output preserved**

Run: `./gradlew run 2>&1 | grep -cE "FILLED|REJECTED"`
Expected: 10 (3 FILLED + 7 REJECTED).

Run: `./gradlew run 2>&1 | grep -cE "Done\."`
Expected: 1.

If counts differ from Phase 3b, the `Main.kt` restructure broke behavior. **Report DONE_WITH_CONCERNS** with the actual output.

- [ ] **Step 4: Verify file count and structure**

Run: `find src -name "*.kt" -type f | sort | wc -l`
Expected: 54.

Phase 3b had 47 files (31 production + 16 test). Phase 4 adds:
- Production: `HistoricalTickFeed.kt`, `TradeRecord.kt`, `BacktestResult.kt`, `TradingPipeline.kt`, `Backtest.kt` (+5)
- Test: `HistoricalTickFeedTest.kt`, `BacktestTest.kt` (+2)
- Total: 47 + 7 = 54.

- [ ] **Step 5: Verify line counts**

Run: `wc -l src/main/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest production file under 130 lines. `TradingPipeline.kt` and `Backtest.kt` are likely the biggest.

Run: `wc -l src/test/kotlin/com/qkt/**/*.kt 2>/dev/null | sort -n | tail -5`
Expected: largest test file is `EndToEndTest.kt` at ~360 lines (carry-over from Phase 3b — still over the 200-line cap, still flagged for future cleanup; not blocked by Phase 4).

- [ ] **Step 6: Run precheck**

Run: `./scripts/precheck.sh`
Expected: all 6 steps PASS.

- [ ] **Step 7: Verify acceptance criteria from spec §13**

- [x] `TradingPipeline` exists in `com.qkt.app` with constructor wiring + `ingest(tick)`.
- [x] `HistoricalTickFeed` exists in `com.qkt.marketdata`.
- [x] `Backtest` exists in `com.qkt.app` with `(strategies, rules, ticks, candleWindow?, initialTimestamp).run(): BacktestResult`.
- [x] `BacktestResult` and `TradeRecord` exist in `com.qkt.app`.
- [x] `Main.kt` is restructured to use `TradingPipeline`.
- [x] All 116 tests pass.
- [x] `./gradlew build` green.
- [x] `./gradlew run` produces same output as Phase 3b.
- [x] Determinism: running the same `Backtest(...)` twice produces an identical `BacktestResult`. (Tests assert this implicitly — same setup, same numbers.)
- [x] Mark-to-market drawdown captures unrealized swings (verified by `BacktestTest`).
- [x] Phase 3b acceptance criteria still hold.
- [x] Clean git history.

When all checked, Phase 4 is done.

- [ ] **Step 8: No commit**

Verification only.

---

## Notes for the implementing engineer

- **Run tests after every TDD task** (Tasks 1, 4). Don't batch.
- **Don't use mocks.** Anonymous `object : Strategy { ... }` impls + capture lists.
- **Follow ktlint output.** Run `./gradlew ktlintFormat` if `check` fails on it; stage formatting with the changes.
- **Match Kotlin style exactly.** No semicolons. No `public`. No emojis. No useless comments.
- **Frequent small commits per task.** Each task ends in exactly one commit.
- **No emoji, no AI references** in code or commit messages. Per CLAUDE.md and the project skill.
- **Commit message types:** use `feat`, `refactor`, `test`, `build`, `chore`, `docs`, `style`. Subject only, no body, no footer, no AI references.
- **Task 3 is the most subtle.** The wiring extraction must preserve behavior. The check is `./gradlew run` producing the same FILLED/REJECTED/CANDLE output as Phase 3b. If it differs, the wiring order is wrong (most likely the `onFilled` callback wasn't invoked from inside the data-update subscriber, or `CandleAggregator` was constructed AFTER strategies subscribed).
- **Task 4's drawdown subscriber order is the critical wiring invariant.** Register it AFTER constructing `TradingPipeline`, in `Backtest.run()`. This puts it last among `TickEvent` subscribers, so it observes post-tick equity correctly.
