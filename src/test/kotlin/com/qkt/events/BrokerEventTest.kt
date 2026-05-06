package com.qkt.events

import com.qkt.common.Money
import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerEventTest {
    @Test
    fun `Accepted carries client and broker order ids`() {
        val e =
            BrokerEvent.OrderAccepted(
                clientOrderId = "c1",
                brokerOrderId = "b1",
            )
        assertThat(e.clientOrderId).isEqualTo("c1")
        assertThat(e.brokerOrderId).isEqualTo("b1")
    }

    @Test
    fun `Rejected carries reason`() {
        val e =
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = null,
                reason = "no price",
            )
        assertThat(e.reason).isEqualTo("no price")
    }

    @Test
    fun `Filled carries price quantity and side`() {
        val e =
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            )
        assertThat(e.price).isEqualByComparingTo(Money.of("1.10"))
        assertThat(e.quantity).isEqualByComparingTo(Money.of("1"))
        assertThat(e.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `PartiallyFilled carries cumulative quantity`() {
        val e =
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("0.3"),
                cumulativeFilled = Money.of("0.3"),
            )
        assertThat(e.cumulativeFilled).isEqualByComparingTo(Money.of("0.3"))
    }

    @Test
    fun `Cancelled carries reason`() {
        val e =
            BrokerEvent.OrderCancelled(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                reason = "user cancel",
            )
        assertThat(e.reason).isEqualTo("user cancel")
    }
}
