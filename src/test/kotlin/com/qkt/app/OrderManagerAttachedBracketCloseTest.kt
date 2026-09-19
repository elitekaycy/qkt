package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.attachCaps
import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.bracket
import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.newBus
import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.venueClose
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.events.TickEvent
import com.qkt.execution.StopLossSpec
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A venue-attached bracket (BRACKET + POSITION_MODIFY) has no resting exit orders: the venue
 * closes the ticket when SL/TP is hit. The bracket wrapper must complete when that close is
 * observed — otherwise every filled bracket stays WORKING for the life of the process, is
 * re-persisted on each state change, and is never reclaimed.
 */
class OrderManagerAttachedBracketCloseTest {
    @Test
    fun `fixed bracket wrapper completes when the venue closes the position`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket(StopLossSpec.Fixed(Money.of("90"))))
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        assertThat(om.activeOrders().map { it.id }).containsExactly("b1")

        venueClose(bus, clock, Money.of("1"))

        assertThat(om.activeOrders()).isEmpty()
    }

    @Test
    fun `partial venue close keeps the wrapper active until the position is flat`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(bracket(StopLossSpec.Fixed(Money.of("90"))))
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        venueClose(bus, clock, Money.of("0.4"))
        assertThat(om.activeOrders().map { it.id }).containsExactly("b1")

        venueClose(bus, clock, Money.of("0.6"))
        assertThat(om.activeOrders()).isEmpty()
    }

    @Test
    fun `armed trail wrapper and its held engine stop complete on venue close`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            bracket(StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10"))),
        )
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        assertThat(om.activeOrders().map { it.id }).containsExactlyInAnyOrder("b1", "b1-sl")

        venueClose(bus, clock, Money.of("1"))

        assertThat(om.activeOrders()).isEmpty()
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `armed trail wrapper completes when its held engine stop fires and fills`() {
        // The engine-held stop fires a close-by-ticket and the venue fills it. That fill is the
        // position's exit exactly like a venue-side close is: the wrapper must complete and
        // release its exposure instead of staying pending until the next restart retires it.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            bracket(StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10"))),
        )
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        assertThat(om.activeOrders().map { it.id }).containsExactlyInAnyOrder("b1", "b1-sl")

        // Arm (MFE 10 ≥ threshold → trail at 110−5=105), then drop through the trail.
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))
        val close = broker.submits.first { it.id == "b1-sl" }
        broker.emitFill(close, price = Money.of("104"))

        assertThat(om.activeOrders()).isEmpty()
    }
}
