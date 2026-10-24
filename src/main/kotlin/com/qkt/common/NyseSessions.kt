package com.qkt.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

/**
 * Which days the New York Stock Exchange trades and when each closes: the rule-based holidays, the one-off full
 * closures the exchange called (national days of mourning, the September 2001 attacks, Hurricane Sandy) and the
 * 13:00 New York early closes on the day before Independence Day, the day after Thanksgiving and Christmas Eve.
 *
 * Early closes follow the exchange's rule: July 3 and December 24 close early only on a Monday to Thursday (a
 * Friday July 3 or December 24 is the observed holiday itself), and the Friday after Thanksgiving always does.
 * A New Year's Day falling on a Saturday is not observed on the Friday before, as the exchange does not.
 */
internal object NyseSessions {
    val REGULAR_CLOSE: LocalTime = LocalTime.of(16, 0)
    val EARLY_CLOSE: LocalTime = LocalTime.of(13, 0)

    /** Full closures outside the holiday rules, since 2000. */
    private val SPECIAL_CLOSURES: Set<LocalDate> =
        setOf(
            LocalDate.of(2001, 9, 11),
            LocalDate.of(2001, 9, 12),
            LocalDate.of(2001, 9, 13),
            LocalDate.of(2001, 9, 14),
            LocalDate.of(2004, 6, 11),
            LocalDate.of(2007, 1, 2),
            LocalDate.of(2012, 10, 29),
            LocalDate.of(2012, 10, 30),
            LocalDate.of(2018, 12, 5),
            LocalDate.of(2025, 1, 9),
        )

    private val holidayCache = ConcurrentHashMap<Int, Set<LocalDate>>()

    /** True when [date] has a trading session: a weekday that is neither a holiday nor a special closure. */
    fun isTradingDay(date: LocalDate): Boolean = date.dayOfWeek.value < 6 && !isClosed(date)

    /** True when [date] is a weekday holiday or special closure, so its whole session is closed. */
    fun isClosed(date: LocalDate): Boolean = date in SPECIAL_CLOSURES || date in holidaysFor(date.year)

    /** The session close on [date]: 13:00 on an early-close day, 16:00 otherwise. */
    fun closeOn(date: LocalDate): LocalTime = if (isEarlyClose(date)) EARLY_CLOSE else REGULAR_CLOSE

    /** The trading day before [date], skipping weekends, holidays and special closures. */
    fun previousTradingDay(date: LocalDate): LocalDate {
        var d = date.minusDays(1)
        while (!isTradingDay(d)) d = d.minusDays(1)
        return d
    }

    private fun isEarlyClose(date: LocalDate): Boolean {
        val midweek = date.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.THURSDAY
        return when {
            date.monthValue == 7 && date.dayOfMonth == 3 -> midweek
            date.monthValue == 12 && date.dayOfMonth == 24 -> midweek
            else -> date == thanksgiving(date.year).plusDays(1)
        }
    }

    private fun holidaysFor(year: Int): Set<LocalDate> = holidayCache.computeIfAbsent(year) { computeHolidays(year) }

    private fun computeHolidays(year: Int): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        days.add(observed(LocalDate.of(year, 1, 1)))
        days.add(LocalDate.of(year, 1, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY)))
        days.add(LocalDate.of(year, 2, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY)))
        days.add(WesternEaster.goodFriday(year))
        days.add(LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)))
        if (year >= 2022) days.add(observed(LocalDate.of(year, 6, 19)))
        days.add(observed(LocalDate.of(year, 7, 4)))
        days.add(LocalDate.of(year, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)))
        days.add(thanksgiving(year))
        days.add(observed(LocalDate.of(year, 12, 25)))
        return days
    }

    private fun thanksgiving(year: Int): LocalDate =
        LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))

    private fun observed(d: LocalDate): LocalDate =
        when (d.dayOfWeek) {
            DayOfWeek.SATURDAY -> d.minusDays(1)
            DayOfWeek.SUNDAY -> d.plusDays(1)
            else -> d
        }
}
