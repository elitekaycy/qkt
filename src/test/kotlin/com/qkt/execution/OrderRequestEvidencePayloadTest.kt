package com.qkt.execution

import com.qkt.common.Side
import com.qkt.execution.OrderRequestEvidenceRequests.allRequests
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderRequestEvidencePayloadTest {
    @Test
    fun `payload covers every sealed order request with a fixed type label`() {
        val requests = allRequests()
        val runtimeTypes =
            OrderRequest::class.java.permittedSubclasses
                .map { it.simpleName }
                .toSet()
        val payloadTypes = requests.map { OrderRequestEvidence.payload(it).getValue("orderType") }.toSet()

        assertThat(requests.map { it::class.java.simpleName }.toSet()).isEqualTo(runtimeTypes)
        assertThat(payloadTypes).isEqualTo(runtimeTypes)
        assertThat(requests).hasSize(16)

        val byType = requests.associateBy { OrderRequestEvidence.payload(it).getValue("orderType") }
        assertThat(OrderRequestEvidence.payload(byType.getValue("SteppedStop"))["steps"])
            .isInstanceOf(List::class.java)
        assertThat(OrderRequestEvidence.payload(byType.getValue("TimeTighteningStop")))
            .containsEntry("intervalMs", 5_000L)
            .containsEntry("floorDistance", BigDecimal("1.00"))
        assertThat(OrderRequestEvidence.payload(byType.getValue("Market")))
            .containsEntry("closesTicket", "ticket-1")
            .containsEntry("closesLegId", "leg-1")
            .containsEntry("partialClose", true)
    }

    @Test
    fun `json is deterministic retains nulls escapes strings and renders plain decimals`() {
        val request =
            OrderRequest.Market(
                id = "order\"\\\n\r\t\b\u000c\u0001",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("1E+3"),
                timeInForce = TimeInForce.GTC,
                timestamp = 123L,
            )

        val first = OrderRequestEvidence.toJson(request)
        val second = OrderRequestEvidence.toJson(request)
        val parsed = Json.parseToJsonElement(first).jsonObject

        assertThat(first).isEqualTo(second)
        assertThat(first).startsWith("{\"orderId\":")
        assertThat(first).contains("\"qty\":1000")
        assertThat(first).doesNotContain("1E+3")
        assertThat(first).contains("\"strategyId\":null")
        assertThat(first).contains("\"expiresAt\":null")
        assertThat(first).contains("\"closesTicket\":null")
        assertThat(first).contains("\"closesLegId\":null")
        assertThat(first).contains("\\\"", "\\\\", "\\n", "\\r", "\\t", "\\b", "\\f", "\\u0001")
        assertThat(parsed.getValue("orderId").jsonPrimitive.content).isEqualTo(request.id)
    }
}
