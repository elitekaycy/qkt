package com.qkt.execution

import com.qkt.common.Side
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestLegIntentTest {
    private val market =
        OrderRequest.Market("m1", "XAUUSD", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1_000L, "s1")

    private val limit =
        OrderRequest.Limit("l1", "XAUUSD", Side.BUY, BigDecimal.ONE, BigDecimal("2400"), TimeInForce.GTC, 1_000L)

    @Test
    fun `every leaf defaults to Unplanned`() {
        assertThat(market.legIntent).isEqualTo(LegIntent.Unplanned)
        assertThat(limit.legIntent).isEqualTo(LegIntent.Unplanned)
    }

    @Test
    fun `withLegIntent stamps a leaf and survives the other copy helpers`() {
        val open = LegIntent.Open("leg-1", LegRole.INDEPENDENT)
        val planned = limit.withLegIntent(open)

        assertThat(planned.legIntent).isEqualTo(open)
        assertThat(planned.withStrategyId("s9").legIntent).isEqualTo(open)
        assertThat(planned.withExpiresAt(5_000L).legIntent).isEqualTo(open)
        assertThat(planned.scaleQuantity(BigDecimal("0.5")).legIntent).isEqualTo(open)
    }

    @Test
    fun `withLegIntent on a bracket reaches its entry and openingLegIntent reads it back`() {
        val bracket =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal.ONE,
                entry = market,
                takeProfit = BigDecimal("2450"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("2350")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            )
        val open = LegIntent.Open("b1", LegRole.PRIMARY)

        val planned = bracket.withLegIntent(open) as OrderRequest.Bracket

        assertThat(planned.entry.legIntent).isEqualTo(open)
        assertThat(planned.legIntent).isEqualTo(LegIntent.Unplanned)
        assertThat(planned.openingLegIntent()).isEqualTo(open)
    }

    @Test
    fun `a Close intent must agree with the legacy close fields`() {
        val agreeing =
            market.copy(
                closesLegId = "leg-7",
                closesTicket = "42",
                legIntent = LegIntent.Close(legId = "leg-7", ticket = "42"),
            )
        assertThat(agreeing.legIntent).isEqualTo(LegIntent.Close("leg-7", "42"))

        assertThatThrownBy {
            market.copy(closesLegId = "leg-7", legIntent = LegIntent.Close(legId = "leg-8"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `STACK open intent requires a parent and Close requires an identifier`() {
        assertThatThrownBy { LegIntent.Open("t1", LegRole.STACK) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LegIntent.Close() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
