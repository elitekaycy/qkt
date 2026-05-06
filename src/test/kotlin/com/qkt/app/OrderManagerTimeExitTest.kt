package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTimeExitTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `TimeExit CANCEL cancels target after deadline`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val target =
            OrderRequest.Limit(
                id = "tg1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            )
        om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                target = target,
                deadline = Instant.ofEpochMilli(2_000L),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            ),
        )
        assertThat(broker.submits.map { it.id }).containsExactly("tg1")

        clock.time = 2_500L
        bus.publish(TickEvent(Tick("X", Money.of("99"), 2_500L)))

        assertThat(broker.cancels).contains("tg1")
    }

    @Test
    fun `TimeExit before deadline does nothing`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                target =
                    OrderRequest.Limit(
                        id = "tg1",
                        symbol = "X",
                        side = Side.BUY,
                        quantity = Money.of("1"),
                        limitPrice = Money.of("100"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1_000L,
                    ),
                deadline = Instant.ofEpochMilli(5_000L),
                onExpiry = ExpiryAction.CANCEL,
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            ),
        )

        clock.time = 1_500L
        bus.publish(TickEvent(Tick("X", Money.of("99"), 1_500L)))

        assertThat(broker.cancels).isEmpty()
    }

    @Test
    fun `TimeExit CLOSE_AT_MARKET fires Market on opposite side after deadline if target was filled`() {
        val bus = newBus()
        val clock = FixedClock(time = 1_000L)
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val target =
            OrderRequest.Limit(
                id = "tg1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
            )
        om.submit(
            OrderRequest.TimeExit(
                id = "te1",
                symbol = "X",
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

        clock.time = 2_500L
        bus.publish(TickEvent(Tick("X", Money.of("101"), 2_500L)))

        val closing = broker.submits.lastOrNull { it is OrderRequest.Market && it.id != "tg1" }
        assertThat(closing).isNotNull
        assertThat(closing!!.side).isEqualTo(Side.SELL)
        assertThat(closing.quantity).isEqualByComparingTo(Money.of("1"))
    }
}
