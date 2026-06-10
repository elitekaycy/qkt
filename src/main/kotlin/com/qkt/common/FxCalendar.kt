package com.qkt.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

object FxCalendar : TradingCalendar {
    override val name: String = "fx"

    private const val SESSION_START_UTC_HOUR = 22

    /**
     * FX trades ~24h x 5 days: 5/7 of the year's minutes. Powers Sharpe
     * annualization for FX backtests; e.g. 1m windows -> ~375,686 periods/year.
     */
    override fun tradingPeriodsPerYear(window: com.qkt.candles.TimeWindow): java.math.BigDecimal {
        val minutesPerYear =
            java.math
                .BigDecimal("525960")
                .multiply(java.math.BigDecimal(5))
                .divide(java.math.BigDecimal(7), Money.CONTEXT)
        val windowMinutes =
            java.math.BigDecimal(window.durationMs).divide(java.math.BigDecimal("60000"), Money.CONTEXT)
        return minutesPerYear.divide(windowMinutes, Money.CONTEXT)
    }

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val zdt = t.atZone(ZoneOffset.UTC)
        val dow = zdt.dayOfWeek.value
        val hour = zdt.hour
        return when (dow) {
            6 -> false
            7 -> hour >= SESSION_START_UTC_HOUR
            5 -> hour < SESSION_START_UTC_HOUR
            else -> true
        }
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val end = sessionEnd(t)
        val start = end.minus(Duration.ofDays(1))
        return TimeRange(start, end)
    }

    private fun sessionEnd(t: Instant): Instant {
        val day = t.atZone(ZoneOffset.UTC).toLocalDate()
        val candidate = day.atTime(SESSION_START_UTC_HOUR, 0).toInstant(ZoneOffset.UTC)
        return if (t < candidate) candidate else candidate.plus(Duration.ofDays(1))
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val end = sessionEnd(t)
        return when (anchor) {
            SessionAnchor.CurrentSession -> end.toEpochMilli()
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            -> end.minus(Duration.ofDays(1)).toEpochMilli()
            is SessionAnchor.Rolling -> t.toEpochMilli()
        }
    }

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange {
        val end = Instant.ofEpochMilli(anchorEpoch)
        return when (anchor) {
            SessionAnchor.CurrentSession,
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            -> TimeRange(end.minus(Duration.ofDays(1)), end)
            is SessionAnchor.Rolling -> TimeRange(end.minus(anchor.duration), end)
        }
    }
}
