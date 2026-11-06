package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.LegIntent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StrategyPositionTrackerEdgeTest {
    private val symbol = "XAUUSD"

    private fun leg(
        id: String,
        side: Side,
        role: LegRole,
    ) = PositionLeg(id, symbol, side, BigDecimal.ONE, BigDecimal.TEN, 1L, role, brokerTicket = "T")

    @Test
    fun `a netting fill applied without a cumulative books its quantity`() {
        val tracker = StrategyPositionTracker()

        val applied = tracker.applyFillDetailed(trackerFill("s", "c1", symbol, Side.BUY, "1", "100"), LegIntent.Net)

        assertThat(applied.realized).isEqualByComparingTo("0")
        assertThat(tracker.positionFor("s", symbol)?.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `a fill without a strategy books nothing`() {
        val tracker = StrategyPositionTracker()

        val applied = tracker.applyFillDetailed(trackerFill("", "c1", symbol, Side.BUY, "1", "100"), LegIntent.Net)

        assertThat(applied.realized).isEqualByComparingTo("0")
        assertThat(tracker.allByStrategy()).isEmpty()
    }

    @Test
    fun `an execution that reaches the ledger without a leg intent is a wiring error`() {
        val tracker = StrategyPositionTracker()

        assertThatThrownBy {
            tracker.applyFillDetailed(trackerFill("s", "c9", symbol, Side.BUY, "1", "100"), LegIntent.Unplanned)
        }.hasMessage("execution c9 for s reached the ledger unplanned")
    }

    @Test
    fun `a second execution of an owned leg under another venue ticket is refused`() {
        val tracker = StrategyPositionTracker()
        val open = LegIntent.Open("leg-1", LegRole.INDEPENDENT)
        tracker.applyFillDetailed(trackerFill("s", "t1", symbol, Side.BUY, "1", "100"), open)

        assertThatThrownBy {
            tracker.applyFillDetailed(trackerFill("s", "t2", symbol, Side.BUY, "1", "101"), open)
        }.hasMessage("owned leg leg-1 changed broker ticket across execution slices")
        assertThat(tracker.legBookFor("s", symbol)?.leg("leg-1")?.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `an owned leg found by ticket skips the primary that carries the same ticket`() {
        val book = LegBook(symbol)
        book.add(leg("p", Side.BUY, LegRole.PRIMARY))
        assertThat(book.ownedLegByTicket("T")).isNull()

        book.add(leg("i", Side.SELL, LegRole.INDEPENDENT))
        assertThat(book.ownedLegByTicket("T")?.legId).isEqualTo("i")
        assertThat(book.ownedLegByTicket("other")).isNull()
    }

    @Test
    fun `gross exposure of a symbol the strategy never held is zero`() {
        assertThat(StrategyPositionTracker().grossFor("s", symbol)).isEqualByComparingTo("0")
    }

    @Test
    fun `an unowned venue correction held by several strategies is not applied`() {
        val tracker = StrategyPositionTracker()
        IntentBook().apply(tracker, trackerFill("a", "c1", symbol, Side.BUY, "1", "100"))
        IntentBook().apply(tracker, trackerFill("b", "c2", symbol, Side.BUY, "1", "100"))

        val changed = tracker.reconcileNet(symbol, BigDecimal("5"), BigDecimal("100"), 3L, "venue", null, null)

        assertThat(changed).isFalse()
        assertThat(tracker.positionFor("a", symbol)?.quantity).isEqualByComparingTo("1")
        assertThat(tracker.positionFor("b", symbol)?.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `a flat venue figure without a ticket clears the owner's book`() {
        val tracker = StrategyPositionTracker()
        IntentBook().apply(tracker, trackerFill("a", "c1", symbol, Side.BUY, "1", "100"))

        val changed = tracker.reconcileNet(symbol, BigDecimal.ZERO, BigDecimal("100"), 3L, "venue", null, "a")

        assertThat(changed).isTrue()
        assertThat(tracker.positionFor("a", symbol)).isNull()
    }
}
