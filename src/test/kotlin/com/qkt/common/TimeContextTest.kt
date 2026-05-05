package com.qkt.common

import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TimeContextTest {
    private fun ctx(at: String): TimeContext = TimeContext(FixedClock(time = Instant.parse(at).toEpochMilli()))

    @Test
    fun `now returns clock time`() {
        val time = ctx("2024-01-15T14:23:00Z")
        assertThat(time.now()).isEqualTo(Instant.parse("2024-01-15T14:23:00Z"))
    }

    @Test
    fun `today is start of today to start of tomorrow UTC`() {
        val r = ctx("2024-01-15T14:23:00Z").today()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-16T00:00:00Z"))
    }

    @Test
    fun `yesterday is start of prev day to start of today UTC`() {
        val r = ctx("2024-01-15T14:23:00Z").yesterday()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-14T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T00:00:00Z"))
    }

    @Test
    fun `lastDays trails N days from now`() {
        val r = ctx("2024-01-15T14:23:00Z").lastDays(3)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-12T14:23:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T14:23:00Z"))
    }

    @Test
    fun `lastHours trails N hours from now`() {
        val r = ctx("2024-01-15T14:23:00Z").lastHours(2)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T12:23:00Z"))
    }

    @Test
    fun `thisMonth covers first to first of next`() {
        val r = ctx("2024-01-15T14:23:00Z").thisMonth()
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-02-01T00:00:00Z"))
    }

    @Test
    fun `previousMonth handles January rolling into prev year`() {
        val r = ctx("2024-01-15T14:23:00Z").previousMonth()
        assertThat(r.from).isEqualTo(Instant.parse("2023-12-01T00:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun `thisYear and previousYear correct`() {
        val time = ctx("2024-03-15T00:00:00Z")
        assertThat(time.thisYear().from).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        assertThat(time.previousYear().from).isEqualTo(Instant.parse("2023-01-01T00:00:00Z"))
        assertThat(time.previousYear().to).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test
    fun `session returns time of day window for a date`() {
        val time = ctx("2024-01-15T14:23:00Z")
        val r = time.session(LocalDate.parse("2024-01-15"), 8, 16)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T08:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-15T16:00:00Z"))
    }

    @Test
    fun `session with endHour 24 rolls to next day midnight`() {
        val time = ctx("2024-01-15T14:23:00Z")
        val r = time.session(LocalDate.parse("2024-01-15"), 22, 24)
        assertThat(r.from).isEqualTo(Instant.parse("2024-01-15T22:00:00Z"))
        assertThat(r.to).isEqualTo(Instant.parse("2024-01-16T00:00:00Z"))
    }
}
