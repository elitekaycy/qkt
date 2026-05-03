package com.qkt.marketdata

import java.math.BigDecimal

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
