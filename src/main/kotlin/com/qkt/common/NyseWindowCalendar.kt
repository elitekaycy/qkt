package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * NYSE trading days with a session window in America/New_York: the days [NyseSessions] says the exchange trades,
 * each open from [open] to [close], except that on an early-close day the session ends at the exchange's early close
 * when [close] is later. `nyse` itself is the 09:30-16:00 window; a broker quoting stock CFDs from 06:00 to 15:45
 * New York is `nyse[06:00-15:45]`. Daylight saving follows the zone.
 */
open class NyseWindowCalendar(
    val open: LocalTime,
    val close: LocalTime,
    override val name: String = "nyse[$open-$close]",
) : TradingCalendar {
    init {
        require(open < close) { "calendar $name: the session must open before it closes" }
        // Every NYSE trading day then has a session, early-close days included.
        require(open < NyseSessions.EARLY_CLOSE) { "calendar $name: the session must open before the 13:00 early close" }
    }

    private val zone = ZoneId.of("America/New_York")

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean {
        val zdt = t.atZone(zone)
        val date = zdt.toLocalDate()
        if (!NyseSessions.isTradingDay(date)) return false
        val tod = zdt.toLocalTime()
        return tod >= open && tod < closeOn(date)
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
     * The candle-grid bars a year of these sessions touches: a bar is formed when any of its time falls inside a
     * session, the way equity is sampled on candle closes. Counted over [TradingPeriods.REFERENCE_FROM] to
     * [TradingPeriods.REFERENCE_TO] (four 365.25-day years) and divided by four.
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

    /** The session close on [date]: the window's close, or on an early-close day the exchange's close when earlier. */
    private fun closeOn(date: LocalDate): LocalTime {
        val exchange = NyseSessions.closeOn(date)
        val early = exchange < NyseSessions.REGULAR_CLOSE
        return if (early) minOf(close, exchange) else close
    }

    private fun sessionOn(date: LocalDate): TimeRange =
        TimeRange(
            date.atTime(open).atZone(zone).toInstant(),
            date.atTime(closeOn(date)).atZone(zone).toInstant(),
        )

    companion object {
        private val SPEC = Regex("""nyse\[(\d{2}):(\d{2})-(\d{2}):(\d{2})]""")

        /** The calendar `nyse[HH:MM-HH:MM]` names, or null when [spec] is not that form. */
        fun parse(spec: String): NyseWindowCalendar? {
            val m = SPEC.matchEntire(spec.trim().lowercase()) ?: return null
            val (oh, om, ch, cm) = m.destructured.toList().map(String::toInt)
            require(oh < 24 && om < 60 && ch < 24 && cm < 60) { "calendar '$spec': times are HH:MM, 00:00 to 23:59" }
            return NyseWindowCalendar(LocalTime.of(oh, om), LocalTime.of(ch, cm))
        }
    }
}
