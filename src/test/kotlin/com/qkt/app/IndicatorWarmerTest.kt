package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorWarmerTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val candleStart = Instant.parse("2024-01-15T14:00:00Z").toEpochMilli()

    private fun newPipeline(strategies: List<Pair<String, Strategy>>): TradingPipeline {
        val clock = FixedClock(time = now.toEpochMilli())
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = com.qkt.positions.StrategyPositionTracker()
        val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val riskState = com.qkt.risk.RiskState(pnl, strategyPnL, clock, bus)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        return TradingPipeline(
            clock = clock,
            ids = ids,
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = broker,
            engine = engine,
            strategies = strategies,
            riskEngine = riskEngine,
            riskState = riskState,
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    private fun candle(
        close: String,
        startMs: Long,
    ): Candle =
        Candle(
            "X",
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of(close),
            Money.of("1"),
            startMs,
            startMs + 60_000L,
        )

    @Test
    fun `Bars warmup pushes four OHLC ticks per bar through ingestForWarmup`() {
        val source = InMemoryMarketSource()
        val warmupStart = Instant.parse("2024-01-15T14:30:00Z").toEpochMilli()
        val candles =
            (0 until 30).map { i -> candle((100 + i).toString(), warmupStart + i * 60_000L) }
        source.seedBars("X", TimeWindow.ONE_MINUTE, candles)

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30), now)

        // Four synthetic ticks (O, L, H, C) per bar.
        assertThat(captured).hasSize(120)
        assertThat(captured.map { it.symbol }).allMatch { it == "X" }
        assertThat(captured.first().price).isEqualByComparingTo(Money.of("100"))
        assertThat(captured.last().price).isEqualByComparingTo(Money.of("129"))
    }

    @Test
    fun `synthetic tick timestamp is bar endTime minus one`() {
        val source = InMemoryMarketSource()
        val barStart = Instant.parse("2024-01-15T14:59:00Z").toEpochMilli()
        source.seedBars("X", TimeWindow.ONE_MINUTE, listOf(candle("100", barStart)))

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1), now)

        // Four OHLC ticks; the close tick sits last, at endTime - 1.
        assertThat(captured).hasSize(4)
        assertThat(captured.last().timestamp).isEqualTo(barStart + 60_000L - 1)
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
        val twoBarsBack = Instant.parse("2024-01-15T14:58:00Z").toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(candle("100", twoBarsBack), candle("101", twoBarsBack + 60_000L)),
        )

        val seen = mutableListOf<Tick>()
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seen.add(tick)
                }
            }
        val pipeline = newPipeline(listOf("test" to strategy))

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

        IndicatorWarmer(source, pipeline)
            .warmup(
                symbols = listOf("X"),
                spec = WarmupSpec.Duration(TimeWindow.ONE_MINUTE, Duration.ofMinutes(15)),
                now = now,
            )

        // 15 bars x 4 OHLC ticks.
        assertThat(captured).hasSize(60)
    }

    @Test
    fun `warmup ticks carry the bar high and low, not just the close`() {
        val source = InMemoryMarketSource()
        val barStart = Instant.parse("2024-01-15T14:59:00Z").toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(
                Candle(
                    "X",
                    Money.of("100"),
                    Money.of("110"),
                    Money.of("90"),
                    Money.of("105"),
                    Money.of("1"),
                    barStart,
                    barStart + 60_000L,
                ),
            ),
        )

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1), now)

        val prices = captured.map { it.price }
        assertThat(prices).anyMatch { it.compareTo(Money.of("110")) == 0 }
        assertThat(prices).anyMatch { it.compareTo(Money.of("90")) == 0 }
    }
}
