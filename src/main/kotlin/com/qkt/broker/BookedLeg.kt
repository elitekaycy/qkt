package com.qkt.broker

/**
 * A position-ledger leg pinned to a venue ticket, as the broker needs to see it: enough to
 * synthesize the venue close the engine missed when the ticket is gone.
 */
data class BookedLeg(
    val strategyId: String,
    val legId: String,
    val ticket: String,
    /** qkt symbol with broker prefix, e.g. "EXNESS:XAUUSD". */
    val symbol: String,
    val side: com.qkt.common.Side,
    val quantity: java.math.BigDecimal,
    val entryPrice: java.math.BigDecimal,
    val openedAt: Long,
)
