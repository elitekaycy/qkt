package com.qkt.marketdata.macro

import java.time.DayOfWeek
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UsFederalCalendarTest {
    private fun d(iso: String) = LocalDate.parse(iso)

    @Test
    fun `2026 holidays match the OPM schedule`() {
        // https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/ (2026)
        assertThat(UsFederalCalendar.holidays(2026)).containsExactlyInAnyOrder(
            d("2026-01-01"),
            d("2026-01-19"),
            d("2026-02-16"),
            d("2026-05-25"),
            d("2026-06-19"),
            d("2026-07-03"), // Independence Day is a Saturday, observed Friday
            d("2026-09-07"),
            d("2026-10-12"),
            d("2026-11-11"),
            d("2026-11-26"),
            d("2026-12-25"),
        )
    }

    @Test
    fun `a sunday holiday is observed monday and juneteenth starts in 2021`() {
        assertThat(UsFederalCalendar.holidays(2022)).contains(d("2022-06-20"), d("2022-12-26"))
        assertThat(UsFederalCalendar.holidays(2020).map { it.monthValue to it.dayOfMonth }).doesNotContain(6 to 19)
        assertThat(UsFederalCalendar.holidays(2021)).contains(d("2021-06-18"))
    }

    @Test
    fun `next year's saturday new year is observed on this year's 31 december`() {
        assertThat(d("2022-01-01").dayOfWeek).isEqualTo(DayOfWeek.SATURDAY)
        assertThat(UsFederalCalendar.isClosed(d("2021-12-31"))).isTrue()
    }

    @Test
    fun `the fed works good friday and closes on columbus and veterans day`() {
        assertThat(UsFederalCalendar.isClosed(d("2026-04-03"))).isFalse() // Good Friday, NYSE closed
        assertThat(UsFederalCalendar.isClosed(d("2026-10-12"))).isTrue()
        assertThat(UsFederalCalendar.isClosed(d("2026-11-11"))).isTrue()
    }

    @Test
    fun `executive-order closures are closed`() {
        assertThat(UsFederalCalendar.isClosed(d("2025-01-09"))).isTrue()
        assertThat(UsFederalCalendar.isClosed(d("2024-12-24"))).isTrue()
        assertThat(UsFederalCalendar.isClosed(d("2023-12-26"))).isFalse()
        assertThat(UsFederalCalendar.isClosed(d("2008-12-26"))).isTrue()
        assertThat(UsFederalCalendar.isClosed(d("2012-10-29"))).isTrue()
    }

    @Test
    fun `next business day skips weekends and holidays`() {
        assertThat(UsFederalCalendar.nextBusinessDay(d("2024-05-24"))).isEqualTo(d("2024-05-28"))
        assertThat(UsFederalCalendar.nextBusinessDay(d("2024-12-23"))).isEqualTo(d("2024-12-26"))
        assertThat(UsFederalCalendar.nextBusinessDay(d("2024-01-31"), 2)).isEqualTo(d("2024-02-02"))
        // Christmas 2008 and the executive-order closure the day after.
        assertThat(UsFederalCalendar.nextBusinessDay(d("2008-12-24"))).isEqualTo(d("2008-12-29"))
    }
}
