package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * New York Stock Exchange regular hours: 09:30-16:00 America/New_York on trading days, 09:30-13:00 on the early-close
 * days, closed at weekends, on exchange holidays and on the one-off closures [NyseSessions] lists. Daylight saving is
 * followed through the zone, so the open is 13:30 UTC in summer and 14:30 UTC in winter. Pre-market and after-hours
 * trading are not part of the session.
 */
object NyseCalendar : TradingCalendar {
    override val name: String = "nyse"

    private val zone = ZoneId.of("America/New_York")
    private val openTime = LocalTime.of(9, 30)

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val zdt = t.atZone(zone)
        val date = zdt.toLocalDate()
        if (!NyseSessions.isTradingDay(date)) return false
        val tod = zdt.toLocalTime()
        return tod >= openTime && tod < NyseSessions.closeOn(date)
    }

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange = sessionOn(t.atZone(zone).toLocalDate())

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long {
        val date = t.atZone(zone).toLocalDate()
        return when (anchor) {
            SessionAnchor.CurrentSession -> date.toEpochDay()
            SessionAnchor.PreviousDay,
            SessionAnchor.PreviousSession,
            -> NyseSessions.previousTradingDay(date).toEpochDay()
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
            -> sessionOn(LocalDate.ofEpochDay(anchorEpoch))
            is SessionAnchor.Rolling -> {
                val end = Instant.ofEpochMilli(anchorEpoch)
                TimeRange(end.minus(anchor.duration), end)
            }
        }

    /** A holiday or special closure closes the whole session, so data coverage reports it as such, not a weekday hole. */
    override fun isClosedSession(
        symbol: String,
        session: TimeRange,
    ): Boolean = NyseSessions.isClosed(session.from.atZone(zone).toLocalDate())

    /**
     * The candle-grid bars a year of NYSE sessions touches: a bar is formed when any of its time falls inside a session,
     * the way equity is sampled on candle closes. Counted over [TradingPeriods.REFERENCE_FROM] to
     * [TradingPeriods.REFERENCE_TO] (four 365.25-day years) and divided by four, so a 1d window gives the average
     * trading days a year (251.25) and a 1h window seven bars per regular session (the 13:30 UTC summer open shares
     * the 13:00 bar).
     */
    override fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal {
        var bars = 0L
        var lastBar = Long.MIN_VALUE
        var day = TradingPeriods.REFERENCE_FROM
        while (day.isBefore(TradingPeriods.REFERENCE_TO)) {
            if (NyseSessions.isTradingDay(day)) {
                val session = sessionOn(day)
                val first = maxOf(Math.floorDiv(session.from.toEpochMilli(), window.durationMs), lastBar + 1)
                val last = Math.floorDiv(session.to.toEpochMilli() - 1, window.durationMs)
                if (last >= first) {
                    bars += last - first + 1
                    lastBar = last
                }
            }
            day = day.plusDays(1)
        }
        return BigDecimal(bars).divide(BigDecimal(TradingPeriods.REFERENCE_YEARS), Money.CONTEXT)
    }

    private fun sessionOn(date: LocalDate): TimeRange =
        TimeRange(
            date.atTime(openTime).atZone(zone).toInstant(),
            date.atTime(NyseSessions.closeOn(date)).atZone(zone).toInstant(),
        )
}
