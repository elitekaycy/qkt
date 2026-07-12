package com.qkt.marketdata.store.macro

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

internal object NewZealandBusinessCalendar {
    private const val FIRST_SUPPORTED_YEAR = 1999
    private const val LAST_SUPPORTED_YEAR = 2052
    private val holidayCache = ConcurrentHashMap<Int, Set<LocalDate>>()

    fun nextBusinessDay(date: LocalDate): LocalDate {
        require(date.year in FIRST_SUPPORTED_YEAR..LAST_SUPPORTED_YEAR) {
            "New Zealand policy-rate calendar supports $FIRST_SUPPORTED_YEAR..$LAST_SUPPORTED_YEAR, got $date"
        }
        var candidate = date.plusDays(1)
        while (true) {
            check(candidate.year in FIRST_SUPPORTED_YEAR..LAST_SUPPORTED_YEAR) {
                "New Zealand policy-rate release falls outside the audited calendar: $candidate"
            }
            if (
                candidate.dayOfWeek.value < DayOfWeek.SATURDAY.value &&
                candidate !in holidays(candidate.year)
            ) {
                return candidate
            }
            candidate = candidate.plusDays(1)
        }
    }

    private fun holidays(year: Int): Set<LocalDate> = holidayCache.computeIfAbsent(year, ::computeHolidays)

    private fun computeHolidays(year: Int): Set<LocalDate> =
        buildSet {
            addObservedPair(
                LocalDate.of(year, Month.JANUARY, 1),
                LocalDate.of(year, Month.JANUARY, 2),
            )
            add(wellingtonAnniversary(year))
            addMondayised(LocalDate.of(year, Month.FEBRUARY, 6), mondayised = year >= 2014)
            val easterSunday = easterSunday(year)
            add(easterSunday.minusDays(2))
            add(easterSunday.plusDays(1))
            addMondayised(LocalDate.of(year, Month.APRIL, 25), mondayised = year >= 2014)
            add(LocalDate.of(year, Month.JUNE, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)))
            MATARIKI_DATES[year]?.let(::add)
            add(
                LocalDate
                    .of(year, Month.OCTOBER, 1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.MONDAY)),
            )
            if (year == 2022) add(LocalDate.of(2022, Month.SEPTEMBER, 26))
            addObservedPair(
                LocalDate.of(year, Month.DECEMBER, 25),
                LocalDate.of(year, Month.DECEMBER, 26),
            )
        }

    private fun MutableSet<LocalDate>.addMondayised(
        date: LocalDate,
        mondayised: Boolean,
    ) {
        add(date)
        if (!mondayised) return
        when (date.dayOfWeek) {
            DayOfWeek.SATURDAY -> add(date.plusDays(2))
            DayOfWeek.SUNDAY -> add(date.plusDays(1))
            else -> Unit
        }
    }

    private fun MutableSet<LocalDate>.addObservedPair(
        first: LocalDate,
        second: LocalDate,
    ) {
        add(first)
        add(second)
        when (first.dayOfWeek) {
            DayOfWeek.FRIDAY -> add(second.plusDays(2))
            DayOfWeek.SATURDAY -> {
                add(first.plusDays(2))
                add(second.plusDays(2))
            }
            DayOfWeek.SUNDAY -> add(first.plusDays(2))
            else -> Unit
        }
    }

    private fun wellingtonAnniversary(year: Int): LocalDate {
        val january22 = LocalDate.of(year, Month.JANUARY, 22)
        val previous = january22.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val next = january22.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val previousDistance = january22.toEpochDay() - previous.toEpochDay()
        val nextDistance = next.toEpochDay() - january22.toEpochDay()
        return if (previousDistance <= nextDistance) {
            previous
        } else {
            next
        }
    }

    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private val MATARIKI_DATES =
        mapOf(
            2022 to LocalDate.of(2022, 6, 24),
            2023 to LocalDate.of(2023, 7, 14),
            2024 to LocalDate.of(2024, 6, 28),
            2025 to LocalDate.of(2025, 6, 20),
            2026 to LocalDate.of(2026, 7, 10),
            2027 to LocalDate.of(2027, 6, 25),
            2028 to LocalDate.of(2028, 7, 14),
            2029 to LocalDate.of(2029, 7, 6),
            2030 to LocalDate.of(2030, 6, 21),
            2031 to LocalDate.of(2031, 7, 11),
            2032 to LocalDate.of(2032, 7, 2),
            2033 to LocalDate.of(2033, 6, 24),
            2034 to LocalDate.of(2034, 7, 7),
            2035 to LocalDate.of(2035, 6, 29),
            2036 to LocalDate.of(2036, 7, 18),
            2037 to LocalDate.of(2037, 7, 10),
            2038 to LocalDate.of(2038, 6, 25),
            2039 to LocalDate.of(2039, 7, 15),
            2040 to LocalDate.of(2040, 7, 6),
            2041 to LocalDate.of(2041, 7, 19),
            2042 to LocalDate.of(2042, 7, 11),
            2043 to LocalDate.of(2043, 7, 3),
            2044 to LocalDate.of(2044, 6, 24),
            2045 to LocalDate.of(2045, 7, 7),
            2046 to LocalDate.of(2046, 6, 29),
            2047 to LocalDate.of(2047, 7, 19),
            2048 to LocalDate.of(2048, 7, 3),
            2049 to LocalDate.of(2049, 6, 25),
            2050 to LocalDate.of(2050, 7, 15),
            2051 to LocalDate.of(2051, 6, 30),
            2052 to LocalDate.of(2052, 6, 21),
        )
}
