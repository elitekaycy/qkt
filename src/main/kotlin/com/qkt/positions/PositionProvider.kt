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

    companion object {
        /** Provider used when no order manager has been bound. */
        val NONE = PendingOrderExposureProvider { _, _, _ -> Money.ZERO }
    }
}
