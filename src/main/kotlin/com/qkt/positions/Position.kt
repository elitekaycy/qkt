package com.qkt.positions

import java.math.BigDecimal

/**
 * A net position on a symbol, tracked by [StrategyPositionTracker].
 *
 * [openedAt] is the timestamp (millis since epoch) when the position transitioned
 * from flat to its current side. Preserved when adding to a same-direction
 * position; reset when the position closes or flips. `null` only on positions
 * created without a timestamp source (legacy paths) — production tracking always
 * sets it.
 */
data class Position(
    val symbol: String,
    val quantity: BigDecimal,
    val avgEntryPrice: BigDecimal,
    val openedAt: Long? = null,
)
