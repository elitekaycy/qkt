package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import java.math.BigDecimal

interface PositionProvider {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>

    /**
     * Quantity of live, not-yet-filled entry orders on [side]. [strategyId] null means
     * account-wide; a non-null id scopes the reservation to that strategy.
     */
    fun pendingOrderQuantity(
        symbol: String,
        side: Side,
        strategyId: String? = null,
    ): BigDecimal = Money.ZERO

    /**
     * Symbols with an open position, without copying the backing map — [allPositions] copies,
     * which is wasteful for per-tick sweeps like unrealized-PnL totals. The returned set is a
     * live view where the implementation allows; callers must not retain it across mutations.
     */
    fun symbols(): Set<String> = allPositions().keys

    /**
     * Symbols this strategy has live, not-yet-filled entry orders on. A concurrency limit that
     * counts only filled positions can be outrun by a burst: every order in the burst is checked
     * before any of its fills come back, so each one sees an empty book and is approved. Counting
     * in-flight symbols closes that, and makes the limit bind identically whether fills arrive
     * between submissions (backtest) or after all of them (live).
     */
    fun pendingEntrySymbols(strategyId: String? = null): Set<String> = emptySet()
}

/**
 * A [PositionProvider] that can also expose the individual legs behind a symbol's net position.
 * Per-leg valuation is what keeps a hedged pair's locked loss visible when its net is zero.
 */
interface LegExposureProvider : PositionProvider {
    /** Visit every open leg on [symbol] across strategies, in place — no snapshot. */
    fun forEachLeg(
        symbol: String,
        action: (PositionLeg) -> Unit,
    )
}

/** Supplies live pending-entry exposure to pre-trade position-cap rules. */
fun interface PendingOrderExposureProvider {
    /** Return not-yet-filled entry quantity for the requested symbol, side, and strategy scope. */
    fun quantityFor(
        symbol: String,
        side: Side,
        strategyId: String?,
    ): BigDecimal

    /** Symbols with live, not-yet-filled entry orders in the requested strategy scope. */
    fun symbolsFor(strategyId: String?): Set<String> = emptySet()

    companion object {
        /** Provider used when no order manager has been bound. */
        val NONE = PendingOrderExposureProvider { _, _, _ -> Money.ZERO }
    }
}
