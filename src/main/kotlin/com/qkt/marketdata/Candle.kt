package com.qkt.marketdata

import java.math.BigDecimal

/**
 * OHLC candle aggregated over a closed time window.
 *
 * Produced by the candle aggregator on its `EVERY` boundary and published as
 * [com.qkt.events.CandleEvent]. `endTime` is exclusive — the window covers
 * `[startTime, endTime)`.
 */
data class Candle(
    val symbol: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val startTime: Long,
    val endTime: Long,
)
