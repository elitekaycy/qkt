package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

data class Order(
    val id: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val type: OrderType,
    val price: BigDecimal? = null,
    val timestamp: Long,
)
