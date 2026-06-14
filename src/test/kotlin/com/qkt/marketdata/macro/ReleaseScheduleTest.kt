package com.qkt.marketdata.macro

import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReleaseScheduleTest {
    private fun utc(
        date: LocalDate,
        hour: Int,
    ): Long = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `a friday observation releases the next business day, monday`() {
        val fri = LocalDate.of(2024, 3, 1)
        assertThat(ReleaseSchedule.releaseTimeMs(fri))
            .isEqualTo(utc(LocalDate.of(2024, 3, 4), 13))
    }

    @Test
    fun `a midweek observation releases the next day`() {
        val tue = LocalDate.of(2024, 3, 5)
        assertThat(ReleaseSchedule.releaseTimeMs(tue))
            .isEqualTo(utc(LocalDate.of(2024, 3, 6), 13))
    }

    @Test
    fun `the release hour is configurable`() {
        val tue = LocalDate.of(2024, 3, 5)
        assertThat(ReleaseSchedule.releaseTimeMs(tue, releaseUtcHour = 8))
            .isEqualTo(utc(LocalDate.of(2024, 3, 6), 8))
    }
}
