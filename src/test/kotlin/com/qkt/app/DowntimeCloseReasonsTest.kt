package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerDeal
import com.qkt.broker.CloseReason
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PaperBroker
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.persistence.PersistedLeg
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** How a leg the venue closed during downtime is booked, by what the venue's closing deals say. */
class DowntimeCloseReasonsTest {
    private val clock = FixedClock(time = 50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val strategyPositions = StrategyPositionTracker()
    private val prices = MarketPriceTracker()
    private val strategyPnl = StrategyPnL(strategyPositions, prices)

    private class DealsBroker(
        private val deals: List<BrokerDeal>,
    ) : Broker {
        override val name = "venue"
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) {}

        override fun deals(
            from: Long,
            to: Long,
        ): List<BrokerDeal> = deals
    }

    private fun deal(
        reason: CloseReason?,
        price: String = "2000",
        ticket: String = "9001",
    ) = BrokerDeal(
        broker = "VENUE",
        dealTicket = "d-$ticket-${reason ?: "none"}",
        positionTicket = ticket,
        orderTicket = null,
        symbol = "VENUE:XAUUSD",
        side = Side.SELL,
        entry = "OUT",
        qty = BigDecimal.ONE,
        price = BigDecimal(price),
        profit = BigDecimal("-40"),
        commission = BigDecimal("-1"),
        swap = BigDecimal("-0.5"),
        magic = null,
        comment = null,
        ts = 40_000L,
        closeReason = reason,
    )

    private val leg =
        PersistedLeg(
            "leg-1",
            null,
            LegRole.PRIMARY,
            Side.BUY,
            "XAUUSD",
            BigDecimal.ONE,
            BigDecimal("1990"),
            10_000L,
            "9001",
        )

    private fun pipeline(): TradingPipeline {
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
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
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    private fun closingFill(vararg deals: BrokerDeal): BrokerEvent.OrderFilled {
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
        val closes = DowntimeCloses(DealsBroker(deals.toList()), clock)
        closes.onLegRetired("alpha", leg)
        closes.bookInto(pipeline())
        return fills.single()
    }

    @Test
    fun `a venue take profit closes the leg as a take profit`() {
        val fill = closingFill(deal(CloseReason.TAKE_PROFIT))

        assertThat(fill.exitReason).isEqualTo(ExitReason.TAKE_PROFIT)
        assertThat(fill.clientOrderId).isEqualTo("reconcile:leg-1")
        assertThat(fill.side).isEqualTo(Side.SELL)
        assertThat(fill.venueCosts).isEqualByComparingTo("1.5")
    }

    @Test
    fun `a stop out closes the leg as a liquidation`() {
        assertThat(closingFill(deal(CloseReason.STOP_OUT)).exitReason).isEqualTo(ExitReason.LIQUIDATION)
    }

    @Test
    fun `a liquidation closes the leg as a liquidation`() {
        assertThat(closingFill(deal(CloseReason.LIQUIDATION)).exitReason).isEqualTo(ExitReason.LIQUIDATION)
    }

    @Test
    fun `an expiry closes the leg as an expiry`() {
        assertThat(closingFill(deal(CloseReason.EXPIRY)).exitReason).isEqualTo(ExitReason.EXPIRY)
    }

    @Test
    fun `deals that disagree on the reason close the leg as a plain close`() {
        assertThat(closingFill(deal(CloseReason.STOP), deal(CloseReason.TAKE_PROFIT)).exitReason)
            .isEqualTo(ExitReason.CLOSE)
    }

    @Test
    fun `a deal with no reason closes the leg as a plain close`() {
        assertThat(closingFill(deal(null)).exitReason).isEqualTo(ExitReason.CLOSE)
    }

    @Test
    fun `closing deals that carry no price book only the amount the venue realized`() {
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val booked = mutableListOf<FillAccountedEvent>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
        bus.subscribe<FillAccountedEvent> { booked += it }
        val closes = DowntimeCloses(DealsBroker(listOf(deal(CloseReason.STOP, price = "0"))), clock)
        closes.onLegRetired("alpha", leg)

        closes.bookInto(pipeline())

        // No fill can close the leg without a price; profit -40, commission -1 and swap -0.5 are booked as stated.
        assertThat(fills).isEmpty()
        val reconcile = booked.single()
        assertThat(reconcile.orderId).isEqualTo("reconcile:leg-1")
        assertThat(reconcile.strategyId).isEqualTo("alpha")
        assertThat(reconcile.symbol).isEqualTo("XAUUSD")
        assertThat(reconcile.legId).isEqualTo("leg-1")
        assertThat(reconcile.netStrategyAccountRealized).isEqualByComparingTo("-41.5")
    }
}
