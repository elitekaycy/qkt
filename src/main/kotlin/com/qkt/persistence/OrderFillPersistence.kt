package com.qkt.persistence

import java.math.BigDecimal

/**
 * What a live order had filled when last saved: [filledQuantity] at [avgFillPrice]. A restart hands it
 * back with the order, so the venue's recovery books only the fills made since and never one the
 * position ledger already holds (#1329).
 */
data class PersistedOrderFill(
    val filledQuantity: BigDecimal,
    val avgFillPrice: BigDecimal?,
)

/**
 * Storage for the fill progress of a strategy's live, partly filled orders, by client order id. Kept
 * apart from the rest of [StatePersistor] so the defaults leave persistors that predate it compiling;
 * a persistor that keeps nothing restores every order as unfilled.
 */
interface OrderFillPersistence {
    /** Persist [strategyId]'s partly filled live orders, replacing the last save; empty clears them. */
    fun saveOrderFills(
        strategyId: String,
        fills: Map<String, PersistedOrderFill>,
    ) {}

    /** [strategyId]'s partly filled live orders as last saved; empty when none were. */
    fun loadOrderFills(strategyId: String): Map<String, PersistedOrderFill> = emptyMap()
}
