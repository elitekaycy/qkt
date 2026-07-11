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
import com.qkt.events.TradeEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineOwnershipTest {
    @Test
    fun `unowned venue fills do not create an account position`() {
        val clock = FixedClock(time = 0L)
        val sequencer = MonotonicSequenceGenerator()
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        val bus = EventBus(clock, sequencer)
        val riskState = RiskState(pnl, strategyPnl, clock, bus)
        TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = sequencer,
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
            riskState = riskState,
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
        val trades = mutableListOf<TradeEvent>()
        bus.subscribe<TradeEvent> { trades.add(it) }

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "external-close-42",
                brokerOrderId = "42",
                symbol = "XAUUSD",
                side = Side.SELL,
                price = BigDecimal("2400"),
                quantity = BigDecimal.ONE,
                strategyId = "",
                timestamp = 1_000L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "external-partial-43",
                brokerOrderId = "43",
                symbol = "XAUUSD",
                side = Side.SELL,
                price = BigDecimal("2401"),
                quantity = BigDecimal("0.5"),
                cumulativeFilled = BigDecimal("0.5"),
                strategyId = "",
                timestamp = 2_000L,
            ),
        )

        assertThat(positions.positionFor("XAUUSD")).isNull()
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(riskState.dailyPnLTracker.globalRealizedToday()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(trades).isEmpty()
    }
}
