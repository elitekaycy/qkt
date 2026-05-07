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
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineWarmupSplitTest {
    private fun newPipeline(
        strategies: List<Pair<String, Strategy>>,
        onCandle: (com.qkt.marketdata.Candle) -> Unit = {},
    ): TradingPipeline {
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = com.qkt.positions.StrategyPositionTracker()
        val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskState = com.qkt.risk.RiskState(pnl, strategyPnL, clock, bus)
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
            onCandle = onCandle,
        )
    }

    @Test
    fun `ingestForWarmup does not call onTick on strategies`() {
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

        pipeline.ingestForWarmup(Tick("X", Money.of("100"), 1_000L))
        pipeline.ingestForWarmup(Tick("X", Money.of("101"), 2_000L))

        assertThat(seen).isEmpty()
    }

    @Test
    fun `ingestForWarmup updates the candle aggregator`() {
        val candles = mutableListOf<com.qkt.marketdata.Candle>()
        val pipeline = newPipeline(strategies = emptyList(), onCandle = { c -> candles.add(c) })

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
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seen.add(tick)
                }
            }
        val pipeline = newPipeline(listOf("test" to strategy))

        pipeline.ingestForWarmup(Tick("X", Money.of("100"), 1_000L))
        pipeline.ingest(Tick("X", Money.of("100"), 2_000L))

        assertThat(seen).hasSize(1)
        assertThat(seen[0].timestamp).isEqualTo(2_000L)
    }
}
