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

    fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal =
        error("tradingPeriodsPerYear not implemented for $name")

    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar

        fun fxDefault(): TradingCalendar = FxCalendar

        fun nyse(): TradingCalendar = NyseCalendar
    }
}
