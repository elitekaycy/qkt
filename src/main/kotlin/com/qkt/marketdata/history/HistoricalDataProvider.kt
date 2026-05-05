package com.qkt.marketdata.history

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick

enum class DataCapability {
    TICKS,
    CANDLES_INTRADAY,
    CANDLES_DAILY,
}

class UnsupportedDataException(
    capability: DataCapability,
    providerClass: String,
) : RuntimeException("$providerClass does not support $capability")

interface HistoricalDataProvider {
    val capabilities: Set<DataCapability>

    fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick>

    fun candles(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle>
}
