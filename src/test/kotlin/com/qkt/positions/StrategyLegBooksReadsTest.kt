package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Leg reads answer "nothing" for an unknown strategy, an unknown symbol and an emptied book alike. */
class StrategyLegBooksReadsTest {
    private val symbol = "XAUUSD"

    private fun trackerWithPrimary(): StrategyPositionTracker =
        StrategyPositionTracker().also {
            IntentBook().apply(it, trackerFill("s", "T1", symbol, Side.BUY, "1", "100"))
        }

    @Test
    fun `an unknown strategy or symbol has no legs, tickets or counts`() {
        val tracker = trackerWithPrimary()

        for ((strategy, sym) in listOf("ghost" to symbol, "s" to "EURUSD")) {
            assertThat(tracker.positionFor(strategy, sym)).isNull()
            assertThat(tracker.legBookFor(strategy, sym)).isNull()
            assertThat(tracker.ticketForPrimary(strategy, sym)).isNull()
            assertThat(tracker.openCountFor(strategy, sym)).isZero()
            assertThat(tracker.longCountFor(strategy, sym)).isZero()
            assertThat(tracker.shortCountFor(strategy, sym)).isZero()
            assertThat(tracker.grossFor(strategy, sym)).isEqualByComparingTo("0")
        }
        assertThat(tracker.positionsFor("ghost")).isEmpty()
        assertThat(tracker.allLegsFor("ghost")).isEmpty()
        assertThat(tracker.legById("ghost", "any")).isNull()
        assertThat(tracker.ticketForLeg("ghost", "any")).isNull()
    }

    @Test
    fun `a known strategy finds its legs and tickets by id and misses others`() {
        val tracker = trackerWithPrimary()
        val primary = tracker.allLegsFor("s").single()

        assertThat(tracker.legById("s", primary.legId)).isEqualTo(primary)
        assertThat(tracker.legById("s", "nope")).isNull()
        assertThat(tracker.ticketForLeg("s", primary.legId)).isEqualTo("T1")
        assertThat(tracker.ticketForLeg("s", "nope")).isNull()
        assertThat(tracker.ticketForPrimary("s", symbol)).isEqualTo("T1")
    }

    @Test
    fun `a book with only owned legs has no primary ticket`() {
        val tracker = StrategyPositionTracker()
        val leg =
            PositionLeg("h", symbol, Side.SELL, BigDecimal.ONE, BigDecimal.TEN, 1L, LegRole.INDEPENDENT, brokerTicket = "T9")
        tracker.addIndependentLeg("s", leg)

        assertThat(tracker.ticketForPrimary("s", symbol)).isNull()
        assertThat(tracker.ticketForLeg("s", "h")).isEqualTo("T9")
    }

    @Test
    fun `an emptied book is left out of the net views`() {
        val tracker = trackerWithPrimary()
        val book = tracker.legBookFor("s", symbol)!!
        book.close(book.all().single().legId)

        assertThat(tracker.positionsFor("s")).isEmpty()
        assertThat(tracker.allByStrategy()).containsEntry("s", emptyMap())
    }

    @Test
    fun `drift is the strategies' net less the venue's, zero where neither holds`() {
        val tracker = trackerWithPrimary()
        IntentBook().apply(tracker, trackerFill("other", "T2", "EURUSD", Side.SELL, "3", "1"))
        val venue =
            object : PositionProvider {
                override fun positionFor(symbol: String): Position? =
                    if (symbol == "XAUUSD") Position(symbol, BigDecimal("0.4"), BigDecimal("100")) else null

                override fun allPositions(): Map<String, Position> = emptyMap()
            }

        assertThat(tracker.driftFor(symbol, venue)).isEqualByComparingTo("0.6")
        assertThat(tracker.driftFor("EURUSD", venue)).isEqualByComparingTo("-3")
        assertThat(tracker.driftFor("BTCUSD", venue)).isEqualByComparingTo("0")
    }
}
