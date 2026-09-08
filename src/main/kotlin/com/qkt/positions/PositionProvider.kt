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

    /**
     * Count of this strategy's live, not-yet-filled entry orders on [side]. A per-day trade cap
     * that counts only filled entries is outrun by a burst: the whole burst is risk-checked before
     * any of it fills, so every order reads the same pre-burst total. [side] separates entries from
     * exits — an open position's protective legs rest on the opposite side and stay live until it
     * closes, so counting both sides reports a phantom entry for every filled position.
     */
    fun pendingEntryOrderCount(
        side: Side,
        strategyId: String? = null,
    ): Int = 0
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

    /** Number of live, not-yet-filled entry orders on [side] in the requested strategy scope. */
    fun orderCountFor(
        side: Side,
        strategyId: String?,
    ): Int = 0

    companion object {
        /** Provider used when no order manager has been bound. */
        val NONE = PendingOrderExposureProvider { _, _, _ -> Money.ZERO }
    }
}
