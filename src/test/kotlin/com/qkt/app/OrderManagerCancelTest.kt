package com.qkt.app

import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.LogBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerCancelTest {
    @Test
    fun `cancel routes to broker for working order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.WORKING)

        om.cancel("c1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `cancelPendingForSymbol cancels venue-resting working orders`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val eurusd =
            OrderRequest.Limit(
                id = "eurusd-resting",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val xauusd =
            OrderRequest.Limit(
                id = "xauusd-resting",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(eurusd)
        om.submit(xauusd)
        assertThat(om.getOrder(eurusd.id)?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder(xauusd.id)?.state).isEqualTo(OrderState.WORKING)

        om.cancelPendingForSymbol("EURUSD")

        assertThat(om.getOrder(eurusd.id)?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(om.getOrder(xauusd.id)?.state).isEqualTo(OrderState.WORKING)
    }

    @Test
    fun `cancel of unknown order is a no-op`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.cancel("does-not-exist")
        assertThat(om.getOrder("does-not-exist")).isNull()
    }
}
