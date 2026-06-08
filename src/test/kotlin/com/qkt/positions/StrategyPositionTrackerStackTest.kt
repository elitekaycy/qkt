package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerStackTest {
    private fun fill(
        strategyId: String,
        clientOrderId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
        timestamp: Long = 0L,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = clientOrderId,
        brokerOrderId = clientOrderId,
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = timestamp,
    )

    @Test
    fun `pre-registered stack-open fill adds a STACK leg and does not average into primary`() {
        val tracker = StrategyPositionTracker()
        // Primary BUY at 100
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.avgEntryPrice).isEqualByComparingTo("100")

        // Pre-register stack-open
        tracker.registerStackOpen(
            strategyId = "alpha",
            clientOrderId = "stack-tier0-entry",
            stackLegId = "stack-tier0",
            parentLegId = "primary-1",
        )
        // Stack BUY at 110 — must NOT average into primary
        tracker.applyFill(fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110"))

        // Primary leg untouched: entry still 100, qty still 1.0
        val book = tracker.legBookFor("alpha", "BTCUSDT")!!
        val primary = book.primary()!!
        assertThat(primary.entryPrice).isEqualByComparingTo("100")
        assertThat(primary.quantity).isEqualByComparingTo("1.0")
        assertThat(primary.role).isEqualTo(LegRole.PRIMARY)

        // New STACK leg present
        val stacks = book.stacks()
        assertThat(stacks).hasSize(1)
        assertThat(stacks[0].legId).isEqualTo("stack-tier0")
        assertThat(stacks[0].entryPrice).isEqualByComparingTo("110")
        assertThat(stacks[0].quantity).isEqualByComparingTo("0.5")
        assertThat(stacks[0].parentLegId).isEqualTo("primary-1")
    }

    @Test
    fun `forgetPending drops a stack-open intent so a later fill adds no STACK leg`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        tracker.registerStackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")

        // The stack-open order is cancelled (e.g. its OCO sibling filled) — forget the intent.
        tracker.forgetPending("alpha", "stack-tier0-entry")

        // A stray fill for that now-cancelled order must not resurrect the stack leg.
        tracker.applyFill(fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110"))

        assertThat(tracker.legBookFor("alpha", "BTCUSDT")!!.stacks()).isEmpty()
    }

    @Test
    fun `pre-registered stack-close fill closes the right leg and returns its realized PnL`() {
        val tracker = StrategyPositionTracker()
        // Primary + stack setup
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        tracker.registerStackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")
        tracker.applyFill(fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110"))

        // Pre-register the TP id as a stack-close
        tracker.registerStackClose("alpha", "stack-tier0-tp", "stack-tier0")

        // Stack TP fires at 130 — SELL 0.5
        val realized =
            tracker.applyFill(fill("alpha", "stack-tier0-tp", "BTCUSDT", Side.SELL, "0.5", "130"))

        // Realized PnL = 0.5 * (130 - 110) = 10
        assertThat(realized).isEqualByComparingTo("10")
        // Primary untouched
        val book = tracker.legBookFor("alpha", "BTCUSDT")!!
        assertThat(book.primary()!!.entryPrice).isEqualByComparingTo("100")
        // Stack leg removed
        assertThat(book.stacks()).isEmpty()
    }

    @Test
    fun `stack-open captures the venue ticket and a venue close by ticket realizes the STACK leg`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        tracker.registerStackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")
        tracker.applyFill(
            fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110").copy(brokerOrderId = "T-STACK"),
        )

        // The stack leg carries its venue ticket, so a venue-side close can target it.
        val stackLeg = tracker.legBookFor("alpha", "BTCUSDT")!!.stacks().single()
        assertThat(stackLeg.brokerTicket).isEqualTo("T-STACK")

        // The venue closes the stack position (attached TP hit). The poller reports it under the
        // layer entry id with the position ticket — it must realize the STACK leg, not net into
        // the primary.
        val realized =
            tracker.applyFill(
                fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.SELL, "0.5", "130").copy(brokerOrderId = "T-STACK"),
            )
        assertThat(realized).isEqualByComparingTo("10") // 0.5 * (130 - 110)
        assertThat(tracker.legBookFor("alpha", "BTCUSDT")!!.stacks()).isEmpty()
        // Primary untouched — the close did not net.
        assertThat(tracker.legBookFor("alpha", "BTCUSDT")!!.primary()!!.entryPrice).isEqualByComparingTo("100")
    }

    @Test
    fun `independent-open fills coexist as separate legs and do not net`() {
        val tracker = StrategyPositionTracker()
        // Straddle: a BUY entry and a SELL entry, each registered as its own independent position.
        tracker.registerIndependentOpen("alpha", "straddle-long", "leg-long")
        tracker.registerIndependentOpen("alpha", "straddle-short", "leg-short")
        tracker.applyFill(fill("alpha", "straddle-long", "XAUUSD", Side.BUY, "0.25", "2000"))
        tracker.applyFill(fill("alpha", "straddle-short", "XAUUSD", Side.SELL, "0.25", "2000"))

        // Two real positions, not one net-zero position.
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(2)
        assertThat(tracker.longCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(tracker.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
        // The net view still nets to zero — back-compat: POSITION.quantity is unchanged.
        assertThat(tracker.positionFor("alpha", "XAUUSD")?.quantity).isEqualByComparingTo("0")

        // The long leg's TP closes ONLY that leg and realizes its own PnL.
        tracker.registerStackClose("alpha", "long-tp", "leg-long")
        val realized = tracker.applyFill(fill("alpha", "long-tp", "XAUUSD", Side.SELL, "0.25", "2020"))
        assertThat(realized).isEqualByComparingTo("5") // 0.25 * (2020 - 2000)
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(tracker.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
    }

    @Test
    fun `independent-open captures the venue ticket from the fill brokerOrderId`() {
        val tracker = StrategyPositionTracker()
        tracker.registerIndependentOpen("alpha", "straddle-long", "leg-long")
        tracker.applyFill(
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
        // Two independent legs (a straddle) with distinct venue tickets.
        tracker.registerIndependentOpen("alpha", "e-long", "leg-long")
        tracker.registerIndependentOpen("alpha", "e-short", "leg-short")
        tracker.applyFill(fill("alpha", "e-long", "XAUUSD", Side.BUY, "0.25", "2000").copy(brokerOrderId = "T-LONG"))
        tracker.applyFill(fill("alpha", "e-short", "XAUUSD", Side.SELL, "0.25", "2000").copy(brokerOrderId = "T-SHORT"))
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(2)

        // The venue closes the long leg (its attached TP hit) — the poller reports it under the
        // entry id with the position's ticket. It must realize the LONG leg, not net.
        val realized =
            tracker.applyFill(
                fill("alpha", "e-long", "XAUUSD", Side.SELL, "0.25", "2020").copy(brokerOrderId = "T-LONG"),
            )
        assertThat(realized).isEqualByComparingTo("5") // 0.25 * (2020 - 2000)
        assertThat(tracker.openCountFor("alpha", "XAUUSD")).isEqualTo(1)
        assertThat(tracker.shortCountFor("alpha", "XAUUSD")).isEqualTo(1)
    }

    @Test
    fun `ticketForLeg returns the venue ticket of an independent leg`() {
        val tracker = StrategyPositionTracker()
        tracker.registerIndependentOpen("alpha", "entry-1", "leg-1")
        tracker.applyFill(fill("alpha", "entry-1", "XAUUSD", Side.BUY, "0.25", "2000").copy(brokerOrderId = "TICKET-7"))
        assertThat(tracker.ticketForLeg("alpha", "leg-1")).isEqualTo("TICKET-7")
        assertThat(tracker.ticketForLeg("alpha", "no-such-leg")).isNull()
    }

    @Test
    fun `unregistered fill on same symbol falls through to existing PRIMARY averaging logic`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        // Not registered as stack-open — should average into PRIMARY
        tracker.applyFill(fill("alpha", "extra", "BTCUSDT", Side.BUY, "1.0", "120"))
        // Weighted avg = (100*1 + 120*1) / 2 = 110
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.avgEntryPrice).isEqualByComparingTo("110")
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.quantity).isEqualByComparingTo("2.0")
    }

    @Test
    fun `SELL parent stack close computes realized PnL with inverted price diff`() {
        val tracker = StrategyPositionTracker()
        // SELL primary at 100
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.SELL, "1.0", "100"))
        // Stack SELL at 90 (price moved favorably for a short)
        tracker.registerStackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")
        tracker.applyFill(fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.SELL, "0.5", "90"))

        // Stack TP fires at 80 (further favorable for a short) — BUY to close
        tracker.registerStackClose("alpha", "stack-tier0-tp", "stack-tier0")
        val realized =
            tracker.applyFill(fill("alpha", "stack-tier0-tp", "BTCUSDT", Side.BUY, "0.5", "80"))

        // For a SELL leg: realized = qty * (entry - close) = 0.5 * (90 - 80) = 5
        assertThat(realized).isEqualByComparingTo("5")
    }

    @Test
    fun `registerStackClose for an unknown legId yields zero realized and is a no-op`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        tracker.registerStackClose("alpha", "stack-x-tp", "does-not-exist")
        val realized = tracker.applyFill(fill("alpha", "stack-x-tp", "BTCUSDT", Side.SELL, "0.5", "120"))
        assertThat(realized).isEqualByComparingTo(BigDecimal.ZERO)
        // Primary untouched
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.avgEntryPrice).isEqualByComparingTo("100")
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.quantity).isEqualByComparingTo("1.0")
    }
}
