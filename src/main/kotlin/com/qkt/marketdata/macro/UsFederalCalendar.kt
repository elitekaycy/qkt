package com.qkt.marketdata.macro

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * US federal business days: when a US agency (the Fed's H.15, FRED's copy of it) can publish.
 *
 * Federal releases are not posted on weekends, federal holidays (as observed) or days federal
 * offices are closed by executive order, so "the next weekday" makes a value visible a day early
 * around every holiday. The NYSE calendar is not a substitute: the Fed works Good Friday and closes
 * on Columbus and Veterans Day. Kept identical to qkt-data-hub's `tools/us_calendar.py`, so a
 * `MACRO:` series and its hub dataset release on the same instant.
 */
object UsFederalCalendar {
    /**
     * One-off closures: executive orders, days of mourning and Washington-area emergency closures.
     * Counting a day as closed can only make a release later, never earlier, so a doubtful day is included.
     */
    val SPECIAL_CLOSURES: Set<LocalDate> =
        listOf(
            "2004-06-11", // President Reagan, day of mourning
            "2007-01-02", // President Ford, day of mourning
            "2007-12-24",
            "2008-12-26",
            "2010-02-08", // Washington snowstorm closures
            "2010-02-09",
            "2010-02-10",
            "2010-02-11",
            "2012-10-29", // Hurricane Sandy
            "2012-10-30",
            "2012-12-24",
            "2014-12-26",
            "2015-12-24",
            "2016-01-25", // Washington snowstorm closures
            "2016-01-26",
            "2018-12-05", // President George H. W. Bush, day of mourning
            "2018-12-24",
            "2019-12-24",
            "2020-12-24",
            "2024-12-24",
            "2025-01-09", // President Jimmy Carter, day of mourning
            "2025-12-24",
            "2025-12-26",
        ).map(LocalDate::parse).toSet()

    private fun nth(
        year: Int,
        month: Int,
        day: DayOfWeek,
        n: Int,
    ): LocalDate = LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    private fun observed(day: LocalDate): LocalDate =
        when (day.dayOfWeek) {
            DayOfWeek.SATURDAY -> day.minusDays(1)
            DayOfWeek.SUNDAY -> day.plusDays(1)
            else -> day
        }

    /** Federal holidays as observed: a Saturday holiday on the Friday, a Sunday one on the Monday. */
    fun holidays(year: Int): Set<LocalDate> {
        val days =
            mutableSetOf(
                observed(LocalDate.of(year, 1, 1)),
                nth(year, 1, DayOfWeek.MONDAY, 3), // Martin Luther King Jr. Day
                nth(year, 2, DayOfWeek.MONDAY, 3), // Washington's Birthday
                LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), // Memorial Day
                observed(LocalDate.of(year, 7, 4)),
                nth(year, 9, DayOfWeek.MONDAY, 1), // Labor Day
                nth(year, 10, DayOfWeek.MONDAY, 2), // Columbus Day
                observed(LocalDate.of(year, 11, 11)),
                nth(year, 11, DayOfWeek.THURSDAY, 4), // Thanksgiving
                observed(LocalDate.of(year, 12, 25)),
            )
        if (year >= 2021) days.add(observed(LocalDate.of(year, 6, 19))) // Juneteenth, first observed 2021-06-18
        // The next year's New Year's Day falling on a Saturday is observed on this year's 31 December.
        if (LocalDate.of(year + 1, 1, 1).dayOfWeek == DayOfWeek.SATURDAY) days.add(LocalDate.of(year, 12, 31))
        return days
    }

    fun isClosed(day: LocalDate): Boolean =
        day.dayOfWeek == DayOfWeek.SATURDAY ||
            day.dayOfWeek == DayOfWeek.SUNDAY ||
            day in SPECIAL_CLOSURES ||
            day in holidays(day.year)

    /** The [n]th federal business day after [day]. */
    fun nextBusinessDay(
        day: LocalDate,
        n: Int = 1,
    ): LocalDate {
        var out = day
        repeat(n) {
            out = out.plusDays(1)
            while (isClosed(out)) out = out.plusDays(1)
        }
        return out
    }
}
