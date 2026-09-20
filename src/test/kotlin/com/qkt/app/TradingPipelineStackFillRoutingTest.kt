package com.qkt.app

import com.qkt.app.TradingPipelineStackFixtures.StubDslStrategy
import com.qkt.app.TradingPipelineStackFixtures.newPipeline
import com.qkt.app.TradingPipelineStackFixtures.tier
import com.qkt.common.Side
import com.qkt.dsl.compile.PendingStack
import com.qkt.dsl.compile.PendingStacks
import com.qkt.events.BrokerEvent
import com.qkt.events.OrderEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingPipelineStackFillRoutingTest {
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
