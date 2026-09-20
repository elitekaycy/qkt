package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketFixtures.armedTrailBracket
import com.qkt.app.OrderManagerAttachedBracketFixtures.attachCaps
import com.qkt.app.OrderManagerAttachedBracketFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
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
    fun `fixed bracket on an attach venue ships native keyed under the entry id`() {
        // The orchestrator stack-tier shape: a market entry with a fixed SL/TP. Keying the venue
        // order under the entry id is what lets registerStackOpen (which keys on entry.id) match
        // the fill, so the STACK leg is tracked on a hedging venue — not just in backtest.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val bracket =
            OrderRequest.Bracket(
                id = "stk-tier0",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry =
                    OrderRequest.Market(
                        id = "stk-tier0-entry",
                        symbol = "X",
                        side = Side.BUY,
                        quantity = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                    ),
                takeProfit = Money.of("130"),
                stopLoss = StopLossSpec.Fixed(Money.of("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(bracket)

        assertThat(broker.submits).hasSize(1)
        val shipped = broker.submits.single()
        assertThat(shipped).isInstanceOf(OrderRequest.Bracket::class.java)
        assertThat(shipped.id).isEqualTo("stk-tier0-entry")
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
