package com.qkt.backtest

import com.qkt.positions.Position
import java.math.BigDecimal

/**
 * Position snapshot captured around a single fill.
 *
 * The report bundle can use this to show the fill's entry/exit context without asking Forge
 * to reconstruct state from the tape alone.
 */
data class FillState(
    val accountPositionBefore: Position?,
    val accountPositionAfter: Position?,
    val strategyPositionBefore: Position?,
    val strategyPositionAfter: Position?,
    val contractSize: BigDecimal? = null,
    /** Net account-currency P&L after modeled and venue-reported fill costs. */
    val netAccountRealized: BigDecimal = BigDecimal.ZERO,
    /** Whether this fill reduced an existing strategy-owned position. */
    val reducedExposure: Boolean = false,
)
