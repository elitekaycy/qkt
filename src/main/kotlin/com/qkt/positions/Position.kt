package com.qkt.positions

import java.math.BigDecimal

data class Position(
    val symbol: String,
    val quantity: BigDecimal,
    val avgEntryPrice: BigDecimal,
)
