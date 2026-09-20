package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketFixtures.armedTrailBracket
import com.qkt.app.OrderManagerAttachedBracketFixtures.attachCaps
import com.qkt.app.OrderManagerAttachedBracketFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerAttachedBracketTrailTest {
    @Test
    fun `engine trail still fires close-by-ticket at the tightened level`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closeTicketFor = { _, exitId -> if (exitId == "b1-sl") "tkt-1" else null },
            )

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        // Arm (MFE 10 ≥ threshold → hwm 110, trail 110−5=105), then drop through (104 ≤ 105).
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        val fired = broker.submits.first { it.id == "b1-sl" } as OrderRequest.Market
        assertThat(fired.closesTicket).isEqualTo("tkt-1")
        assertThat(fired.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `plain bracket armed trail resolves the primary position ticket`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closePrimaryTicketFor = { _, symbol -> if (symbol == "X") "primary-7" else null },
                requireArmedTrailTicket = true,
            )

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        val fired = broker.submits.first { it.id == "b1-sl" } as OrderRequest.Market
        assertThat(fired.closesTicket).isEqualTo("primary-7")
    }

    @Test
    fun `attached armed trail is cancelled when its venue position disappears`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        var ticket: String? = "primary-7"
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closePrimaryTicketFor = { _, _ -> ticket },
                requireArmedTrailTicket = true,
            )

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        ticket = null
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        assertThat(broker.submits.map { it.id }).doesNotContain("b1-sl")
    }
}
