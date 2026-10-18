package com.qkt.backtest

import java.math.BigDecimal

/** One round trip: a position from its first entry fill until it is flat again, net of every fill's costs. */
data class TradeOutcome(
    /** Timestamp of the fill that closed the position, or of its last exit when it was still open at the end. */
    val closedAtMs: Long,
    /** Net account-currency P&L of all the position's fills, entry and exit commissions included. */
    val pnl: BigDecimal,
)
