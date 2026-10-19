package com.qkt.marketdata.store

import com.qkt.common.FxCalendar
import com.qkt.common.SessionAnchor
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The calendar data coverage judges a symbol by when qkt does not know its trading hours: every symbol without
 * a calendar rule falls back to the FX week, which is right for currency and metal pairs (`EURUSD`, `XAUUSD`,
 * `EURUSDm`) but not for a stock (`IBM`, trading 13:30-20:00 UTC) or a futures root (`ES`). For those each UTC
 * weekday is one session that must hold data, a stretch over 90 minutes without data between two stored points
 * is a hole, and how late a day opens or how early it closes is not judged, since the hours are unknown.
 */
internal object UnknownHoursCalendar : TradingCalendar {
    override val name: String = "unknown hours (UTC weekdays)"

    private val PAIR = Regex("""^[A-Z]{6}([._+-]?[A-Za-z0-9]{1,3})?$""")

    /** The calendar coverage uses for [symbol] given its trading [calendar]: this one for a non-pair on the FX default. */
    fun coverageCalendar(
        calendar: TradingCalendar,
        symbol: String,
    ): TradingCalendar = if (calendar === FxCalendar && !PAIR.matches(symbol.substringAfter(':'))) this else calendar

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean = t.atZone(ZoneOffset.UTC).dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange {
        val start =
            t
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        return TimeRange(start, start.plus(Duration.ofDays(1)))
    }

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long = sessionRange("", t).from.toEpochMilli()

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange = sessionRange("", Instant.ofEpochMilli(anchorEpoch))
}
