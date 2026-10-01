package com.qkt.common

import com.qkt.candles.TimeWindow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** CME Globex hours in Chicago time: Sunday 17:00 to Friday 16:00, halted 16:00-17:00 each weekday. */
class CmeGlobexCalendarTest {
    private val chicago = ZoneId.of("America/Chicago")
    private val cal = CmeGlobexCalendar

    private fun ct(local: String): Instant = LocalDateTime.parse(local).atZone(chicago).toInstant()

    private fun open(local: String) = cal.isInSession("CME:ESZ24", ct(local))

    @Test
    fun `the week runs Sunday 17 00 to Friday 16 00 Chicago`() {
        assertThat(open("2024-12-15T16:59")).isFalse()
        assertThat(open("2024-12-15T17:00")).isTrue()
        assertThat(open("2024-12-20T15:59")).isTrue()
        assertThat(open("2024-12-20T16:00")).isFalse()
        assertThat(open("2024-12-21T12:00")).isFalse()
    }

    @Test
    fun `each weekday halts from 16 00 to 17 00 as a scheduled break`() {
        assertThat(open("2024-12-18T15:59")).isTrue()
        assertThat(open("2024-12-18T16:30")).isFalse()
        assertThat(cal.isScheduledBreak("CME:ESZ24", ct("2024-12-18T16:30"))).isTrue()
        assertThat(open("2024-12-18T17:00")).isTrue()
        assertThat(cal.isScheduledBreak("CME:ESZ24", ct("2024-12-21T12:00"))).isFalse()
    }

    @Test
    fun `a session runs from the 17 00 open to the next 16 00 close, over the weekend after Friday`() {
        assertThat(cal.sessionRange("CME:ESZ24", ct("2024-12-18T10:00")))
            .isEqualTo(TimeRange(ct("2024-12-17T17:00"), ct("2024-12-18T16:00")))
        assertThat(cal.sessionRange("CME:ESZ24", ct("2024-12-18T17:30")))
            .isEqualTo(TimeRange(ct("2024-12-18T17:00"), ct("2024-12-19T16:00")))
        assertThat(cal.sessionRange("CME:ESZ24", ct("2024-12-20T18:00")))
            .isEqualTo(TimeRange(ct("2024-12-22T17:00"), ct("2024-12-23T16:00")))
    }

    @Test
    fun `local hours hold across the daylight saving change`() {
        assertThat(cal.sessionRange("CME:ESZ24", ct("2025-03-10T09:00")))
            .isEqualTo(TimeRange(ct("2025-03-09T17:00"), ct("2025-03-10T16:00")))
        assertThat(ct("2025-03-10T16:00")).isEqualTo(Instant.parse("2025-03-10T21:00:00Z"))
        assertThat(ct("2025-03-07T16:00")).isEqualTo(Instant.parse("2025-03-07T22:00:00Z"))
    }

    @Test
    fun `the previous session of a Monday is Friday's`() {
        val monday = ct("2024-12-23T10:00")

        assertThat(
            cal.anchorEpochFor(SessionAnchor.CurrentSession, monday),
        ).isEqualTo(ct("2024-12-23T16:00").toEpochMilli())
        assertThat(
            cal.anchorEpochFor(SessionAnchor.PreviousSession, monday),
        ).isEqualTo(ct("2024-12-20T16:00").toEpochMilli())
        assertThat(cal.rangeFor(SessionAnchor.PreviousSession, ct("2024-12-20T16:00").toEpochMilli()))
            .isEqualTo(TimeRange(ct("2024-12-19T17:00"), ct("2024-12-20T16:00")))
    }

    @Test
    fun `a year has 23 trading hours on five days a week`() {
        assertThat(cal.tradingPeriodsPerYear(TimeWindow(3_600_000L)).toInt()).isEqualTo(6_000)
    }
}
