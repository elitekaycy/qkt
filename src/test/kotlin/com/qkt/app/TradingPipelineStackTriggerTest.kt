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

class TradingPipelineStackTriggerTest {
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
    fun `pending MAE recovery stack fires after adverse move recovers`() {
        val pendingStacks = PendingStacks()
        pendingStacks.register(
            PendingStack(
                parentClientOrderId = "parent-1",
                symbol = "EURUSD",
                side = Side.BUY,
                tiers = listOf(tier(threshold = "0.010", recover = "0.006")),
            ),
        )
        val strategy = StubDslStrategy(pendingStacks)
        val (_, bus) = newPipeline(strategy)
        val stackOrders = mutableListOf<OrderRequest>()
        bus.subscribe<OrderEvent> { e ->
            if (e.request is OrderRequest.Bracket) stackOrders.add(e.request)
        }

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

        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.0900"), 100L)))
        assertThat(stackOrders).isEmpty()
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.0950"), 200L)))
        assertThat(stackOrders).isEmpty()
        bus.publish(TickEvent(Tick("EURUSD", BigDecimal("1.0960"), 300L)))

        assertThat(stackOrders).hasSize(1)
        val stack = stackOrders.single() as OrderRequest.Bracket
        assertThat(stack.symbol).isEqualTo("EURUSD")
        assertThat(stack.side).isEqualTo(Side.BUY)
        assertThat(stack.quantity).isEqualByComparingTo("0.05")
    }
}
