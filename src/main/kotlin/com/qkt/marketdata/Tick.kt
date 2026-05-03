package com.qkt.marketdata

import java.math.BigDecimal

data class Tick(
    val symbol: String,
    val price: BigDecimal,
    val timestamp: Long,
    val volume: BigDecimal? = null,
)
