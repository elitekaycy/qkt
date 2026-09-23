package com.qkt.app

import com.qkt.app.TradingPipelineStackFixtures.StubDslStrategy
import com.qkt.app.TradingPipelineStackFixtures.tier
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.PendingStack
import com.qkt.dsl.compile.PendingStacks
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
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

class TradingPipelineStackFillAnchorTest {
    private val quote =
        Tick("EURUSD", BigDecimal("1.1060"), 1L, bid = BigDecimal("1.1055"), ask = BigDecimal("1.1065"))

    @Test
    fun `attach venue receives stack protection beyond the ask, then re-anchored on the leg fill`() {
        val (bus, broker) = wire(ATTACH_VENUE)
        val pipeline = pipelineOf(bus, broker)
        bus.publish(fill("parent-1", "parent-1", Side.BUY, "1.1000", "0.10"))

        pipeline.ingest(quote)

        val shipped = broker.submits.filterIsInstance<OrderRequest.Bracket>().last { it.id.endsWith("-entry") }
        assertThat(shipped.takeProfit).isEqualByComparingTo("1.1265")
        assertThat((shipped.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1.1015")

        bus.publish(fill(shipped.id, "stk-tkt", Side.BUY, "1.1070", "0.05"))

        val modify = broker.modifyPositions.last { it.ticket == "stk-tkt" }
        assertThat(modify.tp).isEqualByComparingTo("1.1270")
        assertThat(modify.sl).isEqualByComparingTo("1.1020")
    }

    @Test
    fun `decomposed venue builds the stack exits from the leg's own fill price`() {
        val (bus, broker) = wire(setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP))
        val pipeline = pipelineOf(bus, broker)
        bus.publish(fill("parent-1", "parent-1", Side.BUY, "1.1000", "0.10"))

        pipeline.ingest(quote)
        val entry = broker.submits.filterIsInstance<OrderRequest.Market>().last { it.id.contains("-tier0") }
        bus.publish(fill(entry.id, entry.id, Side.BUY, "1.1070", "0.05"))

        val target = broker.submits.filterIsInstance<OrderRequest.Limit>().last()
        val stop = broker.submits.filterIsInstance<OrderRequest.Stop>().last()
        assertThat(target.limitPrice).isEqualByComparingTo("1.1270")
        assertThat(stop.stopPrice).isEqualByComparingTo("1.1020")
    }

    @Test
    fun `a leg whose fill runs past its small target distance still gets TP from its own fill`() {
        // Live run 003: the ask rose past a 0.10 distance between tier fire and send. The target is
        // not sent with the entry, so nothing can refuse it; it attaches at fill + distance.
        val (bus, broker) = wire(ATTACH_VENUE)
        val pipeline = pipelineOf(bus, broker, tp = "0.0005")
        bus.publish(fill("parent-1", "parent-1", Side.BUY, "1.1000", "0.10"))
        pipeline.ingest(quote)
        val shipped = broker.submits.filterIsInstance<OrderRequest.Bracket>().last { it.id.endsWith("-entry") }

        bus.publish(fill(shipped.id, "stk-tkt", Side.BUY, "1.1080", "0.05"))

        val modify = broker.modifyPositions.last { it.ticket == "stk-tkt" }
        assertThat(modify.tp).isEqualByComparingTo("1.1085")
        assertThat(modify.sl).isEqualByComparingTo("1.1030")
    }

    @Test
    fun `a refused target modify arms an engine-held target that closes the leg by ticket`() {
        val (bus, broker) = wire(ATTACH_VENUE)
        broker.rejectPositionModifications = true
        val pipeline = pipelineOf(bus, broker, tp = "0.0005")
        bus.publish(fill("parent-1", "parent-1", Side.BUY, "1.1000", "0.10"))
        pipeline.ingest(quote)
        val shipped = broker.submits.filterIsInstance<OrderRequest.Bracket>().last { it.id.endsWith("-entry") }
        bus.publish(fill(shipped.id, "stk-tkt", Side.BUY, "1.1080", "0.05"))
        assertThat(closesOf(broker, "stk-tkt")).isEmpty()

        pipeline.ingest(
            Tick("EURUSD", BigDecimal("1.1090"), 2L, bid = BigDecimal("1.1086"), ask = BigDecimal("1.1094")),
        )

        assertThat(closesOf(broker, "stk-tkt")).singleElement().satisfies({ assertThat(it.side).isEqualTo(Side.SELL) })
    }

    private fun closesOf(
        broker: FakeBroker,
        ticket: String,
    ) = broker.submits.filterIsInstance<OrderRequest.Market>().filter { it.closesTicket == ticket }

    private val clock = FixedClock(time = 0L)
    private val sequencer = MonotonicSequenceGenerator()
    private val priceTracker = MarketPriceTracker()
    private val strategyPositions = StrategyPositionTracker()

    private fun wire(caps: Set<OrderTypeCapability>): Pair<EventBus, FakeBroker> {
        val bus = EventBus(clock, sequencer)
        return bus to FakeBroker(bus, clock, caps)
    }

    private fun pipelineOf(
        bus: EventBus,
        broker: FakeBroker,
        tp: String = "0.020",
    ): TradingPipeline {
        val pendingStacks = PendingStacks()
        pendingStacks.register(
            PendingStack(
                parentClientOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                tiers = listOf(tier(threshold = "0.005", qty = "0.05", sl = "0.005", tp = tp)),
            ),
        )
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        return TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = broker,
            engine = Engine(bus, priceTracker),
            strategies = listOf("alpha" to StubDslStrategy(pendingStacks)),
            riskEngine = RiskEngine(rules = emptyList(), positions = positions),
            riskState = RiskState(pnl, strategyPnL, clock, bus),
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
    }

    private fun fill(
        clientOrderId: String,
        brokerOrderId: String,
        side: Side,
        price: String,
        qty: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = clientOrderId,
        brokerOrderId = brokerOrderId,
        symbol = "EURUSD",
        side = side,
        price = BigDecimal(price),
        quantity = BigDecimal(qty),
        strategyId = "alpha",
        timestamp = 1L,
    )

    private companion object {
        val ATTACH_VENUE =
            setOf(
                OrderTypeCapability.MARKET,
                OrderTypeCapability.LIMIT,
                OrderTypeCapability.STOP,
                OrderTypeCapability.BRACKET,
                OrderTypeCapability.POSITION_MODIFY,
                OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
            )
    }
}
