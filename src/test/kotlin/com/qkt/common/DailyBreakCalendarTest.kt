package com.qkt.common

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DailyBreakCalendarTest {
    private val nyClose =
        DailyBreakCalendar(FxCalendar, LocalTime.of(17, 0), LocalTime.of(18, 0), ZoneId.of("America/New_York"))

    @Test
    fun `break follows the venue wall clock across daylight saving`() {
        // Thursday 2026-08-20: New York is UTC-4, so 17:00 NY is 21:00 UTC.
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-08-20T20:59:59Z"))).isFalse()
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-08-20T21:00:00Z"))).isTrue()
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-08-20T21:59:59Z"))).isTrue()
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-08-20T22:00:00Z"))).isFalse()
        // Thursday 2026-12-10: New York is UTC-5, so the same pause sits at 22:00 UTC.
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-12-10T21:30:00Z"))).isFalse()
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-12-10T22:30:00Z"))).isTrue()
    }

    @Test
    fun `session semantics are the base calendar's, untouched by the break`() {
        val inBreak = Instant.parse("2026-08-20T21:30:00Z")
        assertThat(nyClose.isInSession("XAUUSD", inBreak)).isTrue()
        assertThat(nyClose.isInSession("XAUUSD", Instant.parse("2026-08-22T12:00:00Z"))).isFalse()
        assertThat(nyClose.isScheduledBreak("XAUUSD", Instant.parse("2026-08-22T21:30:00Z"))).isTrue()
    }

    @Test
    fun `parses the pause sentence with and without a zone`() {
        val parsed = DailyBreakCalendar.parse("fx pause 17:00-18:00 America/New_York") { FxCalendar }
        assertThat(parsed).isEqualTo(nyClose)
        assertThat(parsed!!.name).isEqualTo("fx pause 17:00-18:00 America/New_York")

        val utc = DailyBreakCalendar.parse("fx pause 21:00-22:00") { FxCalendar }!!
        assertThat(utc.isScheduledBreak("X", Instant.parse("2026-12-10T21:30:00Z"))).isTrue()
        assertThat(DailyBreakCalendar.parse("fx") { FxCalendar }).isNull()
        assertThatThrownBy { DailyBreakCalendar.parse("fx pause 21:00") { FxCalendar } }
            .hasMessageContaining("pause HH:MM-HH:MM")
    }

    @Test
    fun `a window wrapping midnight matches both sides`() {
        val wrap = DailyBreakCalendar(FxCalendar, LocalTime.of(23, 30), LocalTime.of(0, 30), java.time.ZoneOffset.UTC)
        assertThat(wrap.isScheduledBreak("X", Instant.parse("2026-08-20T23:45:00Z"))).isTrue()
        assertThat(wrap.isScheduledBreak("X", Instant.parse("2026-08-21T00:15:00Z"))).isTrue()
        assertThat(wrap.isScheduledBreak("X", Instant.parse("2026-08-21T00:45:00Z"))).isFalse()
    }
}
