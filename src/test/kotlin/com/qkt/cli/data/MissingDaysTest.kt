package com.qkt.cli.data

import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MissingDaysTest {
    private fun days(vararg d: String) = d.associate { LocalDate.parse(it) to 100 }

    @Test
    fun `a weekday between two sources with no file is listed, weekends are not`() {
        val missing = MissingDays.of(days("2026-08-21", "2026-08-24", "2026-08-25", "2026-08-27", "2026-08-28"))

        assertThat(missing.days.map { it.toString() }).containsExactly("2026-08-26")
        assertThat(missing.lines().single()).startsWith("2026-08-26  MISSING (1 weekday(s)")
    }

    @Test
    fun `a store holding a Saturday expects every day and runs of holes are one line`() {
        val missing = MissingDays.of(days("2026-08-22", "2026-08-23", "2026-08-27"))

        assertThat(missing.days).hasSize(3)
        assertThat(missing.lines().single()).startsWith("2026-08-24 … 2026-08-26  MISSING (3 day(s)")
    }

    @Test
    fun `a Sunday evening file or an empty Saturday file does not make every day expected`() {
        assertThat(MissingDays.of(days("2026-08-23", "2026-08-24", "2026-08-28")).days).hasSize(3)
        val emptySaturday = days("2026-08-21", "2026-08-24") + (LocalDate.parse("2026-08-22") to 0)
        assertThat(MissingDays.of(emptySaturday).days).isEmpty()
        assertThat(MissingDays.of(days("2026-08-28", "2026-08-30", "2026-08-31")).days).isEmpty()
    }
}
