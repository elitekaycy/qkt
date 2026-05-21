package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerOcoTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(
        id = id,
        symbol = "X",
        side = side,
        quantity = Money.of("1"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `submits both legs of OCO to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(broker.submits.map { it.id }).containsExactlyInAnyOrder("l1", "l2")
    }

    @Test
    fun `leg1 fill cancels leg2`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l1" }, price = Money.of("100"))

        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).contains("l2")
    }

    @Test
    fun `leg2 fill cancels leg1`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l2" }, price = Money.of("120"))

        assertThat(broker.cancels).contains("l1")
    }

    @Test
    fun `cancelling the OCO group cancels both legs`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("oco1")

        assertThat(broker.cancels).containsExactlyInAnyOrder("l1", "l2")
    }

    @Test
    fun `leg2 rejection cancels the live leg1`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.rejectOrderIds.add("l2")
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack =
            om.submit(
                OrderRequest.StandaloneOCO(
                    id = "oco1",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    leg1 = limit("l1", Side.BUY, "100"),
                    leg2 = limit("l2", Side.SELL, "120"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(broker.cancels).contains("l1")
        assertThat(ack.accepted).isFalse
        assertThat(om.getOrder("oco1")?.state).isEqualTo(OrderState.REJECTED)
    }

    @Test
    fun `leg1 rejection abandons the OCO without placing leg2`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.rejectOrderIds.add("l1")
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack =
            om.submit(
                OrderRequest.StandaloneOCO(
                    id = "oco1",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    leg1 = limit("l1", Side.BUY, "100"),
                    leg2 = limit("l2", Side.SELL, "120"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(broker.submits.map { it.id }).doesNotContain("l2")
        assertThat(ack.accepted).isFalse
        assertThat(om.getOrder("oco1")?.state).isEqualTo(OrderState.REJECTED)
    }
}
