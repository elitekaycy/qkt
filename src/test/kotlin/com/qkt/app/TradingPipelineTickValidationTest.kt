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
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The ingestion validation floor: zero, negative, and crossed quotes are dropped and
 * counted; the last good price holds for PnL marks instead of being poisoned.
 */
class TradingPipelineTickValidationTest {
    private fun pipeline(priceTracker: MarketPriceTracker): TradingPipeline {
        val clock = FixedClock(time = 0L)
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = com.qkt.positions.StrategyPositionTracker()
        val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        return TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = PaperBroker(bus, clock, priceTracker),
            engine = Engine(bus, priceTracker),
            strategies = emptyList(),
            riskEngine = RiskEngine(rules = emptyList(), positions = positions),
            riskState = com.qkt.risk.RiskState(pnl, strategyPnL, clock, bus),
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `malformed ticks are dropped and the last good price holds`() {
        val priceTracker = MarketPriceTracker()
        val p = pipeline(priceTracker)

        p.ingest(Tick("X", Money.of("100"), 1L))
        assertThat(priceTracker.lastPrice("X")).isEqualByComparingTo("100")

        // Zero price, negative price, crossed book, zero bid — all dropped.
        p.ingest(Tick("X", BigDecimal.ZERO, 2L))
        p.ingest(Tick("X", Money.of("-5"), 3L))
        p.ingest(Tick("X", Money.of("100"), 4L, bid = Money.of("101"), ask = Money.of("99")))
        p.ingest(Tick("X", Money.of("100"), 5L, bid = BigDecimal.ZERO, ask = Money.of("101")))

        assertThat(p.malformedTickCount.get()).isEqualTo(4L)
        // The poison never reached the price tracker — marks stay on the last good tick.
        assertThat(priceTracker.lastPrice("X")).isEqualByComparingTo("100")

        // A healthy tick keeps flowing.
        p.ingest(Tick("X", Money.of("102"), 6L))
        assertThat(priceTracker.lastPrice("X")).isEqualByComparingTo("102")
        assertThat(p.malformedTickCount.get()).isEqualTo(4L)
    }
}
