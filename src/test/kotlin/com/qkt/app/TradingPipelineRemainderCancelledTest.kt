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
 * A venue order that filled in part and whose remainder the venue cancelled (a market order's unfilled
 * rest, or a triggered stop's): the strategy holds what filled, nothing sends the remainder again, and
 * `OPEN_ORDERS` returns to zero.
 */
class TradingPipelineRemainderCancelledTest {
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

    /** The venue fills 30 of the order's 50, then ends it cancelled. */
    private fun fillPartThenCancel(id: String) {
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                id,
                "v-1",
                symbol,
                Side.BUY,
                BigDecimal("60010"),
                BigDecimal("30"),
                BigDecimal("30"),
                "alpha",
                timestamp = 2L,
            ),
        )
        bus.publish(BrokerEvent.OrderCancelled(id, "v-1", "cancelled at the venue", "alpha", timestamp = 3L))
    }

    private fun assertHoldsTheFilledPartOnly(strategy: OneEntry) {
        val id = broker.submits.single().id
        fillPartThenCancel(id)
        val order = pipeline.orderManager.getOrder(id)
        assertThat(order?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(order?.cumulativeFilledQuantity).isEqualByComparingTo("30")
        tick(4L)
        tick(5L)

        assertThat(strategyPositions.positionFor("alpha", symbol)?.quantity).isEqualByComparingTo("30")
        assertThat(broker.submits).hasSize(1)
        assertThat(broker.cancels).isEmpty()
        assertThat(strategy.openOrders.first()).isEqualTo(1)
        assertThat(strategy.openOrders.drop(1)).containsOnly(0)
    }

    @Test
    fun `a market order answered already cancelled with a part filled holds that part and resends nothing`() {
        broker.emitAcceptOnSubmit = false
        val strategy = OneEntry(market())
        run(strategy)

        tick(1L)

        assertHoldsTheFilledPartOnly(strategy)
    }

    @Test
    fun `a triggered stop whose remainder the venue cancelled holds the filled part and resends nothing`() {
        val strategy =
            OneEntry(
                OrderRequest.Stop("s-1", symbol, Side.BUY, BigDecimal("50"), BigDecimal("60005"), TimeInForce.GTC, 0L),
            )
        run(strategy)

        tick(1L)

        assertHoldsTheFilledPartOnly(strategy)
    }

    private fun market() = OrderRequest.Market("m-1", symbol, Side.BUY, BigDecimal("50"), TimeInForce.GTC, 0L)
}
