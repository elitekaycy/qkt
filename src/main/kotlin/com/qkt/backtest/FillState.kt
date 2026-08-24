package com.qkt.backtest

import com.qkt.positions.Position
import java.math.BigDecimal

/**
 * Position snapshot captured around a single fill.
 *
 * The report bundle can use this to show the fill's entry/exit context without asking Forge
 * to reconstruct state from the tape alone. [netAccountRealized] is the per-strategy
 * account-currency PnL after modeled and venue-reported costs; [reducedExposure] marks fills
 * that closed or reduced an existing strategy position.
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
    /** Leg the fill was routed to when leg-routed (hedging/stack/OCO books, #1071). */
    val legId: String? = null,
    /** How the fill landed in the strategy leg book; null for pre-#1071 producers. */
    val legAction: com.qkt.positions.StrategyPositionTracker.LegAction? = null,
)
