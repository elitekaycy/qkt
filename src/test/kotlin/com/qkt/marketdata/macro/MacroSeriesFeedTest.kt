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

    private val points =
        listOf(
            MacroPoint(LocalDate.of(2024, 3, 1), BigDecimal("4.00")), // Fri -> released Mon 13:00
            MacroPoint(LocalDate.of(2024, 3, 4), BigDecimal("4.10")), // Mon -> released Tue 13:00
            MacroPoint(LocalDate.of(2024, 3, 5), BigDecimal("4.20")), // Tue -> released Wed 13:00
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
        // Window: Monday 00:00 .. Tuesday 12:00 — one hour BEFORE Monday's value releases (Tue 13:00).
        val feed =
            MacroSeriesFeed(
                qktSymbol = "MACRO:DGS10",
                points = points,
                fromMs = utcMs(LocalDate.of(2024, 3, 4), 0),
                toMs = utcMs(LocalDate.of(2024, 3, 5), 12),
            )
        val seen = drain(feed)
        // Only Friday's value (released Mon 13:00) is knowable by Tue 12:00.
        assertThat(seen.map { it.second }).containsExactly(BigDecimal("4.00"))
        assertThat(seen.single().first).isEqualTo(utcMs(LocalDate.of(2024, 3, 4), 13))
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
            utcMs(LocalDate.of(2024, 3, 4), 13) to BigDecimal("4.00"),
            utcMs(LocalDate.of(2024, 3, 5), 13) to BigDecimal("4.10"),
            utcMs(LocalDate.of(2024, 3, 6), 13) to BigDecimal("4.20"),
        )
    }
}
