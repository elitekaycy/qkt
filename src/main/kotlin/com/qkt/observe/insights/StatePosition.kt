package com.qkt.observe.insights

import java.math.BigDecimal

/**
 * One open venue position as the "state.positions" payload carries it — a
 * [com.qkt.broker.BrokerPositionTicket] plus the strategy id the poller attributed
 * (null when the ticket is an orphan this daemon cannot claim).
 */
data class StatePosition(
    val ticket: String,
    val symbol: String,
    /** "BUY" or "SELL". */
    val side: String,
    val qty: BigDecimal,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal?,
    val profit: BigDecimal?,
    val swap: BigDecimal?,
    val openedAt: Long?,
    val strategyId: String?,
    /** Venue-side protective levels; null when unsupported, zero when absent on MT5. */
    val stopLoss: BigDecimal? = null,
    val takeProfit: BigDecimal? = null,
    /** What qkt last requested — differs from venue truth while a modify is in flight. */
    val requestedStopLoss: BigDecimal? = null,
    val requestedTakeProfit: BigDecimal? = null,
    val magic: Int? = null,
    val clientOrderId: String? = null,
)
