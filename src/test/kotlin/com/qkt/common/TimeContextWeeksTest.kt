package com.qkt.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TimeContextWeeksTest {
    // 2024-01-17 is a Wednesday; its week starts Monday 2024-01-15.
    private val wednesday = TimeContext(FixedClock(time = Instant.parse("2024-01-17T10:30:00Z").toEpochMilli()))

    @Test
    fun `last weeks trails seven days per week`() {
        val r = wednesday.lastWeeks(2)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-03T10:30:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-17T10:30:00Z"))
    }

    @Test
    fun `this week runs monday to the next monday`() {
        val r = wednesday.thisWeek()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-22T00:00:00Z"))
    }

    @Test
    fun `last week ends where this week starts`() {
        val r = wednesday.lastWeek()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-08T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `on a monday this week starts the same day`() {
        val monday = TimeContext(FixedClock(time = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli()))
        assertThat(monday.thisWeek().from).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `today and yesterday sessions use the clock's date in the zone`() {
        val ny = TimeContext(FixedClock(time = Instant.parse("2024-01-17T03:00:00Z").toEpochMilli()), ZoneId.of("America/New_York"))
        val today = ny.sessionToday(9, 16)
        assertThat(today.from).isEqualTo(Instant.parse("2024-01-16T14:00:00Z"))
        assertThat(today.to).isEqualTo(Instant.parse("2024-01-16T21:00:00Z"))
        assertThat(ny.sessionYesterday(9, 16).from).isEqualTo(Instant.parse("2024-01-15T14:00:00Z"))
    }

    @Test
    fun `session hours outside the day or out of order are rejected`() {
        val day = LocalDate.parse("2024-01-17")
        assertThatThrownBy { wednesday.session(day, -1, 5) }.hasMessageContaining("startHour must be in 0..23")
        assertThatThrownBy { wednesday.session(day, 24, 24) }.hasMessageContaining("startHour must be in 0..23")
        assertThatThrownBy { wednesday.session(day, 0, 0) }.hasMessageContaining("endHour must be in 1..24")
        assertThatThrownBy { wednesday.session(day, 0, 25) }.hasMessageContaining("endHour must be in 1..24")
        assertThatThrownBy { wednesday.session(day, 9, 9) }.hasMessageContaining("startHour < endHour")
    }
}
