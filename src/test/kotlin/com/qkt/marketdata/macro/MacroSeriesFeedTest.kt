package com.qkt.marketdata.macro

import com.qkt.marketdata.store.macro.MacroPoint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MacroSeriesFeedTest {
    private fun utcMs(
        date: LocalDate,
        hour: Int,
    ): Long = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** 16:30 New York on [date]: 21:30 UTC in early March 2024, before daylight saving starts. */
    private fun released(date: LocalDate): Long = date.atTime(21, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val points =
        listOf(
            MacroPoint(LocalDate.of(2024, 3, 1), BigDecimal("4.00")), // Fri -> released Mon 16:30 NY
            MacroPoint(LocalDate.of(2024, 3, 4), BigDecimal("4.10")), // Mon -> released Tue 16:30 NY
            MacroPoint(LocalDate.of(2024, 3, 5), BigDecimal("4.20")), // Tue -> released Wed 16:30 NY
        )

    private fun drain(feed: MacroSeriesFeed): List<Pair<Long, BigDecimal>> =
        buildList {
            while (true) {
                val t = feed.next() ?: break
                add(t.timestamp to t.price)
            }
        }

    @Test
    fun `mondays value is not visible until its tuesday release (no look-ahead)`() {
        // Window: Monday 00:00 .. Tuesday 12:00 — before Monday's value releases (Tue 16:30 New York).
        val feed =
            MacroSeriesFeed(
                qktSymbol = "MACRO:DGS10",
                points = points,
                fromMs = utcMs(LocalDate.of(2024, 3, 4), 0),
                toMs = utcMs(LocalDate.of(2024, 3, 5), 12),
            )
        val seen = drain(feed)
        // Only Friday's value (released Mon 16:30 NY) is knowable by Tue 12:00.
        assertThat(seen.map { it.second }).containsExactly(BigDecimal("4.00"))
        assertThat(seen.single().first).isEqualTo(released(LocalDate.of(2024, 3, 4)))
    }

    @Test
    fun `each value is stamped at its release time, in order`() {
        val feed =
            MacroSeriesFeed(
                qktSymbol = "MACRO:DGS10",
                points = points,
                fromMs = 0L,
                toMs = Long.MAX_VALUE,
            )
        val seen = drain(feed)
        assertThat(seen).containsExactly(
            released(LocalDate.of(2024, 3, 4)) to BigDecimal("4.00"),
            released(LocalDate.of(2024, 3, 5)) to BigDecimal("4.10"),
            released(LocalDate.of(2024, 3, 6)) to BigDecimal("4.20"),
        )
    }

    @Test
    fun `explicit availability overrides the generic release schedule`() {
        val availableAt = utcMs(LocalDate.of(2024, 3, 1), 7)
        val feed =
            MacroSeriesFeed(
                qktSymbol = "MACRO:POLICY",
                points = listOf(MacroPoint(LocalDate.of(2024, 3, 1), BigDecimal("4.35"), availableAt)),
                fromMs = 0L,
                toMs = Long.MAX_VALUE,
            )

        assertThat(drain(feed)).containsExactly(availableAt to BigDecimal("4.35"))
    }
}
