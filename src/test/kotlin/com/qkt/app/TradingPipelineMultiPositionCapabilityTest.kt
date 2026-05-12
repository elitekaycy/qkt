package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.engine.Engine
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradingPipelineMultiPositionCapabilityTest {
    private class StubDslStrategy(
        override val multiPositionPerSymbolSymbols: Set<String>,
    ) : DslCompiledStrategy {
        override val declaredStreams: Map<String, HubKey> = emptyMap()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        override val pendingStacks: PendingStacks = PendingStacks()

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

    /** Broker that advertises no special capabilities — used to fail the gate. */
    private class CapabilitylessBroker : Broker {
        override val name: String = "Capabilityless"
        override val capabilities: Set<OrderTypeCapability> = setOf(OrderTypeCapability.MARKET)

        override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) { }
    }

    /** Broker that advertises MULTI_POSITION_PER_SYMBOL. */
    private class MultiPositionBroker : Broker {
        override val name: String = "MultiPositionCapable"
        override val capabilities: Set<OrderTypeCapability> =
            setOf(OrderTypeCapability.MARKET, OrderTypeCapability.MULTI_POSITION_PER_SYMBOL)

        override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, request.id, accepted = true)

        override fun cancel(orderId: String) { }
    }

    private fun newPipeline(
        strategy: DslCompiledStrategy,
        broker: Broker,
    ): TradingPipeline {
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val engine = Engine(bus, priceTracker)
        val riskState = RiskState(pnl, strategyPnL, clock, bus)
        val riskEngine = RiskEngine(rules = emptyList(), positions = positions)
        return TradingPipeline(
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
    }

    @Test
    fun `strategy with STACK_AT against a non-multi-pos broker fails to deploy`() {
        val strategy = StubDslStrategy(multiPositionPerSymbolSymbols = setOf("EURUSD"))
        assertThatThrownBy { newPipeline(strategy, CapabilitylessBroker()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("alpha")
            .hasMessageContaining("EURUSD")
            .hasMessageContaining("MULTI_POSITION_PER_SYMBOL")
            .hasMessageContaining("Capabilityless")
    }

    @Test
    fun `strategy with STACK_AT against a multi-pos-capable broker deploys cleanly`() {
        val strategy = StubDslStrategy(multiPositionPerSymbolSymbols = setOf("EURUSD"))
        val pipeline = newPipeline(strategy, MultiPositionBroker())
        assertThat(pipeline).isNotNull
    }

    @Test
    fun `strategy without STACK_AT deploys against any broker`() {
        val strategy = StubDslStrategy(multiPositionPerSymbolSymbols = emptySet())
        val pipeline = newPipeline(strategy, CapabilitylessBroker())
        assertThat(pipeline).isNotNull
    }
}
