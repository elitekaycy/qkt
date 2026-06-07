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
    private val symbol = "XAUUSD"

    /** A DSL strategy stub that emits an OCO_ENTRY straddle on each tick `onTick` returns true for. */
    private class StraddleStrategy(
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

    private class Harness(
        val bus: EventBus,
        val strategyPositions: StrategyPositionTracker,
        val ocoEmits: MutableList<OrderRequest.StandaloneOCO>,
    )

    private fun harness(strategy: DslCompiledStrategy): Harness {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val positions = PositionTracker()
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPositions = StrategyPositionTracker()
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

    private fun straddle(tag: String) =
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

    private fun fill(
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

    @Test
    fun `OCO_ENTRY straddle tracks both filled legs as independent positions`() {
        val h = harness(StraddleStrategy(shouldEmit = { _, _ -> true }, makeOco = { straddle("1") }))

        // A tick triggers the strategy to emit the OCO_ENTRY → registers the independent-leg opens.
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 100L)))
        // Both straddle entries fill — price whipsawed through both stops.
        fill(h.bus, "e1-A", Side.BUY, "2010")
        fill(h.bus, "e1-B", Side.SELL, "1990")

        // Two real positions — not netted to zero. This is the truthful view.
        assertThat(h.strategyPositions.openCountFor("alpha", symbol)).isEqualTo(2)
        assertThat(h.strategyPositions.longCountFor("alpha", symbol)).isEqualTo(1)
        assertThat(h.strategyPositions.shortCountFor("alpha", symbol)).isEqualTo(1)
        // The net view still reads 0 — back-compat for everything reading POSITION.quantity.
        assertThat(h.strategyPositions.positionFor("alpha", symbol)?.quantity).isEqualByComparingTo("0")
    }

    @Test
    fun `count-gated straddle does not re-arm after both legs fill`() {
        // The fix for the prod accumulation: gate on the TRUTHFUL count, not the net. Emit a
        // straddle once per session tick, only while flat by count. The old POSITION = 0 gate
        // re-armed every session because the two filled legs netted to 0; POSITION.count = 2
        // does not.
        val sessionTimes = setOf(100L, 200L)
        var n = 0
        val strategy =
            StraddleStrategy(
                shouldEmit = { tick, ctx ->
                    tick.timestamp in sessionTimes && ctx.positions.openCountFor(symbol) == 0
                },
                makeOco = {
                    n += 1
                    straddle("s$n")
                },
            )
        val h = harness(strategy)

        // Session 1: flat → arm one straddle, then both legs whipsaw-fill.
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 100L)))
        fill(h.bus, "es1-A", Side.BUY, "2010")
        fill(h.bus, "es1-B", Side.SELL, "1990")
        assertThat(h.strategyPositions.openCountFor("alpha", symbol)).isEqualTo(2)

        // Session 2: count is 2, not 0 → the gate holds, no new straddle is armed.
        h.bus.publish(TickEvent(Tick(symbol, BigDecimal("2000"), 200L)))

        assertThat(h.ocoEmits).hasSize(1)
        assertThat(h.strategyPositions.openCountFor("alpha", symbol)).isEqualTo(2)
    }
}
