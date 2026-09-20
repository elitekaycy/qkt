package com.qkt.app

import com.qkt.app.OrderManagerScaleOutFixtures.newBus
import com.qkt.app.OrderManagerScaleOutFixtures.scaleOut
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerScaleOutCancelTest {
    @Test
    fun `explicit ScaleOut cancellation after a partial fill does not arm exits`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val basis =
            OrderRequest.Limit(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )

        om.submit(scaleOut(basis, strategyId = "alpha"))
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = basis.id,
                brokerOrderId = "position-9",
                symbol = basis.symbol,
                side = basis.side,
                price = Money.of("100"),
                quantity = Money.of("0.4"),
                cumulativeFilled = Money.of("0.4"),
                strategyId = "alpha",
            ),
        )

        om.cancel("s1")

        assertThat(om.pendingOrders().map { it.request }.filterIsInstance<OrderRequest.IfTouched>()).isEmpty()
        assertThat(om.getOrder("s1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `cancelling ScaleOut before basis fill cancels basis`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val basis =
            OrderRequest.Market(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("2"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("105"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("s1")

        assertThat(broker.cancels).contains("e1")
    }
}
