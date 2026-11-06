package com.qkt.execution

import com.qkt.common.Side
import com.qkt.execution.OrderRequestEvidenceRequests.allRequests
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestTreeTransformsTest {
    private val composites = setOf("StandaloneOCO", "OTO", "Bracket", "ScaleOut", "TimeExit", "Stack")

    private fun nested(request: OrderRequest): List<OrderRequest> =
        when (request) {
            is OrderRequest.StandaloneOCO -> listOf(request.leg1, request.leg2)
            is OrderRequest.OTO -> listOf(request.parent) + request.children
            is OrderRequest.Bracket -> listOf(request.entry)
            is OrderRequest.ScaleOut -> listOf(request.basis)
            is OrderRequest.TimeExit -> listOf(request.target)
            else -> emptyList()
        }

    private fun tree(request: OrderRequest): List<OrderRequest> = listOf(request) + nested(request).flatMap(::tree)

    private val OrderRequest.type: String get() = this::class.java.simpleName

    @Test
    fun `strategy id is stamped on every request in the tree`() {
        for (request in allRequests()) {
            val stamped = request.withStrategyId("other")

            assertThat(stamped.type).isEqualTo(request.type)
            assertThat(tree(stamped).map { it.strategyId }).allMatch { it == "other" }
        }
    }

    @Test
    fun `expiry rides every leg except on shapes without a GTD meaning`() {
        for (request in allRequests()) {
            val stamped = request.withExpiresAt(42L)

            if (request.type in setOf("Market", "TimeExit", "Stack")) {
                assertThat(stamped).isSameAs(request)
            } else {
                assertThat(tree(stamped).map { it.expiresAt })
                    .`as`(request.type)
                    .allMatch { it == 42L || it == null }
                assertThat(stamped.expiresAt).`as`(request.type).isEqualTo(42L)
            }
        }
        val scaleOut = allRequests().single { it is OrderRequest.ScaleOut } as OrderRequest.ScaleOut
        assertThat((scaleOut.withExpiresAt(7L) as OrderRequest.ScaleOut).basis).isSameAs(scaleOut.basis)
    }

    @Test
    fun `scaling shrinks every quantity in the tree by the factor`() {
        for (request in allRequests()) {
            val scaled = request.scaleQuantity(BigDecimal("0.5"))

            assertThat(scaled.type).isEqualTo(request.type)
            assertThat(tree(scaled).map { it.quantity })
                .`as`(request.type)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyElementsOf(tree(request).map { it.quantity.multiply(BigDecimal("0.5")) })
        }
    }

    @Test
    fun `a leg intent lands on the leaf that fills first and stacks stay unplanned`() {
        val open = LegIntent.Open("leg-9", LegRole.PRIMARY)
        for (request in allRequests()) {
            val planned = request.withLegIntent(open)

            if (request is OrderRequest.Stack) {
                assertThat(planned).isSameAs(request)
                assertThat(planned.exitLegIntent()).isEqualTo(LegIntent.Unplanned)
            } else {
                assertThat(planned.openingLegIntent()).`as`(request.type).isEqualTo(open)
                assertThat(planned.exitLegIntent()).`as`(request.type).isEqualTo(LegIntent.Close(legId = "leg-9"))
            }
        }
        val ocoRequest = allRequests().single { it is OrderRequest.StandaloneOCO }
        val oco = ocoRequest.withLegIntent(LegIntent.Net) as OrderRequest.StandaloneOCO
        assertThat(listOf(oco.leg1.legIntent, oco.leg2.legIntent)).containsOnly(LegIntent.Net)
        assertThat(oco.exitLegIntent()).isEqualTo(LegIntent.Net)
    }

    @Test
    fun `only engine containers are composite shapes`() {
        val flagged = allRequests().filter { it.isCompositeShape() }.map { it.type }.toSet()

        assertThat(flagged).isEqualTo(composites)
    }

    @Test
    fun `a market close whose ticket disagrees with its close intent is refused`() {
        assertThatThrownBy {
            OrderRequest.Market(
                id = "m",
                symbol = "X",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                closesTicket = "T1",
                legIntent = LegIntent.Close(ticket = "T2"),
            )
        }.hasMessageContaining("closesTicket=T1 disagrees with legIntent")
    }

    @Test
    fun `an if-touched close whose ticket disagrees with its close intent is refused`() {
        assertThatThrownBy {
            OrderRequest.IfTouched(
                id = "it",
                symbol = "X",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                triggerPrice = BigDecimal.TEN,
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                closesTicket = "T1",
                legIntent = LegIntent.Close(ticket = "T2"),
            )
        }.hasMessageContaining("closesTicket=T1 disagrees with legIntent")
    }

    @Test
    fun `close references that agree with the intent or leave it open are accepted`() {
        val agreeing =
            OrderRequest.IfTouched(
                id = "it",
                symbol = "X",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                triggerPrice = BigDecimal.TEN,
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                closesTicket = "T1",
                legIntent = LegIntent.Close(legId = "L1"),
            )
        val market =
            OrderRequest.Market(
                id = "m",
                symbol = "X",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                closesLegId = "L1",
                closesTicket = "T1",
                legIntent = LegIntent.Close(legId = "L1", ticket = "T1"),
            )

        assertThat(agreeing.closesTicket).isEqualTo("T1")
        assertThat(market.closesLegId).isEqualTo("L1")
    }
}
