package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerOwnedSliceTest {
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
    fun `partial stack-open slices remain one owned leg through terminal fill`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "primary", "BTCUSDT", Side.BUY, "1", "90"))
        intents.stackOpen("alpha", "stack-entry", "stack-leg", "primary")

        intents.apply(
            tracker,
            fill("alpha", "stack-entry", "BTCUSDT", Side.BUY, "0.04", "100")
                .copy(brokerOrderId = "POSITION-7"),
        )
        intents.apply(
            tracker,
            fill("alpha", "stack-entry", "BTCUSDT", Side.BUY, "0.03", "101")
                .copy(brokerOrderId = "POSITION-7"),
        )
        intents.apply(
            tracker,
            fill("alpha", "stack-entry", "BTCUSDT", Side.BUY, "0.03", "102")
                .copy(brokerOrderId = "POSITION-7"),
        )

        val book = tracker.legBookFor("alpha", "BTCUSDT")!!
        assertThat(book.primary()!!.quantity).isEqualByComparingTo("1")
        val stack = book.stacks().single()
        assertThat(stack.legId).isEqualTo("stack-leg")
        assertThat(stack.quantity).isEqualByComparingTo("0.10")
        assertThat(stack.entryPrice).isEqualByComparingTo("100.9")
        assertThat(stack.brokerTicket).isEqualTo("POSITION-7")
    }

    @Test
    fun `partial independent-open slices never fall through to primary ownership`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "entry", "independent-leg")

        intents.apply(
            tracker,
            fill("alpha", "entry", "XAUUSD", Side.BUY, "0.04", "2400")
                .copy(brokerOrderId = "POSITION-9"),
        )
        intents.apply(
            tracker,
            fill("alpha", "entry", "XAUUSD", Side.BUY, "0.03", "2401")
                .copy(brokerOrderId = "POSITION-9"),
        )
        intents.apply(
            tracker,
            fill("alpha", "entry", "XAUUSD", Side.BUY, "0.03", "2402")
                .copy(brokerOrderId = "POSITION-9"),
        )

        val book = tracker.legBookFor("alpha", "XAUUSD")!!
        assertThat(book.primary()).isNull()
        val leg = book.all().single()
        assertThat(leg.role).isEqualTo(LegRole.INDEPENDENT)
        assertThat(leg.legId).isEqualTo("independent-leg")
        assertThat(leg.quantity).isEqualByComparingTo("0.10")
        assertThat(leg.entryPrice).isEqualByComparingTo("2400.9")
        assertThat(leg.brokerTicket).isEqualTo("POSITION-9")
    }

    @Test
    fun `partial owned-close slices retain close intent until terminal fill`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "primary", "BTCUSDT", Side.BUY, "1", "90"))
        intents.stackOpen("alpha", "stack-entry", "stack-leg", "primary")
        intents.apply(tracker, fill("alpha", "stack-entry", "BTCUSDT", Side.BUY, "0.10", "100"))
        intents.close("alpha", "stack-exit", "stack-leg")

        val realized =
            listOf(
                intents.apply(tracker, fill("alpha", "stack-exit", "BTCUSDT", Side.SELL, "0.04", "110")),
                intents.apply(tracker, fill("alpha", "stack-exit", "BTCUSDT", Side.SELL, "0.03", "110")),
                intents.apply(tracker, fill("alpha", "stack-exit", "BTCUSDT", Side.SELL, "0.03", "110")),
            ).fold(BigDecimal.ZERO, BigDecimal::add)

        val book = tracker.legBookFor("alpha", "BTCUSDT")!!
        assertThat(realized).isEqualByComparingTo("1")
        assertThat(book.stacks()).isEmpty()
        assertThat(book.primary()!!.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `a cancelled residual leaves the executed owned quantity in place`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "entry", "independent-leg")
        intents.apply(
            tracker,
            fill("alpha", "entry", "XAUUSD", Side.BUY, "0.04", "2400")
                .copy(brokerOrderId = "POSITION-9"),
        )

        // The unfilled residual is cancelled at the venue; the order's intent is still the
        // order's, so nothing about the executed quantity changes.
        val book = tracker.legBookFor("alpha", "XAUUSD")!!
        assertThat(book.primary()).isNull()
        assertThat(book.all().single().quantity).isEqualByComparingTo("0.04")
        assertThat(book.all().single().brokerTicket).isEqualTo("POSITION-9")
    }

    @Test
    fun `a later slice for the same order extends its owned leg`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.independentOpen("alpha", "entry", "independent-leg")
        intents.apply(
            tracker,
            fill("alpha", "entry", "XAUUSD", Side.BUY, "0.04", "2400")
                .copy(brokerOrderId = "POSITION-9"),
        )

        intents.apply(
            tracker,
            fill("alpha", "entry", "XAUUSD", Side.BUY, "0.06", "2401")
                .copy(brokerOrderId = "POSITION-9"),
        )

        val book = tracker.legBookFor("alpha", "XAUUSD")!!
        assertThat(book.primary()).isNull()
        val leg = book.all().single()
        assertThat(leg.legId).isEqualTo("independent-leg")
        assertThat(leg.quantity).isEqualByComparingTo("0.10")
        assertThat(leg.entryPrice).isEqualByComparingTo("2400.6")
    }

    @Test
    fun `a stack-open order that was cancelled still books its leg if it fills anyway`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        intents.apply(tracker, fill("alpha", "primary-1", "BTCUSDT", Side.BUY, "1.0", "100"))
        intents.stackOpen("alpha", "stack-tier0-entry", "stack-tier0", "primary-1")

        // The stack-open order is cancelled (its OCO sibling filled) but a whipsaw fills it
        // anyway. The order still says what its fill means, so the leg is booked — nothing was
        // forgotten on cancel (#1098).
        intents.apply(tracker, fill("alpha", "stack-tier0-entry", "BTCUSDT", Side.BUY, "0.5", "110"))

        val stacks = tracker.legBookFor("alpha", "BTCUSDT")!!.stacks()
        assertThat(stacks).hasSize(1)
        assertThat(stacks.single().legId).isEqualTo("stack-tier0")
    }
}
