package com.qkt.execution

import com.qkt.common.Side
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestEvidenceLegIntentTest {
    private val market =
        OrderRequest.Market("m1", "XAUUSD", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 1L, "s")

    @Test
    fun `unplanned intent is recorded as null so earlier captures stay comparable`() {
        assertThat(OrderRequestEvidence.payload(market)).containsEntry("legIntent", null)
        assertThat(OrderRequestEvidence.toJson(market)).contains("\"legIntent\":null")
    }

    @Test
    fun `planned intents are structural and ordered`() {
        val open = market.withLegIntent(LegIntent.Open("b1", LegRole.STACK, "parent"))
        assertThat(OrderRequestEvidence.payload(open)["legIntent"])
            .isEqualTo(linkedMapOf("kind" to "Open", "legId" to "b1", "role" to "STACK", "parentLegId" to "parent"))

        val close = market.copy(closesTicket = "42", legIntent = LegIntent.Close(ticket = "42", partial = true))
        assertThat(OrderRequestEvidence.toJson(close))
            .contains("\"legIntent\":{\"kind\":\"Close\",\"legId\":null,\"ticket\":\"42\",\"partial\":true}")

        assertThat(OrderRequestEvidence.payload(market.withLegIntent(LegIntent.Net))["legIntent"])
            .isEqualTo(linkedMapOf("kind" to "Net"))
    }
}
