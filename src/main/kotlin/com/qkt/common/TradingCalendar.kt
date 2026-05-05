package com.qkt.common

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

    companion object {
        fun crypto(): TradingCalendar = CryptoCalendar

        fun fxDefault(): TradingCalendar = FxCalendar

        fun nyse(): TradingCalendar = NyseCalendar
    }
}
