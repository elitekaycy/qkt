package com.qkt.app

import com.qkt.app.OrderManagerOtoFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
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

class OrderManagerOtoChildReleaseTest {
    @Test
    fun `OTO submits parent only initially`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val child =
            OrderRequest.Limit(
                id = "c1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("110"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CREATED)
    }

    @Test
    fun `OTO leaves children dormant until a terminal parent fill`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val child = parent.copy(id = "c1", side = Side.SELL, limitPrice = Money.of("110"))
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "p1",
                brokerOrderId = "p1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("0.4"),
                cumulativeFilled = Money.of("0.4"),
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CREATED)

        broker.emitFill(parent, price = Money.of("100"), quantity = Money.of("0.6"))

        assertThat(broker.submits.map { it.id }).containsExactly("p1", "c1")
    }

    @Test
    fun `OTO drops independently sized children when a partial parent residual is cancelled`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val parent =
            OrderRequest.Limit(
                id = "p1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val child = parent.copy(id = "c1", symbol = "Y", side = Side.SELL, quantity = Money.of("3"))
        om.submit(
            OrderRequest.OTO(
                id = "oto1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                parent = parent,
                children = listOf(child),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = parent.id,
                brokerOrderId = "position-9",
                symbol = parent.symbol,
                side = parent.side,
                price = Money.of("100"),
                quantity = Money.of("0.4"),
                cumulativeFilled = Money.of("0.4"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = parent.id,
                brokerOrderId = "residual-10",
                reason = "unfilled residual cancelled",
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactly("p1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }
}
