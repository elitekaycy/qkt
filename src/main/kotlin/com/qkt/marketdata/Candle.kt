package com.qkt.marketdata

data class Candle(
    val symbol: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val startTime: Long,
    val endTime: Long
)
