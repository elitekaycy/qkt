package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Instant

interface TradingCalendar {
    val name: String

    fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean

    fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange

    fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long

    fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange

    fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal = error("tradingPeriodsPerYear not implemented for $name")

    /**
     * True when [t] falls inside a scheduled intraday pause for [symbol] — a venue-published
     * break such as the metals/energy close at 17:00 New York. Distinct from [isInSession]:
     * feed polling and venue-state pollers key off the session (a break is too short to back
     * off for), while the market-data gate uses the break to classify a quote gap as expected
     * rather than as a feed fault. Defaults to never.
     */
    fun isScheduledBreak(
        symbol: String,
        t: Instant,
    ): Boolean = false

    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar

        fun fxDefault(): TradingCalendar = FxCalendar

        fun nyse(): TradingCalendar = NyseCalendar
    }
}
