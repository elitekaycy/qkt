package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTrailingTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `TrailingStop SELL fires when price drops below trail level after ratcheting up`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val tracker = MarketPriceTracker()
        val om = OrderManager(broker, bus, tracker, clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("5"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        assertThat(broker.submits).isEmpty()

        bus.publish(TickEvent(Tick("X", Money.of("108"), 2L)))
        assertThat(broker.submits).isEmpty()

        bus.publish(TickEvent(Tick("X", Money.of("104.99"), 3L)))
        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
        assertThat(broker.submits.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `TrailingStop SELL stop level never moves down`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("5"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("120"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("90"), 2L)))
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `TrailingStop BUY (cover short) fires when price rises above trail level`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                trailAmount = Money.of("5"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("90"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("96"), 2L)))
        assertThat(broker.submits).hasSize(1)
        assertThat(broker.submits.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `TrailingStop PERCENT mode computes proportional trail`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStop(
                id = "t1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("10"),
                trailMode = TrailMode.PERCENT,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("89"), 1L)))
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `TrailingStopLimit fires Limit at stopLevel minus limitOffset for SELL`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStopLimit(
                id = "t1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("5"),
                trailMode = TrailMode.ABSOLUTE,
                limitOffset = Money.of("0.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))
        assertThat(broker.submits).hasSize(1)
        val submitted = broker.submits.single()
        assertThat(submitted).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat((submitted as OrderRequest.Limit).limitPrice).isEqualByComparingTo(Money.of("104.5"))
    }

    @Test
    fun `TrailingStopLimit ratchets like TrailingStop`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = FakeBroker(bus, clock, capabilities = setOf(OrderTypeCapability.LIMIT))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        bus.publish(TickEvent(Tick("X", Money.of("100"), 0L)))
        om.submit(
            OrderRequest.TrailingStopLimit(
                id = "t1",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("5"),
                trailMode = TrailMode.ABSOLUTE,
                limitOffset = Money.of("0.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(TickEvent(Tick("X", Money.of("120"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("116"), 2L)))
        bus.publish(TickEvent(Tick("X", Money.of("90"), 3L)))
        assertThat(broker.submits).hasSize(1)
    }
}
