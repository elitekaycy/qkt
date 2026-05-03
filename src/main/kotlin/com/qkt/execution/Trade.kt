package com.qkt.execution

import com.qkt.common.Side
import java.math.BigDecimal

data class Trade(
    val orderId: String,
    val symbol: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val side: Side,
    val timestamp: Long,
)
