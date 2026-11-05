package com.qkt.connector.gateway

import com.qkt.broker.BookedDeals
import com.qkt.broker.BookedFill
import com.qkt.broker.VenueLeg
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1643: a timed close of `soak_o13_bybit_fund` went to the gateway as `dsl-soak_o13_bybit_fund--0-exit-close.mvzxlpio`
 * (46 characters) and Bybit, which takes 45, refused it and every retry for two days. Every id qkt sends a gateway
 * now fits the strictest venue's limit and still names its strategy and maps back to its engine order.
 */
class GatewayClientIdsTest {
    private val submitted = 1_793_664_619_789L

    private fun order(id: String) =
        OrderRequest.Market(id, "X", Side.SELL, BigDecimal("0.03"), TimeInForce.GTC, submitted, "soak_o13_bybit_fund")

    @Test
    fun `the soak's timed close and its retries fit Bybit's 45 characters and map back to the engine order`() {
        for (id in listOf("dsl-soak_o13_bybit_fund--0-exit-close", "dsl-soak_o13_bybit_fund--0-exit-close-r945")) {
            val sent = GatewayClientIds.of(order(id))

            assertThat(sent.length).isLessThanOrEqualTo(45)
            assertThat(sent).startsWith("dsl-soak_o13_bybit_fund-").endsWith(".${submitted.toString(36)}")
            assertThat(GatewayClientIds.engineId(sent)).isEqualTo(id)
        }
    }

    @Test
    fun `an id that fits is sent as it always was`() {
        assertThat(GatewayClientIds.of(order("dsl-s--4"))).isEqualTo("dsl-s--4.${submitted.toString(36)}")
        assertThat(GatewayClientIds.engineId("dsl-s--4.mg8q2kv1")).isEqualTo("dsl-s--4")
    }

    @Test
    fun `a shortened id is the same for the same engine order and differs between orders`() {
        val first = GatewayClientIds.of(order("dsl-a_long_strategy_name--12-exit-close-r1"))

        assertThat(GatewayClientIds.of(order("dsl-a_long_strategy_name--12-exit-close-r1"))).isEqualTo(first)
        assertThat(GatewayClientIds.of(order("dsl-a_long_strategy_name--12-exit-close-r2"))).isNotEqualTo(first)
    }

    @Test
    fun `an event under a shortened id reaches the engine under its own id`() {
        val id = "dsl-a_long_strategy_name--3-exit-close"
        val sent = GatewayClientIds.of(order(id))

        val event = GatewayClientIds.toEngine(BrokerEvent.OrderRejected(sent, null, "venue_rejected: no"))

        assertThat(event.clientOrderId).isEqualTo(id)
    }

    @Test
    fun `a fill restored after a restart maps its venue id back to its engine order`() {
        val id = "dsl-restored_strategy_name--7-exit-close-r3"
        val sent = GatewayClientIds.shortened(id) + ".mw3rfo1d"
        val fill = BookedFill(id, "f1", VenueLeg("OUT", "p", null), BigDecimal.ONE, "s", 5L)

        GatewayFillLegs().restore(BookedDeals(listOf(fill), emptyList()))

        assertThat(GatewayClientIds.engineId(sent)).isEqualTo(id)
    }
}
