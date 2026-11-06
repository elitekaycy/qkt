package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.DirectPosition
import com.qkt.connector.mt5.Mt5RoutedGateway
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** What `qkt bot` reports for an order or a close the venue decided without a fill. */
class GatewayDirectOutcomeTest {
    private val gateway = Mt5RoutedGateway()
    private val outcome =
        GatewayDirectOutcome(
            "deribit",
            GatewayClient(gateway.url, "k", httpTimeoutMs = 2_000, retryAttempts = 1),
            FixedClock(time = 1_790_835_377_133L),
            pollTimeoutMs = 0L,
            pollMs = 1L,
        )

    @AfterEach
    fun stop() = gateway.close()

    private fun order(
        status: String,
        reason: String? = null,
    ) = """{"client_order_id":"c-1","symbol":"SOL_USDC-PERPETUAL","side":"buy","type":"limit","quantity":"2",""" +
        """"limit_price":"104","time_in_force":"gtc","status":"$status",""" +
        (reason?.let { """"reject_reason":"$it",""" } ?: "") +
        """"created_at":1,"updated_at":1}"""

    @Test
    fun `an order the venue rejected reports the venue's reason`() {
        gateway.on("/v1/orders/c-1", order("rejected", reason = "insufficient margin"))

        val result = outcome.settle("c-1")

        assertThat(result.ok).isFalse()
        assertThat(result.state).isEqualTo("rejected")
        assertThat(result.error).isEqualTo("insufficient margin")
    }

    @Test
    fun `an order rejected without a reason, or cancelled unfilled, says so`() {
        gateway.on("/v1/orders/c-1", order("rejected"), order("cancelled"))

        assertThat(outcome.settle("c-1").error).isEqualTo("rejected by the venue")
        val cancelled = outcome.settle("c-1")
        assertThat(cancelled.error).isEqualTo("order cancelled at the venue with nothing filled")
        assertThat(cancelled.price).isEqualByComparingTo("104")
    }

    @Test
    fun `a close the gateway cannot deliver before the deadline is decided by the venue's fills`() {
        gateway.respond("/v1/positions/close", 503 to """{"error":{"code":"venue_unavailable","message":"down"}}""")
        gateway.on(
            "/v1/deals",
            """{"deals":[{"client_order_id":"close-1","fill_id":"f9","symbol":"SOL_USDC-PERPETUAL",""" +
                """"side":"sell","quantity":"2","price":"105","time":1790835377133}]}""",
        )
        val position =
            DirectPosition(
                ticket = "SOL_USDC-PERPETUAL",
                symbol = "SOL_USDC-PERPETUAL",
                side = "BUY",
                lots = BigDecimal("2"),
                entry = BigDecimal("100"),
                current = null,
                sl = null,
                tp = null,
                profit = null,
                swap = null,
                openedAtMs = null,
                comment = null,
            )

        val result = outcome.close(position, null, "close-1")

        assertThat(result.ok).isTrue()
        assertThat(result.deal).isEqualTo("f9")
        assertThat(result.price).isEqualByComparingTo("105")
        assertThat(gateway.calls("/v1/positions/close")).hasSize(1)
    }
}
