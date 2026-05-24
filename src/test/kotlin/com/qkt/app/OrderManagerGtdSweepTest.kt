package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerGtdSweepTest {
    private fun pendingLimit(expiresAt: Long?): OrderRequest.Limit =
        OrderRequest.Limit(
            id = "ord-1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            limitPrice = Money.of("99"),
            timeInForce = if (expiresAt != null) TimeInForce.GTD else TimeInForce.GTC,
            timestamp = 0L,
            expiresAt = expiresAt,
        )

    /** Minimal broker that claims native GTD support and delegates submit/cancel to a [FakeBroker]. */
    private class GtdNativeBroker(
        private val delegate: FakeBroker,
    ) : Broker {
        override val name: String = "GtdNative"
        override val capabilities: Set<OrderTypeCapability> = delegate.capabilities
        override val supportsNativeGtd: Boolean = true

        override fun submit(request: OrderRequest): SubmitAck = delegate.submit(request)

        override fun cancel(orderId: String) = delegate.cancel(orderId)

        override fun modify(
            orderId: String,
            changes: OrderModification,
        ): SubmitAck = delegate.modify(orderId, changes)
    }

    @Test
    fun `pending GTD order with expired deadline is cancelled on the next tick`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = 500L)) // already past
        clock.time = 2_000L
        bus.publish(TickEvent(Tick("X", BigDecimal("100"), 2_000L)))
        assertThat(cancellations.map { it.clientOrderId }).contains("ord-1")
    }

    @Test
    fun `pending GTD order with future deadline is not cancelled`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = 10_000L))
        bus.publish(TickEvent(Tick("X", BigDecimal("100"), 1_500L)))
        assertThat(cancellations).isEmpty()
    }

    @Test
    fun `pending GTD order is NOT touched by sweep when broker supportsNativeGtd is true`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = GtdNativeBroker(FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT)))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = 500L)) // already past
        clock.time = 2_000L
        bus.publish(TickEvent(Tick("X", BigDecimal("100"), 2_000L)))
        assertThat(cancellations).noneMatch { it.clientOrderId == "ord-1" }
    }

    @Test
    fun `order without expiresAt is never touched by the sweep`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancellations.add(it) }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        om.submit(pendingLimit(expiresAt = null))
        bus.publish(TickEvent(Tick("X", BigDecimal("100"), 9_999_999L)))
        assertThat(cancellations).isEmpty()
    }
}
