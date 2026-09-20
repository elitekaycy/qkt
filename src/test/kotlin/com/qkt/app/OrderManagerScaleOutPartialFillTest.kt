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

class OrderManagerScaleOutPartialFillTest {
    @Test
    fun `ScaleOut leaves legs dormant until a terminal basis fill`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker =
            FakeBroker(
                bus,
                clock,
                setOf(OrderTypeCapability.LIMIT, OrderTypeCapability.IF_TOUCHED),
            )
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val basis =
            OrderRequest.Limit(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(
            OrderRequest.ScaleOut(
                id = "s1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                basis = basis,
                legs = listOf(ScaleOutLeg(Money.of("110"), Money.of("1"))),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "e1",
                brokerOrderId = "e1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("0.4"),
                cumulativeFilled = Money.of("0.4"),
            ),
        )

        assertThat(om.getOrder("s1-leg-0")).isNull()

        broker.emitFill(basis, price = Money.of("100"), quantity = Money.of("0.6"))

        assertThat(om.getOrder("s1-leg-0")?.state).isEqualTo(OrderState.PENDING)
    }

    @Test
    fun `venue cancellation of a partially filled basis arms exits for the executed quantity`() {
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
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = basis.id,
                brokerOrderId = "residual-10",
                reason = "unfilled residual cancelled",
                strategyId = "alpha",
            ),
        )

        val exits = om.pendingOrders().map { it.request }.filterIsInstance<OrderRequest.IfTouched>()
        assertThat(exits).hasSize(2)
        assertThat(exits).allSatisfy { exit ->
            assertThat(exit.quantity).isEqualByComparingTo("0.2")
            assertThat(exit.closesTicket).isEqualTo("position-9")
            assertThat(exit.partialClose).isTrue()
        }
    }
}
