package com.qkt.common

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

class TimeContext(
    private val clock: Clock,
    val zone: ZoneId = ZoneOffset.UTC,
) {
    fun now(): Instant = Instant.ofEpochMilli(clock.now())

    fun today(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        return TimeRange(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    fun yesterday(): TimeRange {
        val date = now().atZone(zone).toLocalDate().minusDays(1)
        return TimeRange(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant(),
        )
    }

    fun lastHours(n: Long): TimeRange = TimeRange(now().minus(n, ChronoUnit.HOURS), now())

    fun lastDays(n: Long): TimeRange = TimeRange(now().minus(n, ChronoUnit.DAYS), now())

    fun lastWeeks(n: Long): TimeRange = lastDays(n * 7)

    fun thisWeek(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return TimeRange(
            monday.atStartOfDay(zone).toInstant(),
            monday.plusDays(7).atStartOfDay(zone).toInstant(),
        )
    }

    fun lastWeek(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val thisMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastMonday = thisMonday.minusDays(7)
        return TimeRange(
            lastMonday.atStartOfDay(zone).toInstant(),
            thisMonday.atStartOfDay(zone).toInstant(),
        )
    }

    fun thisMonth(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val first = date.withDayOfMonth(1)
        return TimeRange(
            first.atStartOfDay(zone).toInstant(),
            first.plusMonths(1).atStartOfDay(zone).toInstant(),
        )
    }

    fun previousMonth(): TimeRange {
        val date = now().atZone(zone).toLocalDate()
        val firstThis = date.withDayOfMonth(1)
        val firstPrev = firstThis.minusMonths(1)
        return TimeRange(
            firstPrev.atStartOfDay(zone).toInstant(),
            firstThis.atStartOfDay(zone).toInstant(),
        )
    }

    fun thisYear(): TimeRange {
        val year = now().atZone(zone).toLocalDate().year
        return TimeRange(
            LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
            LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant(),
        )
    }

    fun previousYear(): TimeRange {
        val year = now().atZone(zone).toLocalDate().year
        return TimeRange(
            LocalDate.of(year - 1, 1, 1).atStartOfDay(zone).toInstant(),
            LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant(),
        )
    }

    fun session(
        date: LocalDate,
        startHour: Int,
        endHour: Int,
    ): TimeRange {
        require(startHour in 0..23) { "startHour must be in 0..23: $startHour" }
        require(endHour in 1..24) { "endHour must be in 1..24: $endHour" }
        require(startHour < endHour) { "startHour < endHour required: $startHour, $endHour" }
        val start = date.atTime(startHour, 0).atZone(zone).toInstant()
        val end =
            if (endHour == 24) {
                date.plusDays(1).atStartOfDay(zone).toInstant()
            } else {
                date.atTime(endHour, 0).atZone(zone).toInstant()
            }
        return TimeRange(start, end)
    }

    fun sessionToday(
        startHour: Int,
        endHour: Int,
    ): TimeRange = session(now().atZone(zone).toLocalDate(), startHour, endHour)

    fun sessionYesterday(
        startHour: Int,
        endHour: Int,
    ): TimeRange = session(now().atZone(zone).toLocalDate().minusDays(1), startHour, endHour)
}
