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
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
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

class TradingPipelineOcoEntryTest {
    /** A DSL strategy stub that emits one OCO_ENTRY straddle on its first tick. */
    private class StraddleEmittingStrategy(
        private val oco: OrderRequest,
    ) : DslCompiledStrategy {
        override val pendingStacks = PendingStacks()
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
        override val declaredStreams: Map<String, HubKey> = emptyMap()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        private var emitted = false

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) { }

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (!emitted) {
                emitted = true
                emit(Signal.Submit(oco))
            }
        }
    }

    private fun trailingBracket(
        bracketId: String,
        entryId: String,
        side: Side,
        stop: String,
    ) = OrderRequest.Bracket(
        id = bracketId,
        symbol = "XAUUSD",
        side = side,
        quantity = BigDecimal("0.25"),
        entry =
            OrderRequest.Stop(
                id = entryId,
                symbol = "XAUUSD",
                side = side,
                quantity = BigDecimal("0.25"),
                stopPrice = BigDecimal(stop),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        takeProfit = BigDecimal("2050"),
        stopLoss = StopLossSpec.ArmedTrail(trailDistance = BigDecimal("18"), mfeThreshold = BigDecimal("18")),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `OCO_ENTRY straddle tracks both filled legs as independent positions`() {
        val clock = FixedClock(0L)
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

        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.25"),
                leg1 = trailingBracket("bA", "eA", Side.BUY, "2010"),
                leg2 = trailingBracket("bB", "eB", Side.SELL, "1990"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )

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
            strategies = listOf("alpha" to StraddleEmittingStrategy(oco)),
            riskEngine = riskEngine,
            riskState = riskState,
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )

        // A tick triggers the strategy to emit the OCO_ENTRY → registers the independent-leg opens.
        bus.publish(TickEvent(Tick("XAUUSD", BigDecimal("2000"), 100L)))

        // Both straddle entries fill — price whipsawed through both stops.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "eA",
                brokerOrderId = "tA",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = BigDecimal("2010"),
                quantity = BigDecimal("0.25"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "eB",
                brokerOrderId = "tB",
                symbol = "XAUUSD",
                side = Side.SELL,
                price = BigDecimal("1990"),
                quantity = BigDecimal("0.25"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )

        // Two real positions — not netted to zero. This is the truthful view the straddle's
        // re-arm gate needs (POSITION.count = 2, not POSITION = 0).
        assertThat(strategyPositions.openCountFor("alpha", "XAUUSD")).isEqualTo(2)
        assertThat(strategyPositions.longCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(strategyPositions.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
        // The net view still reads 0 — back-compat for everything reading POSITION.quantity.
        assertThat(strategyPositions.positionFor("alpha", "XAUUSD")?.quantity).isEqualByComparingTo("0")
    }
}
