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

class TradingPipelineStackAttachVenueTest {
    @Test
    fun `stack on an attach venue is tracked by ticket and a venue close realizes it`() {
        // The live divergence: on a venue that attaches SL/TP (BRACKET + POSITION_MODIFY), the
        // stack bracket ships native. It must be keyed under its entry id so registerStackOpen
        // (entry.id) matches the fill — otherwise the STACK leg is invisible to POSITION.count on
        // live (PaperBroker can't catch this; its fallback already keys on entry.id).
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
        val clock = FixedClock(time = 0L)
        val sequencer = MonotonicSequenceGenerator()
        val priceTracker = MarketPriceTracker()
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        val pnl = PnLCalculator(positions, priceTracker)
        val strategyPnL = StrategyPnL(strategyPositions, priceTracker)
        val bus = EventBus(clock, sequencer)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(
                    OrderTypeCapability.MARKET,
                    OrderTypeCapability.LIMIT,
                    OrderTypeCapability.STOP,
                    OrderTypeCapability.BRACKET,
                    OrderTypeCapability.POSITION_MODIFY,
                    OrderTypeCapability.MULTI_POSITION_PER_SYMBOL,
                ),
            )
        val pipeline =
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
                broker = broker,
                engine = Engine(bus, priceTracker),
                strategies = listOf("alpha" to strategy),
                riskEngine = RiskEngine(rules = emptyList(), positions = positions),
                riskState = RiskState(pnl, strategyPnL, clock, bus),
                mode = Mode.BACKTEST,
                calendar = TradingCalendar.crypto(),
                source = NullMarketSource,
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        // Primary fills → engine spawned.
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
        // Favorable tick crosses the MFE threshold → the engine fires the stack bracket, shipped
        // native (attach venue) keyed under its entry id.
        pipeline.ingest(Tick("EURUSD", BigDecimal("1.1060"), 1L))
        val shipped =
            broker.submits.filterIsInstance<OrderRequest.Bracket>().last { it.id.endsWith("-entry") }
        // FakeBroker doesn't auto-fill — emit the venue open under the entry id, with a ticket.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = shipped.id,
                brokerOrderId = "stk-tkt",
                symbol = "EURUSD",
                side = Side.BUY,
                price = BigDecimal("1.1060"),
                quantity = BigDecimal("0.05"),
                strategyId = "alpha",
                timestamp = 1L,
            ),
        )

        // The STACK leg is tracked on the attach path, with its venue ticket.
        var book = strategyPositions.legBookFor("alpha", "EURUSD")!!
        assertThat(book.stacks()).hasSize(1)
        assertThat(book.stacks()[0].role).isEqualTo(com.qkt.positions.LegRole.STACK)
        assertThat(book.stacks()[0].parentLegId).isEqualTo("parent-1")
        assertThat(book.stacks()[0].brokerTicket).isEqualTo("stk-tkt")

        // The venue closes the stack (attached TP hit); the poller reports it under the entry id
        // with the ticket. It realizes the STACK leg by ticket — no counter, primary survives.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = shipped.id,
                brokerOrderId = "stk-tkt",
                symbol = "EURUSD",
                side = Side.SELL,
                price = BigDecimal("1.1100"),
                quantity = BigDecimal("0.05"),
                strategyId = "alpha",
                timestamp = 2L,
            ),
        )
        book = strategyPositions.legBookFor("alpha", "EURUSD")!!
        assertThat(book.stacks()).isEmpty()
        assertThat(book.primary()!!.entryPrice).isEqualByComparingTo("1.1000")
    }
}
