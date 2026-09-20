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
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
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

object TradingPipelineOcoEntryFixtures {
    val symbol = "XAUUSD"

    /** A DSL strategy stub that emits an OCO_ENTRY straddle on each tick `onTick` returns true for. */
    class StraddleStrategy(
        private val shouldEmit: (Tick, StrategyContext) -> Boolean,
        private val makeOco: () -> OrderRequest,
    ) : DslCompiledStrategy {
        override val pendingStacks = PendingStacks()
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
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
        ) {
            if (shouldEmit(tick, ctx)) emit(Signal.Submit(makeOco()))
        }
    }

    class Harness(
        val bus: EventBus,
        val strategyPositions: StrategyPositionTracker,
        val ocoEmits: MutableList<OrderRequest.StandaloneOCO>,
    )

    fun harness(strategy: DslCompiledStrategy): Harness {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        TradingPipeline(
            clock = clock,
            ids = SequentialIdGenerator(),
            sequencer = sequencer,
            priceTracker = priceTracker,
            positions = positions,
            pnl = pnl,
            strategyPositions = strategyPositions,
            strategyPnL = strategyPnL,
            bus = bus,
            broker = PaperBroker(bus, clock, priceTracker),
            engine = Engine(bus, priceTracker),
            strategies = listOf("alpha" to strategy),
            riskEngine = RiskEngine(rules = emptyList(), positions = positions),
            riskState = RiskState(pnl, strategyPnL, clock, bus),
            mode = Mode.BACKTEST,
            calendar = TradingCalendar.crypto(),
            source = NullMarketSource,
            candleWindow = TimeWindow.ONE_MINUTE,
        )
        val ocoEmits = mutableListOf<OrderRequest.StandaloneOCO>()
        bus.subscribe<OrderEvent> { e -> (e.request as? OrderRequest.StandaloneOCO)?.let { ocoEmits.add(it) } }
        return Harness(bus, strategyPositions, ocoEmits)
    }

    private fun trailingBracket(
        bracketId: String,
        entryId: String,
        side: Side,
        stop: String,
        tp: String,
    ) = OrderRequest.Bracket(
        id = bracketId,
        symbol = symbol,
        side = side,
        quantity = BigDecimal("0.25"),
        entry =
            OrderRequest.Stop(
                id = entryId,
                symbol = symbol,
                side = side,
                quantity = BigDecimal("0.25"),
                stopPrice = BigDecimal(stop),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        takeProfit = BigDecimal(tp),
        stopLoss = StopLossSpec.ArmedTrail(trailDistance = BigDecimal("18"), mfeThreshold = BigDecimal("18")),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    fun straddle(tag: String) =
        OrderRequest.StandaloneOCO(
            id = "oco-$tag",
            symbol = symbol,
            side = Side.BUY,
            quantity = BigDecimal("0.25"),
            // TP/SL placed clear of the test's 2000 price so the exits don't fire during the
            // re-arm check: long TP above (2100), short TP below (1900).
            leg1 = trailingBracket("b$tag-A", "e$tag-A", Side.BUY, "2010", tp = "2100"),
            leg2 = trailingBracket("b$tag-B", "e$tag-B", Side.SELL, "1990", tp = "1900"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    fun fill(
        bus: EventBus,
        clientOrderId: String,
        side: Side,
        price: String,
    ) = bus.publish(
        BrokerEvent.OrderFilled(
            clientOrderId = clientOrderId,
            brokerOrderId = "t-$clientOrderId",
            symbol = symbol,
            side = side,
            price = BigDecimal(price),
            quantity = BigDecimal("0.25"),
            strategyId = "alpha",
            timestamp = 0L,
        ),
    )
}
