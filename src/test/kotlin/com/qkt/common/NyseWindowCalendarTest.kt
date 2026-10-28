package com.qkt.common

import com.qkt.candles.TimeWindow
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A NYSE session window, `nyse[06:00-15:45]` here (the hours Exness quotes US stock CFDs in): NYSE trading days,
 * 06:00-15:45 New York, 06:00-13:00 on the exchange's early-close days. The window Exness takes positions in is
 * pinned in [com.qkt.connector.mt5.MT5ExnessStockCalendarTest].
 */
class NyseWindowCalendarTest {
    private val cal = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse calendar not known")

    private fun open(utc: String) = cal.isInSession("AAPL", Instant.parse(utc))

    @Test
    fun `the name round-trips and nyse alone stays the regular session`() {
        assertThat(cal.name).isEqualTo("nyse[06:00-15:45]")
        assertThat(TradingCalendar.named("nyse")).isSameAs(NyseCalendar)
        assertThat(TradingCalendar.named("NYSE[06:00-15:45]")?.name).isEqualTo("nyse[06:00-15:45]")
        assertThat(NyseWindowCalendar.parse(" NYSE[06:00-15:45] ")?.name).isEqualTo("nyse[06:00-15:45]")
    }

    @Test
    fun `in summer time the session is 10_00 to 19_45 UTC, both edges to the minute`() {
        assertThat(open("2026-10-26T09:59:59Z")).isFalse()
        assertThat(open("2026-10-26T10:00:00Z")).isTrue()
        assertThat(open("2026-10-26T19:44:59Z")).isTrue()
        assertThat(open("2026-10-26T19:45:00Z")).isFalse()
        assertThat(cal.sessionRange("AAPL", Instant.parse("2026-10-26T15:00:00Z")))
            .isEqualTo(TimeRange(Instant.parse("2026-10-26T10:00:00Z"), Instant.parse("2026-10-26T19:45:00Z")))
    }

    @Test
    fun `in winter time it is 11_00 to 20_45 UTC, on each side of both clock changes`() {
        assertThat(open("2026-03-06T10:30:00Z")).isFalse()
        assertThat(open("2026-03-06T11:00:00Z")).isTrue()
        assertThat(open("2026-03-06T20:44:59Z")).isTrue()
        assertThat(open("2026-03-06T20:45:00Z")).isFalse()
        assertThat(open("2026-03-09T10:00:00Z")).isTrue() // the Monday after clocks went forward
        assertThat(open("2025-10-31T10:00:00Z")).isTrue() // the Friday before they went back
        assertThat(open("2025-11-03T10:00:00Z")).isFalse()
        assertThat(open("2025-11-03T11:00:00Z")).isTrue()
    }

    @Test
    fun `on an early-close day the session ends at the exchange's 13_00 close`() {
        for (day in listOf("2025-11-28", "2025-12-24")) {
            assertThat(open("${day}T11:00:00Z")).isTrue()
            assertThat(open("${day}T17:59:59Z")).isTrue()
            assertThat(open("${day}T18:00:00Z")).isFalse()
        }
        assertThat(cal.sessionRange("AAPL", Instant.parse("2025-11-28T12:00:00Z")).to)
            .isEqualTo(Instant.parse("2025-11-28T18:00:00Z"))
    }

    @Test
    fun `every kind of NYSE closure is closed all day, and the days around them trade`() {
        // Rule holidays, an observed Independence Day, Juneteenth, Good Friday and the 2025 day of mourning.
        for (day in listOf(
            "2025-11-27",
            "2025-12-25",
            "2026-01-01",
            "2026-07-03",
            "2026-06-19",
            "2026-04-03",
            "2025-01-09",
        )) {
            assertThat(open("${day}T15:00:00Z")).describedAs(day).isFalse()
            assertThat(cal.isClosedSession("AAPL", cal.sessionRange("AAPL", Instant.parse("${day}T15:00:00Z"))))
                .describedAs(day)
                .isTrue()
        }
        for (day in listOf("2025-11-26", "2025-12-26", "2026-01-02", "2026-07-02", "2025-01-10")) {
            assertThat(open("${day}T15:00:00Z")).describedAs(day).isTrue()
        }
        assertThat(open("2026-10-24T15:00:00Z")).isFalse() // Saturday
    }

    @Test
    fun `a window past the regular close keeps it, ends at an early close, and must open before 13_00`() {
        val late = NyseWindowCalendar.parse("nyse[09:30-20:00]") ?: error("parse")
        assertThat(late.isInSession("AAPL", Instant.parse("2025-11-28T18:30:00Z"))).isFalse()
        assertThat(late.isInSession("AAPL", Instant.parse("2025-11-26T22:30:00Z"))).isTrue()
        assertThatThrownBy { NyseWindowCalendar.parse("nyse[14:00-15:00]") }.hasMessageContaining("13:00 early close")
    }

    @Test
    fun `the session previous to a Monday is the Friday, skipping a holiday`() {
        val monday = Instant.parse("2025-12-01T15:00:00Z")
        val friday =
            cal.rangeFor(
                SessionAnchor.PreviousSession,
                cal.anchorEpochFor(SessionAnchor.PreviousSession, monday),
            )
        assertThat(friday.to).isEqualTo(Instant.parse("2025-11-28T18:00:00Z"))
        val afterThanksgiving = Instant.parse("2025-11-28T15:00:00Z")
        val wednesday =
            cal.rangeFor(
                SessionAnchor.PreviousSession,
                cal.anchorEpochFor(SessionAnchor.PreviousSession, afterThanksgiving),
            )
        assertThat(wednesday.from).isEqualTo(Instant.parse("2025-11-26T11:00:00Z"))
    }

    @Test
    fun `a year has the same trading days as nyse, and 1h bars count its longer sessions`() {
        val day = TimeWindow.parse("1d")
        assertThat(cal.tradingPeriodsPerYear(day)).isEqualByComparingTo(NyseCalendar.tradingPeriodsPerYear(day))
        val hour = TimeWindow.parse("1h")
        assertThat(cal.tradingPeriodsPerYear(hour)).isGreaterThan(NyseCalendar.tradingPeriodsPerYear(hour))
    }

    @Test
    fun `malformed or empty windows are refused with the spec in the message`() {
        assertThat(NyseWindowCalendar.parse("nyse")).isNull()
        assertThat(NyseWindowCalendar.parse("nyse[6:00-15:45]")).isNull()
        assertThatThrownBy {
            NyseWindowCalendar.parse(
                "nyse[15:45-06:00]",
            )
        }.hasMessageContaining("open before it closes")
        assertThatThrownBy { NyseWindowCalendar.parse("nyse[06:00-25:00]") }.hasMessageContaining("nyse[06:00-25:00]")
    }
}
