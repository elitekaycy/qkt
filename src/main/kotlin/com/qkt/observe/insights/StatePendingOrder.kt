package com.qkt.observe.insights

import java.math.BigDecimal

/** One resting venue order as the "state.orders" payload carries it. */
data class StatePendingOrder(
    val ticket: String,
    val symbol: String,
    /** "BUY" or "SELL". */
    val side: String,
    /** Venue order type string, e.g. "ORDER_TYPE_BUY_LIMIT". */
    val orderType: String,
    val qty: BigDecimal,
    val price: BigDecimal?,
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    val expiresAt: Long? = null,
    val createdAt: Long? = null,
    val magic: Int? = null,
    val clientOrderId: String? = null,
    val strategyId: String? = null,
)
