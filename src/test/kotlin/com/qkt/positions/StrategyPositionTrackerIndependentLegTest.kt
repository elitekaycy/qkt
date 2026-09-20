package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerIndependentLegTest {
    private fun fill(
        strategyId: String,
        clientOrderId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
        timestamp: Long = 0L,
    ) = trackerFill(strategyId, clientOrderId, symbol, side, qty, price, timestamp)

    @Test
    fun `independent-open fills coexist as separate legs and do not net`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        // Straddle: a BUY entry and a SELL entry, each registered as its own independent position.
        intents.independentOpen("alpha", "straddle-long", "leg-long")
        intents.independentOpen("alpha", "straddle-short", "leg-short")
        intents.apply(tracker, fill("alpha", "straddle-long", "XAUUSD", Side.BUY, "0.25", "2000"))
        intents.apply(tracker, fill("alpha", "straddle-short", "XAUUSD", Side.SELL, "0.25", "2000"))

        // Two real positions, not one net-zero position.
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(2)
        assertThat(tracker.longCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(tracker.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
        // The net view still nets to zero — back-compat: POSITION.quantity is unchanged.
        assertThat(tracker.positionFor("alpha", "XAUUSD")?.quantity).isEqualByComparingTo("0")

        // The long leg's TP closes ONLY that leg and realizes its own PnL.
        intents.close("alpha", "long-tp", "leg-long")
        val realized = intents.apply(tracker, fill("alpha", "long-tp", "XAUUSD", Side.SELL, "0.25", "2020"))
        assertThat(realized).isEqualByComparingTo("5") // 0.25 * (2020 - 2000)
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(tracker.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
    }

    @Test
    fun `independent-open captures the venue ticket from the fill brokerOrderId`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "straddle-long", "leg-long")
        intents.apply(
            tracker,
            BrokerEvent.OrderFilled(
                clientOrderId = "straddle-long",
                brokerOrderId = "2814861313",
                symbol = "XAUUSD",
                side = Side.BUY,
                price = Money.of("2000"),
                quantity = Money.of("0.25"),
                strategyId = "alpha",
                timestamp = 0L,
            ),
        )
        val leg = tracker.legBookFor("alpha", "XAUUSD")!!.all().single()
        assertThat(leg.legId).isEqualTo("leg-long")
        // The venue ticket is captured so a close can target this exact position by ticket.
        assertThat(leg.brokerTicket).isEqualTo("2814861313")
    }

    @Test
    fun `venue-detected close by ticket realizes the right independent leg`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        // Two independent legs (a straddle) with distinct venue tickets.
        intents.independentOpen("alpha", "e-long", "leg-long")
        intents.independentOpen("alpha", "e-short", "leg-short")
        intents.apply(
            tracker,
            fill("alpha", "e-long", "XAUUSD", Side.BUY, "0.25", "2000").copy(brokerOrderId = "T-LONG"),
        )
        intents.apply(
            tracker,
            fill("alpha", "e-short", "XAUUSD", Side.SELL, "0.25", "2000").copy(brokerOrderId = "T-SHORT"),
        )
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(2)

        // The venue closes the long leg (its attached TP hit) — the poller reports it under the
        // entry id with the position's ticket. It must realize the LONG leg, not net.
        val realized =
            intents.apply(
                tracker,
                fill("alpha", "e-long", "XAUUSD", Side.SELL, "0.25", "2020").copy(brokerOrderId = "T-LONG"),
            )
        assertThat(realized).isEqualByComparingTo("5") // 0.25 * (2020 - 2000)
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(tracker.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
    }

    @Test
    fun `ticketForLeg returns the venue ticket of an independent leg`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "entry-1", "leg-1")
        intents.apply(
            tracker,
            fill("alpha", "entry-1", "XAUUSD", Side.BUY, "0.25", "2000").copy(brokerOrderId = "TICKET-7"),
        )
        assertThat(tracker.ticketForLeg("alpha", "leg-1")).isEqualTo("TICKET-7")
        assertThat(tracker.ticketForLeg("alpha", "no-such-leg")).isNull()
    }

    @Test
    fun `ticketForPrimary returns the venue ticket of a plain position`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(
            tracker,
            fill("alpha", "entry-1", "XAUUSD", Side.BUY, "0.25", "2000")
                .copy(brokerOrderId = "TICKET-PRIMARY"),
        )

        assertThat(tracker.ticketForPrimary("alpha", "XAUUSD")).isEqualTo("TICKET-PRIMARY")
    }
}
