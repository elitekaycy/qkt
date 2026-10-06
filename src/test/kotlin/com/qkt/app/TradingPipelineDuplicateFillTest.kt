package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
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
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The same venue execution heard twice (a stream replay after a reconnect, a recovery re-report) is
 * booked once: the order already holds it, so the position ledger must not book it again.
 */
class TradingPipelineDuplicateFillTest {
    private val symbol = "BTCUSDT"
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.STOP))
    private val strategyPositions = StrategyPositionTracker()
    private lateinit var pipeline: TradingPipeline

    /** Sends [entry] on its first tick and reads `OPEN_ORDERS` on every tick, as the DSL does. */
    private inner class OneEntry(
        private val entry: OrderRequest,
    ) : DslCompiledStrategy {
        val openOrders = mutableListOf<Int>()
        override val pendingStacks = PendingStacks()
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
        override val declaredStreams: Map<String, HubKey> = emptyMap()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (broker.submits.isEmpty()) emit(Signal.Submit(entry))
            openOrders += ctx.openOrders.entryCountFor(symbol)
        }
    }

    private fun run(strategy: OneEntry) {
        val prices = MarketPriceTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, prices)
        val strategyPnl = StrategyPnL(strategyPositions, prices)
        pipeline =
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
                strategies = listOf("alpha" to strategy),
                riskEngine = RiskEngine(rules = emptyList(), positions = positions),
                riskState = RiskState(pnl, strategyPnl, clock, bus),
                mode = Mode.LIVE,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
            )
    }

    private fun tick(at: Long) = bus.publish(TickEvent(Tick(symbol, BigDecimal("60000"), at)))

    private fun entry() =
        OrderRequest.Stop("s-1", symbol, Side.BUY, BigDecimal("50"), BigDecimal("60005"), TimeInForce.GTC, 0L)

    private fun filled(qty: String) =
        BrokerEvent.OrderFilled("s-1", "v-1", symbol, Side.BUY, BigDecimal("60010"), BigDecimal(qty), "alpha", 2L)

    private fun partial(
        qty: String,
        cumulative: String,
    ) = BrokerEvent.OrderPartiallyFilled(
        "s-1",
        "v-1",
        symbol,
        Side.BUY,
        BigDecimal("60010"),
        BigDecimal(qty),
        BigDecimal(cumulative),
        "alpha",
        timestamp = 2L,
    )

    @Test
    fun `a full fill heard twice is booked once`() {
        run(OneEntry(entry()))
        tick(1L)

        bus.publish(filled("50"))
        bus.publish(filled("50"))

        assertThat(pipeline.orderManager.getOrder("s-1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(strategyPositions.positionFor("alpha", symbol)?.quantity).isEqualByComparingTo("50")
    }

    @Test
    fun `a partial fill heard twice is booked once`() {
        run(OneEntry(entry()))
        tick(1L)

        bus.publish(partial("30", "30"))
        bus.publish(partial("30", "30"))

        assertThat(pipeline.orderManager.getOrder("s-1")?.cumulativeFilledQuantity).isEqualByComparingTo("30")
        assertThat(strategyPositions.positionFor("alpha", symbol)?.quantity).isEqualByComparingTo("30")
    }

    @Test
    fun `the rest of a part filled order is booked after a repeated slice`() {
        run(OneEntry(entry()))
        tick(1L)

        bus.publish(partial("30", "30"))
        bus.publish(partial("30", "30"))
        bus.publish(filled("20"))

        assertThat(strategyPositions.positionFor("alpha", symbol)?.quantity).isEqualByComparingTo("50")
    }
}
