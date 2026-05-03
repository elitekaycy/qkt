package com.qkt.execution

import com.qkt.common.Side

data class Trade(
    val orderId: String,
    val symbol: String,
    val price: Double,
    val quantity: Double,
    val side: Side,
    val timestamp: Long
)
