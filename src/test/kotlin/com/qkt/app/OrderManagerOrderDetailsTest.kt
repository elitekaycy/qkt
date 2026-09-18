package com.qkt.app

import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.LogBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOrderDetailsTest {
    @Test
    fun `orderDetailsFor returns symbol side and quantity for a submitted order`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("0.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )

        val details = om.orderDetailsFor("c1")
        assertThat(details).isNotNull
        assertThat(details!!.symbol).isEqualTo("EURUSD")
        assertThat(details.side).isEqualTo(Side.SELL)
        assertThat(details.quantity).isEqualByComparingTo(Money.of("0.5"))
    }

    @Test
    fun `orderDetailsFor still resolves after the order is rejected`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )
        bus.publish(BrokerEvent.OrderRejected(clientOrderId = "c1", brokerOrderId = null, reason = "test"))

        val details = om.orderDetailsFor("c1")
        assertThat(details).isNotNull
        assertThat(details!!.symbol).isEqualTo("XAUUSD")
        assertThat(details.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `orderDetailsFor returns null for an unknown order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        assertThat(om.orderDetailsFor("never-submitted")).isNull()
    }
}
