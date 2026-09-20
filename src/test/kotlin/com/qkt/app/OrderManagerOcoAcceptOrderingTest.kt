package com.qkt.app

import com.qkt.app.OrderManagerOcoFixtures.limit
import com.qkt.app.OrderManagerOcoFixtures.newBus
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

class OrderManagerOcoAcceptOrderingTest {
    @Test
    fun `OCO holds leg2 until leg1 is accepted`() {
        // Event-driven sequencing: leg2 is only sent once the venue confirms leg1's
        // acceptance, so a leg1 rejection can never leave a one-legged (directional) OCO.
        // emitAcceptOnSubmit=false models a real venue that acks asynchronously after submit.
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.emitAcceptOnSubmit = false
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

        // Only leg1 is out; leg2 waits for leg1's acceptance.
        assertThat(broker.submits.map { it.id }).containsExactly("l1")

        broker.emitAccept("l1")

        // leg1 accepted -> leg2 now placed.
        assertThat(broker.submits.map { it.id }).containsExactly("l1", "l2")
    }

    @Test
    fun `leg1 fill before leg2 is acknowledged defers the sibling cancel until leg2 is accepted`() {
        // The async edge: leg1 is accepted, leg2 is sent, then leg1 FILLS before leg2's
        // acceptance arrives. leg2's venue ticket isn't known yet, so cancelling it now would
        // no-op at the venue. The cancel must wait for leg2's acceptance and fire then.
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.emitAcceptOnSubmit = false
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
        broker.emitAccept("l1")
        // leg2 is now out but unacknowledged.
        assertThat(broker.submits.map { it.id }).containsExactly("l1", "l2")

        // leg1 fills before leg2's acceptance — the cancel is deferred, not fired now.
        broker.emitFill(broker.submits.first { it.id == "l1" }, price = Money.of("100"))
        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.FILLED)
        assertThat(broker.cancels).doesNotContain("l2")

        // leg2 is finally acknowledged — the deferred cancel fires.
        broker.emitAccept("l2")
        assertThat(broker.cancels).contains("l2")
    }

    @Test
    fun `first positive partial slice defers one sibling cancel until its acceptance`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        broker.emitAcceptOnSubmit = false
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
        broker.emitAccept("l1")

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "l1",
                brokerOrderId = "position-1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("0.04"),
                cumulativeFilled = Money.of("0.04"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "l1",
                brokerOrderId = "position-1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("0.03"),
                cumulativeFilled = Money.of("0.07"),
            ),
        )

        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(broker.cancels).doesNotContain("l2")

        broker.emitAccept("l2")

        assertThat(broker.cancels).containsExactly("l2")
    }

    @Test
    fun `first positive partial slice immediately cancels an acknowledged sibling`() {
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

        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "l1",
                brokerOrderId = "position-1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("0.04"),
                cumulativeFilled = Money.of("0.04"),
            ),
        )

        assertThat(broker.cancels).containsExactly("l2")
        assertThat(om.getOrder("l1")?.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(om.getOrder("l2")?.state).isEqualTo(OrderState.CANCELLED)
    }
}
