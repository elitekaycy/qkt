package com.qkt.app

import com.qkt.app.TradingPipelineStackFixtures.StubDslStrategy
import com.qkt.app.TradingPipelineStackFixtures.tier
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.PendingStack
import com.qkt.dsl.compile.PendingStacks
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
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

class TradingPipelineStackLegBookTest {
    @Test
    fun `stack engine fire-then-fill produces a STACK leg in the LegBook`() {
        val pendingStacks = PendingStacks()
        pendingStacks.register(
            PendingStack(
                parentClientOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                tiers = listOf(tier(threshold = "0.005", qty = "0.05")),
            ),
        )
        val strategy = StubDslStrategy(pendingStacks)
        // Hand-build the pipeline so we keep references to priceTracker and strategyPositions
        val clock = FixedClock(time = 0L)
        val ids = SequentialIdGenerator()
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker = PaperBroker(bus, clock, priceTracker)
        val engine = com.qkt.engine.Engine(bus, priceTracker)
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
        // Bypass the actual primary submit — pretend the primary entry already filled at 1.1000
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
        // The PRIMARY leg should be in the LegBook now
        var book = strategyPositions.legBookFor("alpha", "EURUSD")!!
        assertThat(book.primary()!!.role).isEqualTo(com.qkt.positions.LegRole.PRIMARY)
        assertThat(book.stacks()).isEmpty()

        // Drive a favorable tick that crosses the MFE threshold (0.005) → stack engine fires
        // This also updates priceTracker so the stack's inner Market can fill.
        pipeline.ingest(com.qkt.marketdata.Tick("EURUSD", BigDecimal("1.1060"), 1L))

        // After the stack signal flows through OrderManager.submitBracketFallback and the entry
        // Market fills, the STACK leg should appear in the LegBook with the bracket id as legId.
        book = strategyPositions.legBookFor("alpha", "EURUSD")!!
        val stacks = book.stacks()
        assertThat(stacks).hasSize(1)
        assertThat(stacks[0].role).isEqualTo(com.qkt.positions.LegRole.STACK)
        assertThat(stacks[0].parentLegId).isEqualTo("parent-1")
        // Stack quantity comes from the tier; entry price is the tick price (1.1060)
        assertThat(stacks[0].quantity).isEqualByComparingTo("0.05")
        assertThat(stacks[0].entryPrice).isEqualByComparingTo("1.1060")
        // Primary leg untouched: entry still 1.1000
        assertThat(book.primary()!!.entryPrice).isEqualByComparingTo("1.1000")
    }
}
