package com.qkt.execution

import com.qkt.common.Side

data class Order(
    val id: String,
    val symbol: String,
    val side: Side,
    val quantity: Double,
    val type: OrderType,
    val price: Double? = null,
    val timestamp: Long,
)
