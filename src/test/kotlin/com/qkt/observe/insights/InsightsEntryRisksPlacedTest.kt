package com.qkt.observe.insights

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsEntryRisksPlacedTest {
    private val qty = BigDecimal("0.1")
    private val entry = OrderRequest.Market("e", "XAUUSD", Side.BUY, qty, TimeInForce.GTC, 1L)
    private val bracket =
        OrderRequest.Bracket(
            id = "b",
            symbol = "XAUUSD",
            side = Side.BUY,
            quantity = qty,
            entry = entry,
            takeProfit = BigDecimal("4720"),
            stopLoss = StopLossSpec.Fixed(BigDecimal("4690")),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
        )

    @Test
    fun `protective orders placed after the fill replace the position's initial levels`() {
        val risks = InsightsEntryRisks(InsightsVenues())
        risks.submitted(bracket)
        val atFill = risks.entered("pos-1", "e", "XAUUSD", BigDecimal("4700"), qty)
        assertThat(atFill!!["initialTarget"] as BigDecimal).isEqualByComparingTo("4720")
        assertThat(atFill).containsEntry("riskAmount", null)

        risks.submitted(OrderRequest.Stop("b-sl", "XAUUSD", Side.SELL, qty, BigDecimal("4695"), TimeInForce.GTC, 2L))
        risks.submitted(OrderRequest.Limit("b-tp", "XAUUSD", Side.SELL, qty, BigDecimal("4725"), TimeInForce.GTC, 2L))

        assertThat(risks.fields("XAUUSD", "pos-1"))
            .containsEntry("initialStop", BigDecimal("4695"))
            .containsEntry("initialTarget", BigDecimal("4725"))
    }

    @Test
    fun `a protective order for an unfilled bracket changes no position`() {
        val risks = InsightsEntryRisks(InsightsVenues())
        risks.submitted(bracket)

        risks.submitted(OrderRequest.Stop("b-sl", "XAUUSD", Side.SELL, qty, BigDecimal("4695"), TimeInForce.GTC, 2L))

        assertThat(risks.fields("XAUUSD", "pos-1")).isEmpty()
        assertThat(risks.entered("pos-1", "e", "XAUUSD", BigDecimal("4700"), qty))
            .containsEntry("initialStop", BigDecimal("4695"))
    }
}
