package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.CostIncurred
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.marketdata.MarketPriceTracker
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

/** A venue cost a broker reports is booked once, as realized loss, outside trade history. */
class CostIncurredBookingTest {
    private val clock = FixedClock(time = 1_000L)
    private val priceTracker = MarketPriceTracker()
    private val strategyPositions = StrategyPositionTracker()
    private val pnl = PnLCalculator(strategyPositions.account, priceTracker)
    private val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    init {
        TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = priceTracker,
            positions = strategyPositions.account,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = PaperBroker(bus, clock, priceTracker),
            engine = Engine(bus, priceTracker),
            strategies = emptyList(),
            riskEngine = RiskEngine(rules = emptyList(), positions = strategyPositions.account),
            riskState = RiskState(pnl, strategyPnL, clock, bus),
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    @Test
    fun `a cost is booked as a realized loss of kind COST`() {
        val accounted = mutableListOf<FillAccountedEvent>()
        bus.subscribe<FillAccountedEvent> { accounted += it }

        bus.publish(cost(BigDecimal("2.5")))

        val event = accounted.single()
        assertThat(event.kind).isEqualTo(FillAccountingKind.COST)
        assertThat(event.strategyId).isEqualTo("s")
        assertThat(event.symbol).isEqualTo("BINANCE_UM:BTCUSDT@front")
        assertThat(event.netStrategyAccountRealized).isEqualByComparingTo("-2.5")
        assertThat(event.orderId).isEqualTo("cost:roll BTCUSDT_240927->BTCUSDT_241227")
        assertThat(strategyPnL.realizedFor("s")).isEqualByComparingTo("-2.5")
        assertThat(pnl.realizedTotal()).isEqualByComparingTo("-2.5")
    }

    @Test
    fun `a negative cost is a credit`() {
        bus.publish(cost(BigDecimal("-0.4")))

        assertThat(strategyPnL.realizedFor("s")).isEqualByComparingTo("0.4")
    }

    private fun cost(amount: BigDecimal) =
        CostIncurred(
            strategyId = "s",
            symbol = "BINANCE_UM:BTCUSDT@front",
            amount = amount,
            reason = "roll BTCUSDT_240927->BTCUSDT_241227",
            referencePrice = BigDecimal("63343.9"),
        )
}
