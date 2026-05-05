package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

interface MarketSource {
    val name: String
    val capabilities: Set<MarketSourceCapability>

    fun supports(symbol: String): Boolean

    fun liveTicks(symbols: List<String>): TickFeed =
        throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, this::class.java.simpleName ?: "MarketSource")

    fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        throw UnsupportedDataException(MarketSourceCapability.BARS, this::class.java.simpleName ?: "MarketSource")

    fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> =
        throw UnsupportedDataException(MarketSourceCapability.TICKS, this::class.java.simpleName ?: "MarketSource")
}
