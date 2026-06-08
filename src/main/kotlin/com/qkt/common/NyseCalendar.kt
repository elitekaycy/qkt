package com.qkt.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

object NyseCalendar : TradingCalendar {
    override val name: String = "nyse"

    private val zone = ZoneId.of("America/New_York")
    private val openTime = LocalTime.of(9, 30)
    private val closeTime = LocalTime.of(16, 0)

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val zdt = t.atZone(zone)
        val date = zdt.toLocalDate()
        if (zdt.dayOfWeek.value >= 6) return false
        if (isHoliday(date)) return false
        val tod = zdt.toLocalTime()
        return tod >= openTime && tod < closeTime
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val date = t.atZone(zone).toLocalDate()
        val start = date.atTime(openTime).atZone(zone).toInstant()
        val end = date.atTime(closeTime).atZone(zone).toInstant()
        return TimeRange(start, end)
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val date = t.atZone(zone).toLocalDate()
        return when (anchor) {
            SessionAnchor.CurrentSession -> date.toEpochDay()
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            -> previousTradingDay(date).toEpochDay()
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }
    }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange =
        when (anchor) {
            SessionAnchor.CurrentSession,
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            -> {
                val date = LocalDate.ofEpochDay(anchorEpoch)
                val start = date.atTime(openTime).atZone(zone).toInstant()
                val end = date.atTime(closeTime).atZone(zone).toInstant()
                TimeRange(start, end)
            }
            is SessionAnchor.Rolling -> {
                val end = Instant.ofEpochMilli(anchorEpoch)
                TimeRange(end.minus(anchor.duration), end)
            }
        }

    private fun previousTradingDay(date: LocalDate): LocalDate {
        var d = date.minusDays(1)
        while (d.dayOfWeek.value >= 6 || isHoliday(d)) d = d.minusDays(1)
        return d
    }

    private fun isHoliday(d: LocalDate): Boolean = d in holidaysFor(d.year)

    private val holidayCache = ConcurrentHashMap<Int, Set<LocalDate>>()

    private fun holidaysFor(year: Int): Set<LocalDate> = holidayCache.computeIfAbsent(year) { computeHolidays(year) }

    private fun computeHolidays(year: Int): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        days.add(observed(LocalDate.of(year, 1, 1)))
        days.add(LocalDate.of(year, 1, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.MONDAY)))
        days.add(LocalDate.of(year, 2, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.MONDAY)))
        days.add(goodFriday(year))
        days.add(LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(java.time.DayOfWeek.MONDAY)))
        if (year >= 2022) days.add(observed(LocalDate.of(year, 6, 19)))
        days.add(observed(LocalDate.of(year, 7, 4)))
        days.add(LocalDate.of(year, 9, 1).with(TemporalAdjusters.firstInMonth(java.time.DayOfWeek.MONDAY)))
        days.add(LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, java.time.DayOfWeek.THURSDAY)))
        days.add(observed(LocalDate.of(year, 12, 25)))
        return days
    }

    private fun observed(d: LocalDate): LocalDate =
        when (d.dayOfWeek) {
            java.time.DayOfWeek.SATURDAY -> d.minusDays(1)
            java.time.DayOfWeek.SUNDAY -> d.plusDays(1)
            else -> d
        }

    private fun goodFriday(year: Int): LocalDate {
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
        val day = ((h + l - 7 * m + 114) % 31) + 1
        val easter = LocalDate.of(year, month, day)
        return easter.minusDays(2)
    }
}
