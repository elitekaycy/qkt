package com.qkt.app

import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.LogBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerBrokerEventStateTest {
    @Test
    fun `OrderFilled event transitions state to FILLED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("1"))
        assertThat(managed.avgFillPrice).isEqualByComparingTo(Money.of("1.10"))
    }

    @Test
    fun `OrderRejected event transitions state to REJECTED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                reason = "no price",
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.REJECTED)
    }

    @Test
    fun `OrderPartiallyFilled accumulates cumulative fill quantity`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("3"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("2"),
                cumulativeFilled = Money.of("3"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("3"))
    }
}
