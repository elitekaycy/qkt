package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.LogBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerGcTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun market(id: String) =
        OrderRequest.Market(
            id = id,
            symbol = "EURUSD",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )

    private fun limit(
        id: String,
        side: Side,
        price: String,
    ) = OrderRequest.Limit(
        id = id,
        symbol = "EURUSD",
        side = side,
        quantity = Money.of("1"),
        limitPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    private fun fill(
        id: String,
        price: String,
    ): BrokerEvent.OrderFilled =
        BrokerEvent.OrderFilled(
            clientOrderId = id,
            brokerOrderId = id,
            symbol = "EURUSD",
            side = Side.BUY,
            price = Money.of(price),
            quantity = Money.of("1"),
            timestamp = 0L,
        )

    private fun tick(price: String): TickEvent = TickEvent(Tick("EURUSD", Money.of(price), 1L))

    @Test
    fun `a finished unreferenced order is reclaimed after a tick`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(market("c1"))
        bus.publish(fill("c1", "1.10"))
        assertThat(om.getOrder("c1")).isNotNull()

        bus.publish(tick("1.11"))

        assertThat(om.getOrder("c1")).isNull()
    }

    @Test
    fun `a filled order held by a pending timed-exit is retained until the exit fires`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val target =
            OrderRequest.Limit(
                id = "tg1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            )
        om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = target,
                deadline = Instant.ofEpochMilli(2_000L),
                onExpiry = ExpiryAction.CLOSE_AT_MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            ),
        )
        broker.emitFill(target, price = Money.of("100"))
        assertThat(om.getOrder("tg1")?.state).isEqualTo(OrderState.FILLED)

        // Tick before the deadline: the time-exit still references the filled target,
        // so it must survive the GC drain.
        clock.time = 1_500L
        bus.publish(TickEvent(Tick("EURUSD", Money.of("101"), 1_500L)))
        assertThat(om.getOrder("tg1")).isNotNull()

        // Past the deadline: the exit fires (removing the timeExits entry) and the target
        // is no longer referenced. A subsequent tick reclaims it.
        clock.time = 2_500L
        bus.publish(TickEvent(Tick("EURUSD", Money.of("101"), 2_500L)))
        bus.publish(TickEvent(Tick("EURUSD", Money.of("101"), 2_600L)))

        assertThat(om.getOrder("tg1")).isNull()
    }

    @Test
    fun `an OCO sibling is cancelled correctly even though GC runs between fill and cancel`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.StandaloneOCO(
                id = "oco1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                leg1 = limit("l1", Side.BUY, "100"),
                leg2 = limit("l2", Side.SELL, "120"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.emitFill(broker.submits.first { it.id == "l1" }, price = Money.of("100"))

        // The fill cancelled the sibling synchronously, before any GC drain.
        assertThat(om.getOrder("l2")?.state).isEqualTo(OrderState.CANCELLED)

        // Drive ticks to run the GC. Both legs are terminal and unreferenced once the OCO
        // parent is also terminal, so they are reclaimed.
        bus.publish(TickEvent(Tick("EURUSD", Money.of("105"), 1L)))
        bus.publish(TickEvent(Tick("EURUSD", Money.of("105"), 2L)))

        assertThat(om.getOrder("l1")).isNull()
        assertThat(om.getOrder("l2")).isNull()
    }
}
