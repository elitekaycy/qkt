package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Venue-reported costs on a fill (MT5 deal commission/swap, Bybit execFee) must net
 * out of realized PnL everywhere the engine reasons about money — equity, per-strategy
 * PnL, and the daily-loss halt input. A strategy bleeding costs must not look healthier
 * than it is.
 */
class TradingPipelineVenueCostsTest {
    @Test
    fun `venue costs net out of realized pnl and halt inputs`() {
        val clock = FixedClock(time = 0L)
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = com.qkt.positions.StrategyPositionTracker()
        val strategyPnL = com.qkt.pnl.StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val riskState = com.qkt.risk.RiskState(pnl, strategyPnL, clock, bus)
        TradingPipeline(
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
            riskState = riskState,
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )

        fun fill(
            side: Side,
            price: String,
            costs: String,
        ) = BrokerEvent.OrderFilled(
            clientOrderId = "c-$price",
            brokerOrderId = "b",
            symbol = "X",
            side = side,
            price = BigDecimal(price),
            quantity = BigDecimal.ONE,
            strategyId = "A",
            timestamp = 0L,
            venueCosts = BigDecimal(costs),
        )

        bus.publish(fill(Side.BUY, "100", "0.5"))
        bus.publish(fill(Side.SELL, "110", "2.0"))

        // Gross round trip +10, venue charged 2.5 across both fills -> net 7.5.
        assertThat(pnl.realizedTotal()).isEqualByComparingTo("7.5")
        assertThat(strategyPnL.realizedFor("A")).isEqualByComparingTo("7.5")
        assertThat(riskState.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("7.5")

        // Halt timing is therefore cost-aware: a losing round trip plus costs trips
        // MaxDailyLoss at the NET number, not the healthier-looking gross one.
        bus.publish(fill(Side.BUY, "100", "0.5"))
        bus.publish(fill(Side.SELL, "95", "2.0")) // gross -5, net -7.5 -> day total 0
        bus.publish(fill(Side.BUY, "100", "0.5"))
        bus.publish(fill(Side.SELL, "95", "2.0")) // day total -7.5
        assertThat(riskState.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo("-7.5")
        assertThat(
            com.qkt.risk.rules
                .MaxDailyLoss(java.math.BigDecimal("7"))
                .evaluate(riskState),
        ).isInstanceOf(com.qkt.risk.HaltDecision.Halt::class.java)
        assertThat(
            com.qkt.risk.rules
                .MaxDailyLoss(java.math.BigDecimal("8"))
                .evaluate(riskState),
        ).isEqualTo(com.qkt.risk.HaltDecision.Continue)
    }
}
