package com.qkt.marketdata

data class Tick(
    val symbol: String,
    val price: Double,
    val timestamp: Long,
    val volume: Double? = null,
)
