package com.qkt.common

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The FX week and day on New York time: the week opens Sunday 17:00 America/New_York and closes Friday
 * 17:00, and each FX day runs from one 17:00 New York to the next. In UTC that is 21:00 during US daylight
 * saving and 22:00 otherwise, matching brokers that run on New York-close server time (a summer EURUSD
 * week's last tick is Friday 20:59:59 UTC). A day spanning a daylight-saving change is 23 or 25 hours.
 *
 * The FX days closing on Dec 25, Jan 1 and Good Friday are full closures for metals and most FX venues
 * ([isClosedSession]); e.g. the Good Friday 2024 day runs 2024-03-28 21:00 UTC to 2024-03-29 21:00 UTC. They
 * stay in session for live gating ([isInSession] is the plain 24x5 week), and data coverage does not require them.
 */
object FxCalendar : TradingCalendar {
    override val name: String = "fx"

    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    private val CLOSE: LocalTime = LocalTime.of(17, 0)

    /**
     * The bars a year an FX symbol forms at [window], which annualizes FX Sharpe. FX trades 24h x 5 days, 5/7 of the
     * year's minutes: 1m windows give ~375,686 periods, 1h 6,261.43. A window longer than an hour also counts the bars
     * the Sunday open and Friday close cut short: a daily FX series forms six UTC bars a week, 313 a year, not 260.9.
     */
    override fun tradingPeriodsPerYear(window: com.qkt.candles.TimeWindow): java.math.BigDecimal =
        TradingPeriods.forWeeklySession(this, window, openShare = 5, weekShare = 7)

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val ny = t.atZone(NEW_YORK)
        val beforeClose = ny.toLocalTime().isBefore(CLOSE)
        return when (ny.dayOfWeek) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> !beforeClose
            DayOfWeek.FRIDAY -> beforeClose
            else -> true
        }
    }

    override fun isClosedSession(
        symbol: String,
        session: TimeRange,
    ): Boolean {
        val day = session.to.atZone(NEW_YORK).toLocalDate()
        return (day.monthValue == 12 && day.dayOfMonth == 25) ||
            (day.monthValue == 1 && day.dayOfMonth == 1) ||
            day == WesternEaster.goodFriday(day.year)
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val end = sessionEnd(t)
        return TimeRange(dayBefore(end), end)
    }

    private fun sessionEnd(t: Instant): Instant {
        val day = t.atZone(NEW_YORK).toLocalDate()
        val candidate = day.atTime(CLOSE).atZone(NEW_YORK).toInstant()
        return if (t < candidate) {
            candidate
        } else {
            day
                .plusDays(1)
                .atTime(CLOSE)
                .atZone(NEW_YORK)
                .toInstant()
        }
    }

    /** The 17:00 New York one day before [close], which is 23 or 25 hours earlier across a DST change. */
    private fun dayBefore(close: Instant): Instant =
        close
            .atZone(NEW_YORK)
            .minusDays(1)
            .toInstant()

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val end = sessionEnd(t)
        return when (anchor) {
            SessionAnchor.CurrentSession -> end.toEpochMilli()
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            -> dayBefore(end).toEpochMilli()
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
            -> TimeRange(dayBefore(end), end)
            is SessionAnchor.Rolling -> TimeRange(end.minus(anchor.duration), end)
        }
    }
}
