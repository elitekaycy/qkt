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
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Armed-trail brackets on a venue that can both attach SL/TP to an order and modify an open
 * position's SL/TP (BRACKET + POSITION_MODIFY) ship the bracket whole so the venue holds the
 * pre-arm SL + TP on the position — on a hedging account the venue closes that ticket instead
 * of a resting exit order opening a counter. The bracket is keyed under the ENTRY id so ticket
 * capture and close attribution still flow through the existing entry.id paths.
 */
class OrderManagerAttachedBracketTest {
    private fun newBus(clock: FixedClock): EventBus = EventBus(clock, MonotonicSequenceGenerator())

    private fun armedTrailBracket(): OrderRequest.Bracket {
        val entry =
            OrderRequest.Stop(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        return OrderRequest.Bracket(
            id = "b1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry = entry,
            takeProfit = Money.of("120"),
            stopLoss = StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10")),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )
    }

    private val attachCaps =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.BRACKET,
            OrderTypeCapability.POSITION_MODIFY,
        )

    @Test
    fun `ships one native bracket keyed under the entry id`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())

        assertThat(broker.submits).hasSize(1)
        val shipped = broker.submits.single()
        assertThat(shipped).isInstanceOf(OrderRequest.Bracket::class.java)
        assertThat(shipped.id).isEqualTo("e1")
    }

    @Test
    fun `does not rest separate exit orders on the venue when the entry fills`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        // The TP/SL ride the venue order; the engine trail is held, not sent — so no extra
        // resting exit orders that would open counters on a hedging account.
        assertThat(broker.submits).hasSize(1)
    }

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
    fun `without POSITION_MODIFY it falls back to decomposed resting exits`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val caps = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())
        // Fallback submits the entry under its own id first...
        assertThat(broker.submits.single().id).isEqualTo("e1")
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        // ...then rests the TP limit (the SL ArmedTrailingStop is engine-held).
        assertThat(broker.submits.map { it::class.simpleName }).contains("Limit")
    }
}
