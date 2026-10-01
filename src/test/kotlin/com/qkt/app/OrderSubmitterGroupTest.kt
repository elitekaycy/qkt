package com.qkt.app

import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A structure's legs leave buys first; a leg the venue refuses on the spot stops the legs behind it. */
class OrderSubmitterGroupTest {
    private val clock = FixedClock(5L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val positions = StrategyPositionTracker()
    private val submitter =
        OrderSubmitter(
            SequentialIdGenerator(prefix = "t-"),
            clock,
            bus,
            RiskEngine(emptyList(), positions.account),
            positions.account,
            positions,
            MarketPriceTracker(),
            ExitHookManager(NoopStatePersistor()),
            { PositionAccountingMode.NETTING },
            { BigDecimal.ONE },
        )
    private val book = StructureBook(StructureFixtures.registry, MarketPriceTracker())
    private val sent = mutableListOf<String>()
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    init {
        StructureCoordinator(bus, clock) {}.bind("st", book) {}
        // The venue refuses the wing as it arrives, before the next leg is published.
        bus.subscribe<OrderEvent> { e ->
            sent += e.request.id
            if (e.request.side == Side.BUY) bus.publish(BrokerEvent.OrderRejected(e.request.id, null, "refused", "st"))
        }
    }

    @Test
    fun `a wing the venue refuses on the spot keeps the short from being sent`() {
        val group =
            Signal.SubmitGroup(
                "ps-1",
                "ps",
                listOf(
                    StructureFixtures.market("s", StructureFixtures.P81, Side.SELL),
                    StructureFixtures.market("l", StructureFixtures.P78, Side.BUY),
                ),
            )
        bus.publish(SignalEvent(group, strategyId = "st"))

        submitter.submitGroup("st", strategy, testStrategyContext(strategyId = "st").copy(structures = book), group)

        assertThat(sent).containsExactly("l")
        assertThat(book.live("ps")).isNull()
    }
}
