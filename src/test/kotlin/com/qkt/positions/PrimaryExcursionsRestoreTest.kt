package com.qkt.positions

import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.marketdata.Candle
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedExcursion
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A restored primary (BUY 1 @ 100, leg "p") takes saved excursion marks only when they are its own. */
class PrimaryExcursionsRestoreTest {
    private val symbol = "XAUUSD"

    private fun restored(saved: PersistedExcursion?): StrategyPositionTracker {
        val persistor = NoopStatePersistor()
        val leg = PositionLeg("p", symbol, Side.BUY, BigDecimal.ONE, BigDecimal("100"), 10L, LegRole.PRIMARY)
        persistor.saveLegBook("s", symbol, LegBook(symbol).apply { add(leg) })
        if (saved != null) persistor.saveExcursion("s", symbol, saved)
        return StrategyPositionTracker(persistor, clock = { 0L }).apply { preloadFromPersistor("s", symbol) }
    }

    private fun bar(
        high: String,
        low: String,
        startMs: Long,
    ): Candle {
        val mid = BigDecimal("100")
        return Candle(symbol, mid, BigDecimal(high), BigDecimal(low), mid, BigDecimal.ONE, startMs, startMs + 5)
    }

    private fun saved(
        legId: String = "p",
        side: Side = Side.BUY,
        entry: String = "100",
    ) = PersistedExcursion(legId, side, BigDecimal(entry), BigDecimal("5"), BigDecimal("2"), BigDecimal("98"))

    @Test
    fun `marks saved for the same leg, side and entry are restored`() {
        val tracker = restored(saved())

        assertThat(tracker.primaryMfeFor("s", symbol)).isEqualByComparingTo("5")
        assertThat(tracker.primaryMaeFor("s", symbol)).isEqualByComparingTo("2")
    }

    @Test
    fun `marks of another leg, side or entry, or none at all, start the excursion fresh`() {
        val cases = listOf(null, saved(legId = "old"), saved(side = Side.SELL), saved(entry = "101"))

        for (case in cases) {
            val tracker = restored(case)
            assertThat(tracker.primaryMfeFor("s", symbol)).isEqualByComparingTo("0")
            assertThat(tracker.primaryMaeFor("s", symbol)).isEqualByComparingTo("0")
        }
    }

    @Test
    fun `untracked strategies have no excursion and extending them does nothing`() {
        val tracker = restored(null)
        tracker.extendExcursion("ghost", symbol, listOf(bar("110", "90", 20L)))

        assertThat(tracker.primaryMfeFor("ghost", symbol)).isNull()
        assertThat(tracker.primaryMaeFor("s", "EURUSD")).isNull()
    }

    @Test
    fun `downtime bars from before the entry leave the excursion unchanged`() {
        val tracker = restored(null)
        tracker.extendExcursion("s", symbol, listOf(bar("150", "50", 0L)))

        assertThat(tracker.primaryMfeFor("s", symbol)).isEqualByComparingTo("0")
    }

    @Test
    fun `a tracker whose leg vanished from the book is skipped by ticks, bars and splits`() {
        val tracker = restored(null)
        tracker.legBookFor("s", symbol)!!.close("p")
        tracker.onTick(symbol, BigDecimal("120"))
        tracker.extendExcursion("s", symbol, listOf(bar("110", "90", 20L)))
        tracker.restateForSplit("EURUSD", BigDecimal("2"))
        tracker.restateForSplit(symbol, BigDecimal("2"))

        assertThat(tracker.primaryMfeFor("s", symbol)).isEqualByComparingTo("20")
    }

    @Test
    fun `without a primary the oldest parentless non-stack leg is measured`() {
        val tracker = StrategyPositionTracker()
        val intents = IntentBook()
        val parentlessStack = LegIntent.Open("stack-leg", LegRole.STACK, parentLegId = "p0")
        val stackFill = trackerFill("s", "st", symbol, Side.SELL, "1", "100", timestamp = 1L)
        tracker.applyFillDetailed(stackFill, parentlessStack)
        intents.independentOpen("s", "i1", "first")
        intents.independentOpen("s", "i2", "second")
        intents.apply(tracker, trackerFill("s", "i1", symbol, Side.BUY, "1", "100", timestamp = 2L))
        intents.apply(tracker, trackerFill("s", "i2", symbol, Side.SELL, "1", "100", timestamp = 3L))

        tracker.onTick(symbol, BigDecimal("104"))

        assertThat(tracker.primaryMfeFor("s", symbol)).isEqualByComparingTo("4")
    }
}
