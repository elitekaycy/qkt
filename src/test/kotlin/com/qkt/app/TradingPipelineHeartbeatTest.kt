package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The live 1 Hz heartbeat closes a quiet symbol's bar once its window has ended, with no grace by default. */
class TradingPipelineHeartbeatTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private fun pipeline(): TradingPipeline {
        val prices = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        return TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = prices,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnl,
            bus = bus,
            broker = PaperBroker(bus, clock, prices),
            engine = Engine(bus, prices),
            strategies = emptyList(),
            riskEngine = RiskEngine(rules = emptyList(), positions = positions),
            riskState = RiskState(pnl, strategyPnl, clock, bus),
            mode = Mode.LIVE,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `a quiet symbol's bar closes on the first heartbeat at its window end`() {
        val candles = mutableListOf<CandleEvent>()
        bus.subscribe<CandleEvent> { candles += it }
        val pipeline = pipeline()
        pipeline.ingest(Tick("BTCUSDT", BigDecimal("100"), 10_000L))
        pipeline.ingest(Tick("BTCUSDT", BigDecimal("101"), 20_000L))

        pipeline.scheduleHeartbeat(59_999L)
        assertThat(candles).isEmpty()

        pipeline.scheduleHeartbeat(60_000L)
        val bar = candles.single().candle
        assertThat(bar.symbol).isEqualTo("BTCUSDT")
        assertThat(bar.open).isEqualByComparingTo("100")
        assertThat(bar.close).isEqualByComparingTo("101")
        assertThat(bar.startTime).isEqualTo(0L)
    }
}
