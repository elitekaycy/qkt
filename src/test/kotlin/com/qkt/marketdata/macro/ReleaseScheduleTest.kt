package com.qkt.marketdata.macro

import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReleaseScheduleTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `a friday observation releases monday at 16 30 new york`() {
        assertThat(ReleaseSchedule.releaseTimeMs(LocalDate.of(2024, 3, 1)))
            .isEqualTo(at("2024-03-04T21:30:00Z"))
    }

    @Test
    fun `the release follows new york daylight saving`() {
        assertThat(ReleaseSchedule.releaseTimeMs(LocalDate.of(2024, 3, 12)))
            .isEqualTo(at("2024-03-13T20:30:00Z"))
    }

    @Test
    fun `a holiday pushes the release to the next business day`() {
        // Memorial Day 2024-05-27: Friday's value posts Tuesday.
        assertThat(ReleaseSchedule.releaseTimeMs(LocalDate.of(2024, 5, 24)))
            .isEqualTo(at("2024-05-28T20:30:00Z"))
    }

    @Test
    fun `a value is never visible before the 4 15 pm posting`() {
        val posting = at("2024-03-06T21:15:00Z") // 16:15 EST
        assertThat(ReleaseSchedule.releaseTimeMs(LocalDate.of(2024, 3, 5))).isGreaterThan(posting)
    }

    @Test
    fun `a longer lag counts business days`() {
        assertThat(ReleaseSchedule.releaseTimeMs(LocalDate.of(2024, 1, 31), lagBusinessDays = 2))
            .isEqualTo(at("2024-02-02T21:30:00Z"))
    }
}
