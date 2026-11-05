package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1638: the second of two closes of one leg filled at the venue, found no leg to close and was logged
 * as "nothing booked", while the venue now held a short no strategy owned. A close fill naming a leg the
 * book no longer holds now raises the operator alert; a ticket-only venue-sweep close does not.
 */
class TradingPipelineOrphanCloseTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
    private val strategyPositions = StrategyPositionTracker()
    private val alerts = mutableListOf<String>()
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    init {
        val prices = MarketPriceTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = MonotonicSequenceGenerator(),
            priceTracker = prices,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnl,
            bus = bus,
            broker = broker,
            engine = Engine(bus, prices),
            strategies = listOf("book:msft" to idle),
            riskEngine = RiskEngine(rules = emptyList(), positions = positions),
            riskState = RiskState(pnl, strategyPnl, clock, bus),
            mode = Mode.LIVE,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
            positionMode = { PositionAccountingMode.NETTING },
            onProtectionFailure = { _, message -> alerts += message },
        )
    }

    private fun closeOf(
        id: String,
        legId: String?,
    ) = OrderRequest.Market(
        id,
        "MSFT",
        Side.SELL,
        BigDecimal.ONE,
        TimeInForce.GTC,
        0L,
        strategyId = "book:msft",
        closesTicket = "ecda150c",
        closesLegId = legId,
    )

    // The venue reports the execution under the strategy that sent it.
    private fun fill(close: OrderRequest) =
        bus.publish(
            BrokerEvent.OrderFilled(
                close.id,
                close.id,
                "MSFT",
                Side.SELL,
                BigDecimal("539.86"),
                BigDecimal.ONE,
                "book:msft",
            ),
        )

    @Test
    fun `a close fill naming a leg the book does not hold raises the operator alert`() {
        val close = closeOf("ORD-soak_o09_book:msft-1", "book:msft-primary-1")
        bus.publish(OrderEvent(close))
        fill(close)

        assertThat(alerts)
            .singleElement()
            .asString()
            .contains("UNBOOKED CLOSE", "ORD-soak_o09_book:msft-1", "MSFT", "no strategy owns")
    }

    @Test
    fun `a ticket-only close of a position the ledger never held raises nothing`() {
        val close = closeOf("sweep-1", legId = null)
        bus.publish(OrderEvent(close))
        fill(close)

        assertThat(alerts).isEmpty()
    }
}
