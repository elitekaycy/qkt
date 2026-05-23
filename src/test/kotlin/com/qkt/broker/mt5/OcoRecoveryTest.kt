package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OcoRecoveryTest {
    private fun leg(
        id: String,
        ticket: String,
    ) = ManagedOrder(
        id = id,
        request =
            OrderRequest.Stop(
                id = id,
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1"),
                stopPrice = BigDecimal("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        state = OrderState.WORKING,
        brokerOrderId = ticket,
        createdAt = 0L,
        lastUpdatedAt = 0L,
    )

    private fun position(ticket: Long) =
        MT5Position(
            ticket = ticket,
            symbol = "XAUUSD",
            type = 0,
            volume = BigDecimal("1"),
            priceOpen = BigDecimal("2000"),
            sl = BigDecimal.ZERO,
            tp = BigDecimal.ZERO,
            profit = BigDecimal.ZERO,
            magic = 1,
            openTime = 0L,
        )

    @Test
    fun `classifies a pending leg as reseed, a filled leg as emit-fill, a vanished leg as neither`() {
        val orders = listOf(leg("oco-a", "1"), leg("oco-b", "2"), leg("oco-c", "3"))

        val actions =
            classifyOcoRecovery(
                orders = orders,
                pendingTickets = setOf(1L),
                positions = listOf(position(2L)),
            )

        assertThat(actions).hasSize(2)
        val reseed = actions.filterIsInstance<OcoRecoveryAction.Reseed>().single()
        assertThat(reseed.order.id).isEqualTo("oco-a")
        assertThat(reseed.ticket).isEqualTo(1L)
        val fill = actions.filterIsInstance<OcoRecoveryAction.EmitFill>().single()
        assertThat(fill.order.id).isEqualTo("oco-b")
        assertThat(fill.position.ticket).isEqualTo(2L)
    }

    @Test
    fun `skips a leg with no broker ticket`() {
        val noTicket = leg("oco-a", "1").copy(brokerOrderId = null)

        val actions = classifyOcoRecovery(listOf(noTicket), setOf(1L), emptyList())

        assertThat(actions).isEmpty()
    }
}
