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
import com.qkt.events.OrderEvent
import com.qkt.execution.OrderRequest
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

/**
 * Phase 27 Task 21 — synthetic P&L sanity. Drives the same upward price trajectory
 * through two pipeline variants and confirms the variant with `STACK_AT` realizes
 * materially more P&L than the no-stack baseline. The threshold is "measurably
 * positive" — the production +148% number from pa-quant is on a year of XAUUSD M5
 * data, not reproducible here.
 *
 * Both variants share the same primary parameters (entry, bracket, qty). The stack
 * variant adds one tier that fires once MFE crosses 0.005 within the window, with its
 * own bracket. The synthetic trajectory hits the primary's TP and the stack's TP, so
 * both close at profit.
 */
class StackPnlSanityTest {
    private val symbol = "EURUSD"
    private val strategyId = "alpha"
    private val primaryEntry = BigDecimal("1.1000")
    private val primaryQty = BigDecimal("0.10")
    private val primarySl = BigDecimal("0.0050")
    private val primaryTp = BigDecimal("0.0200")
    private val stackQty = BigDecimal("0.05")
    private val stackSl = BigDecimal("0.0050")
    private val stackTp = BigDecimal("0.0200")
    private val mfeThreshold = BigDecimal("0.0050")

    private class StubDslStrategy(
        override val pendingStacks: PendingStacks,
        override val multiPositionPerSymbolSymbols: Set<String>,
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

    private data class Harness(
        val pipeline: TradingPipeline,
        val bus: EventBus,
        val strategyPnL: StrategyPnL,
        val priceTracker: MarketPriceTracker,
    )

    private fun harness(pendingStacks: PendingStacks): Harness {
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
        val strategy =
            StubDslStrategy(
                pendingStacks = pendingStacks,
                // Empty set so the capability gate doesn't apply for the no-stack variant;
                // PaperBroker has the capability anyway so the stack variant is fine too.
                multiPositionPerSymbolSymbols = emptySet(),
            )
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
                strategies = listOf(strategyId to strategy),
                riskEngine = riskEngine,
                riskState = riskState,
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        return Harness(pipeline, bus, strategyPnL, priceTracker)
    }

    private fun primaryBracket(parentLegId: String): OrderRequest.Bracket {
        val entry =
            OrderRequest.Market(
                id = parentLegId,
                symbol = symbol,
                side = Side.BUY,
                quantity = primaryQty,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = strategyId,
            )
        return OrderRequest.Bracket(
            id = "$parentLegId-bracket",
            symbol = symbol,
            side = Side.BUY,
            quantity = primaryQty,
            entry = entry,
            takeProfit = primaryEntry.add(primaryTp), // 1.1200
            stopLoss = primaryEntry.subtract(primarySl), // 1.0950
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )
    }

    /** Run an upward trajectory: entry → stack-fire price → primary TP → stack TP. */
    private fun runTrajectory(h: Harness) {
        // Tick 1: at entry price — the bracket's entry market fills here
        h.pipeline.ingest(Tick(symbol, primaryEntry, 0L))
        // Tick 2: crosses MFE threshold (1.1060) — stack would fire here in the stack variant
        h.pipeline.ingest(Tick(symbol, BigDecimal("1.1060"), 1L))
        // Tick 3: primary TP price (1.1200) — primary bracket's TP limit fires
        h.pipeline.ingest(Tick(symbol, BigDecimal("1.1200"), 2L))
        // Tick 4: stack TP price (1.1260 = 1.1060 + 0.0200) — stack bracket's TP fires
        h.pipeline.ingest(Tick(symbol, BigDecimal("1.1260"), 3L))
    }

    @Test
    fun `stack version realizes materially more P&L than no-stack baseline`() {
        // Variant A — no stacks
        val pendingNoStack = PendingStacks()
        val ha = harness(pendingNoStack)
        // Pre-warm priceTracker so the entry Market can resolve
        ha.priceTracker.update(symbol, primaryEntry)
        ha.bus.publish(OrderEvent(primaryBracket("a-primary")))
        runTrajectory(ha)
        val pnlNoStack = ha.strategyPnL.realizedFor(strategyId)

        // Variant B — one STACK_AT tier
        val pendingWithStack = PendingStacks()
        // The pending registration mirrors what ActionCompiler.closeWatchIdsFor would build
        pendingWithStack.register(
            PendingStack(
                parentClientOrderId = "b-primary",
                symbol = symbol,
                side = Side.BUY,
                tiers =
                    listOf(
                        CompiledStackTier(
                            mfeThreshold = mfeThreshold,
                            withinMs = 30 * 60 * 1000L,
                            stackQuantity = stackQty,
                            slDistance = stackSl,
                            tpDistance = stackTp,
                        ),
                    ),
                closeWatchIds = setOf("b-primary-bracket-tp", "b-primary-bracket-sl"),
            ),
        )
        val hb = harness(pendingWithStack)
        hb.priceTracker.update(symbol, primaryEntry)
        hb.bus.publish(OrderEvent(primaryBracket("b-primary")))
        runTrajectory(hb)
        val pnlWithStack = hb.strategyPnL.realizedFor(strategyId)

        // Both close primary at profit. Stack variant adds the stack's own realized PnL on top.
        assertThat(pnlNoStack).isGreaterThan(BigDecimal.ZERO)
        assertThat(pnlWithStack).isGreaterThan(pnlNoStack)
        // Sanity: the stack adds at least 25% more P&L on this synthetic trajectory.
        val improvement =
            pnlWithStack.subtract(pnlNoStack).divide(pnlNoStack, java.math.MathContext.DECIMAL64)
        assertThat(improvement).isGreaterThan(BigDecimal("0.25"))
    }
}
