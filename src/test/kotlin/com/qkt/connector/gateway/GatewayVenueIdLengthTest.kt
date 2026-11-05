package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** #1643: a close whose engine id is too long for the venue goes out under a short id and its fill comes back. */
internal class GatewayVenueIdLengthTest : GatewayHarness() {
    @Test
    fun `a long timed close id is sent within the venue's limit and its fill reaches the engine order`() {
        val a = Strategy()
        val id = "dsl-soak_o13_bybit_fund--0-exit-close-r945"
        val close =
            OrderRequest.Market(id, symbol, Side.SELL, BigDecimal("0.1"), TimeInForce.GTC, 1_793_664_619_789L, "a")

        broker(session(), a, "a").submit(close)

        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        val sent = fake.submits.single().clientOrderId
        assertThat(sent.length).isLessThanOrEqualTo(45)
        assertThat(sent).startsWith("dsl-soak_o13_bybit_fund-")
        assertThat(a.of<BrokerEvent.OrderAccepted>().single().clientOrderId).isEqualTo(id)

        fake.act { fill(sent, "f1", "0.1", "650", FakeGateway.TIME) }

        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        assertThat(a.of<BrokerEvent.OrderFilled>().single().clientOrderId).isEqualTo(id)
        assertThat(a.of<BrokerEvent.OrderRejected>()).isEmpty()
    }
}
