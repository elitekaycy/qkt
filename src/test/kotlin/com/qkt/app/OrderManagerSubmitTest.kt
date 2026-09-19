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

class OrderManagerSubmitTest {
    @Test
    fun `submit Market goes to broker and tracks state through accept`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val tracker = MarketPriceTracker()
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, tracker, clock)

        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val ack = om.submit(req)

        assertThat(ack.accepted).isTrue()
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.WORKING)
        assertThat(managed.brokerOrderId).isEqualTo("c1")
    }

    @Test
    fun `submit Limit goes to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c2")?.state).isEqualTo(OrderState.WORKING)
    }

    @Test
    fun `activeOrders excludes terminal states`() {
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
        om.submit(
            OrderRequest.Market(
                id = "c2",
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

        assertThat(om.activeOrders().map { it.id }).containsExactly("c2")
    }

    @Test
    fun `active entry count is strategy and symbol scoped and excludes protective orders`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                isRiskReducingForHalt = { request -> request.side == Side.SELL },
            )

        fun limit(
            id: String,
            strategyId: String,
            symbol: String,
        ) = OrderRequest.Limit(
            id = id,
            symbol = symbol,
            side = Side.BUY,
            quantity = Money.of("2"),
            limitPrice = Money.of("90"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )

        om.submit(limit("alpha-x", "alpha", "X"))
        om.submit(limit("beta-x", "beta", "X"))
        om.submit(limit("alpha-y", "alpha", "Y"))
        om.submit(
            OrderRequest.Stop(
                id = "alpha-x-protection",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("2"),
                stopPrice = Money.of("80"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        )

        assertThat(om.activeEntryOrderCount("alpha", "X")).isEqualTo(1)
        assertThat(om.activeEntryOrderCount("beta", "X")).isEqualTo(1)
        assertThat(om.activeEntryOrderCount("alpha", "Y")).isEqualTo(1)

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "alpha-x",
                brokerOrderId = "alpha-x",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("90"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
                strategyId = "alpha",
            ),
        )
        assertThat(om.activeEntryOrderCount("alpha", "X")).isEqualTo(1)

        om.cancel("alpha-x")
        assertThat(om.activeEntryOrderCount("alpha", "X")).isZero()
    }
}
