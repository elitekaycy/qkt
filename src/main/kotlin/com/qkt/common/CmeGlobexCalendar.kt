package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * CME Globex trading hours in Chicago time: each session opens at 17:00 and closes at 16:00 the next
 * day, Sunday evening to Friday afternoon, with the daily 16:00–17:00 halt out of session (and a
 * scheduled break). Daylight saving is followed through the zone. Exchange holidays and early closes
 * are not modelled.
 */
object CmeGlobexCalendar : TradingCalendar {
    override val name: String = "cme_globex"

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val open: LocalTime = LocalTime.of(17, 0)
    private val close: LocalTime = LocalTime.of(16, 0)

    /** 23 trading hours on 5 days of every 7: the share of a year's minutes the market is open. */
    override fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal {
        val minutesPerYear = BigDecimal("525960").multiply(BigDecimal(23 * 5)).divide(BigDecimal(24 * 7), Money.CONTEXT)
        val windowMinutes = BigDecimal(window.durationMs).divide(BigDecimal("60000"), Money.CONTEXT)
        return minutesPerYear.divide(windowMinutes, Money.CONTEXT)
    }

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val local = t.atZone(zone)
        val time = local.toLocalTime()
        return when (local.dayOfWeek) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> time >= open
            DayOfWeek.FRIDAY -> time < close
            else -> time < close || time >= open
        }
    }

    override fun isScheduledBreak(
        symbol: String,
        t: Instant,
    ): Boolean {
        val local = t.atZone(zone)
        val halts = local.dayOfWeek in DayOfWeek.MONDAY..DayOfWeek.THURSDAY
        val time = local.toLocalTime()
        return halts && time >= close && time < open
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange = rangeEndingOn(sessionEndDate(t))

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val end = sessionEndDate(t)
        return when (anchor) {
            SessionAnchor.CurrentSession -> closeOn(end).toEpochMilli()
            SessionAnchor.PreviousDay, SessionAnchor.PreviousSession -> closeOn(previousTradingDay(end)).toEpochMilli()
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }
    }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange {
        val end = Instant.ofEpochMilli(anchorEpoch)
        return when (anchor) {
            SessionAnchor.CurrentSession, SessionAnchor.PreviousDay, SessionAnchor.PreviousSession ->
                rangeEndingOn(end.atZone(zone).toLocalDate())
            is SessionAnchor.Rolling -> TimeRange(end.minus(anchor.duration), end)
        }
    }

    /** The trading day whose 16:00 close ends the session [t] is in, or the next one when [t] is between sessions. */
    private fun sessionEndDate(t: Instant): LocalDate {
        val local = t.atZone(zone)
        var date = if (local.toLocalTime() >= close) local.toLocalDate().plusDays(1) else local.toLocalDate()
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) date = date.plusDays(1)
        return date
    }

    private fun previousTradingDay(date: LocalDate): LocalDate {
        var previous = date.minusDays(1)
        while (previous.dayOfWeek == DayOfWeek.SATURDAY ||
            previous.dayOfWeek == DayOfWeek.SUNDAY
        ) {
            previous = previous.minusDays(1)
        }
        return previous
    }

    /** The session closing on trading day [date]: from 17:00 the evening before to 16:00 on [date]. */
    private fun rangeEndingOn(date: LocalDate): TimeRange =
        TimeRange(
            date
                .minusDays(1)
                .atTime(open)
                .atZone(zone)
                .toInstant(),
            closeOn(date),
        )

    private fun closeOn(date: LocalDate): Instant = date.atTime(close).atZone(zone).toInstant()
}
