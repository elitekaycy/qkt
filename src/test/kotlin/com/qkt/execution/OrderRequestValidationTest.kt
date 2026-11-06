package com.qkt.execution

import com.qkt.common.Side
import com.qkt.execution.OrderRequestEvidenceRequests.allRequests
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderRequestValidationTest {
    private val zero = BigDecimal.ZERO
    private val requests = allRequests().associateBy { it::class.java.simpleName }

    private inline fun <reified T : OrderRequest> of(): T = requests.values.filterIsInstance<T>().single()

    private fun withZeroQuantity(request: OrderRequest): OrderRequest =
        when (request) {
            is OrderRequest.Market -> request.copy(quantity = zero)
            is OrderRequest.Limit -> request.copy(quantity = zero)
            is OrderRequest.Stop -> request.copy(quantity = zero)
            is OrderRequest.StopLimit -> request.copy(quantity = zero)
            is OrderRequest.IfTouched -> request.copy(quantity = zero)
            is OrderRequest.TrailingStop -> request.copy(quantity = zero)
            is OrderRequest.ArmedTrailingStop -> request.copy(quantity = zero)
            is OrderRequest.SteppedStop -> request.copy(quantity = zero)
            is OrderRequest.TimeTighteningStop -> request.copy(quantity = zero)
            is OrderRequest.TrailingStopLimit -> request.copy(quantity = zero)
            is OrderRequest.StandaloneOCO -> request.copy(quantity = zero)
            is OrderRequest.OTO -> request.copy(quantity = zero)
            is OrderRequest.Bracket -> request.copy(quantity = zero)
            is OrderRequest.ScaleOut -> request.copy(quantity = zero)
            is OrderRequest.TimeExit -> request.copy(quantity = zero)
            is OrderRequest.Stack -> request.copy(quantity = zero)
        }

    @Test
    fun `every order shape refuses a zero quantity`() {
        assertThat(requests).hasSize(16)
        for (request in requests.values) {
            assertThatThrownBy { withZeroQuantity(request) }
                .`as`(request::class.java.simpleName)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must be > 0")
        }
    }

    @Test
    fun `leaf orders refuse a non-positive price level`() {
        assertThatThrownBy { of<OrderRequest.Limit>().copy(limitPrice = zero) }.hasMessage("limitPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.Stop>().copy(stopPrice = zero) }.hasMessage("stopPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.StopLimit>().copy(stopPrice = zero) }
            .hasMessage("stopPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.StopLimit>().copy(limitPrice = zero) }
            .hasMessage("limitPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.IfTouched>().copy(triggerPrice = zero) }
            .hasMessage("triggerPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.Bracket>().copy(takeProfit = zero) }
            .hasMessage("takeProfit must be > 0: 0")
    }

    @Test
    fun `an if-touched limit needs a positive limit price`() {
        val touch = of<OrderRequest.IfTouched>().copy(onTrigger = TriggerType.MARKET, limitPrice = null)

        assertThatThrownBy { touch.copy(onTrigger = TriggerType.LIMIT) }
            .hasMessage("IfTouched.LIMIT requires limitPrice")
        assertThatThrownBy { touch.copy(onTrigger = TriggerType.LIMIT, limitPrice = zero) }
            .hasMessage("limitPrice must be > 0: 0")
    }

    @Test
    fun `trailing and ratcheting stops refuse non-positive distances and anchors`() {
        assertThatThrownBy { of<OrderRequest.TrailingStop>().copy(trailAmount = zero) }
            .hasMessage("trailAmount must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.ArmedTrailingStop>().copy(entryPrice = zero) }
            .hasMessage("entryPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.SteppedStop>().copy(entryPrice = zero) }
            .hasMessage("entryPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.TimeTighteningStop>().copy(entryPrice = zero) }
            .hasMessage("entryPrice must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.TrailingStopLimit>().copy(trailAmount = zero) }
            .hasMessage("trailAmount must be > 0: 0")
        assertThatThrownBy { of<OrderRequest.TrailingStopLimit>().copy(limitOffset = BigDecimal("-1")) }
            .hasMessage("limitOffset must be >= 0: -1")
    }

    @Test
    fun `a scale-out needs legs and a time exit a positive hold`() {
        assertThatThrownBy { of<OrderRequest.ScaleOut>().copy(legs = emptyList()) }
            .hasMessage("ScaleOut requires at least one leg")
        assertThatThrownBy { of<OrderRequest.TimeExit>().copy(holdMs = 0L) }
            .hasMessage("quantity and holdMs must be > 0")
        assertThat(of<OrderRequest.TimeExit>().copy(holdMs = 1_000L).holdMs).isEqualTo(1_000L)
    }

    @Test
    fun `a market close leg id must agree with its close intent`() {
        val market = of<OrderRequest.Market>()

        assertThatThrownBy { market.copy(legIntent = LegIntent.Close(legId = "other")) }
            .hasMessageContaining("closesLegId=leg-1 disagrees with legIntent")
        val byTicketOnly = market.copy(legIntent = LegIntent.Close(ticket = "ticket-1"))
        assertThat(byTicketOnly.legIntent).isEqualTo(LegIntent.Close(ticket = "ticket-1"))
        val byLegOnly = market.copy(closesTicket = null, legIntent = LegIntent.Close(legId = "leg-1", ticket = "t9"))
        assertThat(byLegOnly.closesTicket).isNull()
    }

    @Test
    fun `an if-touched close without its own ticket takes any close intent`() {
        val touch =
            OrderRequest.IfTouched(
                id = "it",
                symbol = "X",
                side = Side.SELL,
                quantity = BigDecimal.ONE,
                triggerPrice = BigDecimal.TEN,
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                legIntent = LegIntent.Close(ticket = "T2"),
            )

        assertThat(touch.copy(closesTicket = "T2").closesTicket).isEqualTo("T2")
        assertThat(touch.closesTicket).isNull()
    }
}
