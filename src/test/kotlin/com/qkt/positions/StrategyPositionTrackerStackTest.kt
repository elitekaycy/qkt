package com.qkt.positions

import com.qkt.common.Side
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
    ) = trackerFill(strategyId, clientOrderId, symbol, side, qty, price, timestamp)

    @Test
    fun `pre-registered stack-open fill adds a STACK leg and does not average into primary`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        // Primary BUY at 100
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.avgEntryPrice).isEqualByComparingTo("100")

        // Pre-register stack-open
        intents.stackOpen(
            strategyId = "alpha",
            clientOrderId = "stack-tier0-entry",
            stackLegId = "stack-tier0",
            parentLegId = "primary-1",
        )
        // Stack BUY at 110 — must NOT average into primary
        intents.apply(tracker, fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110"))

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
    fun `pre-registered stack-close fill closes the right leg and returns its realized PnL`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        // Primary + stack setup
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        intents.stackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")
        intents.apply(tracker, fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110"))

        // Pre-register the TP id as a stack-close
        intents.close("alpha", "stack-tier0-tp", "stack-tier0")

        // Stack TP fires at 130 — SELL 0.5
        val realized =
            intents.apply(tracker, fill("alpha", "stack-tier0-tp", "BTCUSDT", Side.SELL, "0.5", "130"))

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
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        intents.stackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")
        intents.apply(
            tracker,
            fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110").copy(brokerOrderId = "T-STACK"),
        )

        // The stack leg carries its venue ticket, so a venue-side close can target it.
        val stackLeg = tracker.legBookFor("alpha", "BTCUSDT")!!.stacks().single()
        assertThat(stackLeg.brokerTicket).isEqualTo("T-STACK")

        // The venue closes the stack position (attached TP hit). The poller reports it under the
        // layer entry id with the position ticket — it must realize the STACK leg, not net into
        // the primary.
        val realized =
            intents.apply(
                tracker,
                fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.SELL, "0.5", "130").copy(brokerOrderId = "T-STACK"),
            )
        assertThat(realized).isEqualByComparingTo("10") // 0.5 * (130 - 110)
        assertThat(tracker.legBookFor("alpha", "BTCUSDT")!!.stacks()).isEmpty()
        // Primary untouched — the close did not net.
        assertThat(tracker.legBookFor("alpha", "BTCUSDT")!!.primary()!!.entryPrice).isEqualByComparingTo("100")
    }

    @Test
    fun `unregistered fill on same symbol falls through to existing PRIMARY averaging logic`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        // Not registered as stack-open — should average into PRIMARY
        intents.apply(tracker, fill("alpha", "extra", "BTCUSDT", Side.BUY, "1.0", "120"))
        // Weighted avg = (100*1 + 120*1) / 2 = 110
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.avgEntryPrice).isEqualByComparingTo("110")
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.quantity).isEqualByComparingTo("2.0")
    }

    @Test
    fun `SELL parent stack close computes realized PnL with inverted price diff`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        // SELL primary at 100
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.SELL, "1.0", "100"))
        // Stack SELL at 90 (price moved favorably for a short)
        intents.stackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")
        intents.apply(tracker, fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.SELL, "0.5", "90"))

        // Stack TP fires at 80 (further favorable for a short) — BUY to close
        intents.close("alpha", "stack-tier0-tp", "stack-tier0")
        val realized =
            intents.apply(tracker, fill("alpha", "stack-tier0-tp", "BTCUSDT", Side.BUY, "0.5", "80"))

        // For a SELL leg: realized = qty * (entry - close) = 0.5 * (90 - 80) = 5
        assertThat(realized).isEqualByComparingTo("5")
    }

    @Test
    fun `registerStackClose for an unknown legId yields zero realized and is a no-op`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        intents.close("alpha", "stack-x-tp", "does-not-exist")
        val realized = intents.apply(tracker, fill("alpha", "stack-x-tp", "BTCUSDT", Side.SELL, "0.5", "120"))
        assertThat(realized).isEqualByComparingTo(BigDecimal.ZERO)
        // Primary untouched
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.avgEntryPrice).isEqualByComparingTo("100")
        assertThat(tracker.positionFor("alpha", "BTCUSDT")?.quantity).isEqualByComparingTo("1.0")
    }
}
