package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A GTD order whose deadline is already at or past the clock at submit time can only
 * round-trip into a venue rejection (MT5 retcode 10022 INVALID_EXPIRATION), so the
 * manager refuses it locally with both clocks in the reason (#811).
 */
class OrderManagerExpiredSubmitTest {
    private fun gtdLimit(expiresAt: Long): OrderRequest.Limit =
        OrderRequest.Limit(
            id = "ord-1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            limitPrice = Money.of("99"),
            timeInForce = TimeInForce.GTD,
            timestamp = 0L,
            expiresAt = expiresAt,
        )

    @Test
    fun `GTD order already expired at submit is rejected locally and never reaches the broker`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val rejections = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { rejections.add(it) }
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack = om.submit(gtdLimit(expiresAt = 500L))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("expired before submit")
        assertThat(broker.submits).isEmpty()
        assertThat(rejections.single().clientOrderId).isEqualTo("ord-1")
        assertThat(rejections.single().reason).contains("expiresAt=500").contains("now=1000")
    }

    @Test
    fun `GTD order expiring exactly at submit time is rejected — the deadline is inclusive`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack = om.submit(gtdLimit(expiresAt = 1_000L))

        assertThat(ack.accepted).isFalse()
        assertThat(broker.submits).isEmpty()
    }

    @Test
    fun `GTD order with a future deadline passes through to the broker`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack = om.submit(gtdLimit(expiresAt = 2_000L))

        assertThat(ack.accepted).isTrue()
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `order without a deadline is never rejected by the expiry check`() {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val ack =
            om.submit(
                OrderRequest.Limit(
                    id = "ord-1",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    limitPrice = Money.of("99"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(broker.submits).hasSize(1)
    }
}
