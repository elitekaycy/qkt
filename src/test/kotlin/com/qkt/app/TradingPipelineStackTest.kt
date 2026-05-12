package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.CompiledStackTier
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStack
import com.qkt.dsl.compile.PendingStacks
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskState
import com.qkt.strategy.Mode
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineStackTest {
    private class StubDslStrategy(
        override val pendingStacks: PendingStacks,
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet(),
    ) : DslCompiledStrategy {
        override val declaredStreams: Map<String, HubKey> = emptyMap()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) { }

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) { }
    }

    private fun tier(
        threshold: String = "0.005",
        qty: String = "0.05",
        sl: String = "0.005",
        tp: String = "0.020",
    ) = CompiledStackTier(
        mfeThreshold = BigDecimal(threshold),
        withinMs = 30 * 60 * 1000L,
        stackQuantity = BigDecimal(qty),
        slDistance = BigDecimal(sl),
        tpDistance = BigDecimal(tp),
    )

    private fun newPipeline(strategy: DslCompiledStrategy): Pair<TradingPipeline, EventBus> {
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        val pipeline =
            TradingPipeline(
                clock = clock,
                ids = ids,
                sequencer = sequencer,
                priceTracker = priceTracker,
                positions = positions,
                pnl = pnl,
                strategyPositions = strategyPositions,
                strategyPnL = strategyPnL,
                bus = bus,
                broker = broker,
                engine = engine,
                strategies = listOf("alpha" to strategy),
                riskEngine = riskEngine,
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        return pipeline to bus
    }

    @Test
    fun `OrderFilled for a pending stacked primary spawns an engine that fires on a favorable tick`() {
        val pendingStacks = PendingStacks()
        pendingStacks.register(
            PendingStack(
                parentClientOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                tiers = listOf(tier()),
            ),
        )
        val strategy = StubDslStrategy(pendingStacks)
        val (_, bus) = newPipeline(strategy)

        val stackOrders = mutableListOf<OrderRequest>()
        bus.subscribe<OrderEvent> { e ->
            // Filter to bracket orders only — the stack engine emits Signal.Submit(Bracket)
            if (e.request is OrderRequest.Bracket) stackOrders.add(e.request)
        }

        // Simulate the primary fill at 1.1000
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "parent-1",
                brokerOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = BigDecimal("1.1000"),
                quantity = BigDecimal("0.10"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )
        // PendingStacks must have been consumed
        assertThat(pendingStacks.contains("parent-1")).isFalse

        // Unfavorable tick — engine exists but tier not yet triggered
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.1010"), 100L)))
        assertThat(stackOrders).isEmpty()

        // Favorable tick — MFE = 1.1060 - 1.1000 = 0.006 ≥ 0.005 → tier fires
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.1060"), 200L)))
        assertThat(stackOrders).hasSize(1)
        val stack = stackOrders.single() as OrderRequest.Bracket
        assertThat(stack.symbol).isEqualTo("EURUSD")
        assertThat(stack.side).isEqualTo(Side.BUY)
        assertThat(stack.quantity).isEqualByComparingTo("0.05")
    }

    @Test
    fun `OrderFilled with no matching pending stack is a no-op`() {
        val strategy = StubDslStrategy(PendingStacks())
        val (_, bus) = newPipeline(strategy)
        val stackOrders = mutableListOf<OrderRequest>()
        bus.subscribe<OrderEvent> { e ->
            if (e.request is OrderRequest.Bracket) stackOrders.add(e.request)
        }
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "unrelated",
                brokerOrderId = "unrelated",
                symbol = "EURUSD",
                side = Side.BUY,
                price = BigDecimal("1.1000"),
                quantity = BigDecimal("0.10"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.1500"), 100L))) // huge move
        assertThat(stackOrders).isEmpty()
    }

    @Test
    fun `OrderFilled on a bracket close-watch id terminates the engine`() {
        val pendingStacks = PendingStacks()
        pendingStacks.register(
            PendingStack(
                parentClientOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                tiers = listOf(tier()),
                closeWatchIds = setOf("bracket-1-tp", "bracket-1-sl"),
            ),
        )
        val strategy = StubDslStrategy(pendingStacks)
        val (_, bus) = newPipeline(strategy)

        val stackOrders = mutableListOf<OrderRequest>()
        bus.subscribe<OrderEvent> { e ->
            if (e.request is OrderRequest.Bracket) stackOrders.add(e.request)
        }

        // Primary entry fills — engine constructed
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "parent-1",
                brokerOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = BigDecimal("1.1000"),
                quantity = BigDecimal("0.10"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )

        // Bracket TP fires (parent leg closed) BEFORE any favorable tick
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "bracket-1-tp",
                brokerOrderId = "bracket-1-tp",
                symbol = "EURUSD",
                side = Side.SELL,
                price = BigDecimal("1.1200"),
                quantity = BigDecimal("0.10"),
                strategyId = "alpha",
                timestamp = 100L,
            ),
        )

        // Subsequent favorable tick must NOT fire any stack — engine was removed
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.1500"), 200L)))
        assertThat(stackOrders).isEmpty()
    }

    @Test
    fun `OrderFilled for a different strategyId is ignored`() {
        val pendingStacks = PendingStacks()
        pendingStacks.register(
            PendingStack(
                parentClientOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                tiers = listOf(tier()),
            ),
        )
        val strategy = StubDslStrategy(pendingStacks)
        val (_, bus) = newPipeline(strategy)
        val stackOrders = mutableListOf<OrderRequest>()
        bus.subscribe<OrderEvent> { e ->
            if (e.request is OrderRequest.Bracket) stackOrders.add(e.request)
        }
        // Different strategy fills the same client id — must NOT trigger the alpha-owned engine
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "parent-1",
                brokerOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = BigDecimal("1.1000"),
                quantity = BigDecimal("0.10"),
                strategyId = "beta",
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.1060"), 100L)))
        assertThat(stackOrders).isEmpty()
        // PendingStacks must NOT have been consumed
        assertThat(pendingStacks.contains("parent-1")).isTrue
    }
}
