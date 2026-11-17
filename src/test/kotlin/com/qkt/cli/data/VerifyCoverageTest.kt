package com.qkt.cli.data

import com.qkt.marketdata.store.ActivityRun
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VerifyCoverageTest {
    private fun ms(
        day: String,
        hour: Int,
        minute: Int = 0,
    ) = LocalDate
        .parse(day)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli() + (hour * 60L + minute) * 60_000L

    private fun fullDay(day: String) = listOf(ActivityRun(ms(day, 0), ms(day, 23, 59)))

    @Test
    fun `with nothing stored every day reads ok`() {
        val coverage = VerifyCoverage("EURUSD", tradesSaturdays = false, unit = "bar", stored = emptyMap())

        assertThat(coverage.of(LocalDate.parse("2024-01-08"), empty = false)).isEqualTo(VerifyVerdict("ok", false))
    }

    @Test
    fun `data on a closed day is accepted and a day cut short inside the stored span is a gap`() {
        val saturday = LocalDate.parse("2024-01-06")
        val weekend = VerifyCoverage("EURUSD", false, "bar", mapOf(saturday to fullDay("2024-01-06")))
        val stored =
            listOf("2024-01-08", "2024-01-10").associate { LocalDate.parse(it) to fullDay(it) } +
                (LocalDate.parse("2024-01-09") to listOf(ActivityRun(ms("2024-01-09", 10), ms("2024-01-09", 10, 30))))
        val span = VerifyCoverage("EURUSD", false, "bar", stored)

        assertThat(weekend.of(saturday, empty = false)).isEqualTo(VerifyVerdict("ok", false))
        assertThat(span.of(LocalDate.parse("2024-01-09"), empty = false).flagged).isTrue()
        assertThat(span.of(LocalDate.parse("2024-01-09"), empty = false).text).startsWith("GAP (")
    }

    @Test
    fun `days inside the stored span with no file are reported unless the market was shut`() {
        val stored = listOf("2023-12-22", "2023-12-26").associate { LocalDate.parse(it) to fullDay(it) }
        val lines = VerifyCoverage("EURUSD", false, "bar", stored).absentLines()

        assertThat(lines).isNotEmpty()
        assertThat(lines.joinToString("\n") { it.text }).contains("2023-12-25").contains("(no file)")
    }
}
