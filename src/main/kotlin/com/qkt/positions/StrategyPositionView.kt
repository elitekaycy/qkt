package com.qkt.positions

import java.math.BigDecimal

interface StrategyPositionView {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>

    /**
     * Phase 27: current max-favorable-excursion of the PRIMARY leg on [symbol], or null
     * if no PRIMARY exists. Returned in price units (same scale as bracket `BY` distances).
     * Backs the DSL accessor `POSITION.<stream>.mfe`.
     */
    fun mfeFor(symbol: String): BigDecimal? = null
}

internal class StrategyPositionViewImpl(
    private val tracker: StrategyPositionTracker,
    private val strategyId: String,
) : StrategyPositionView {
    override fun positionFor(symbol: String): Position? = tracker.positionFor(strategyId, symbol)

    override fun allPositions(): Map<String, Position> = tracker.positionsFor(strategyId)

    override fun mfeFor(symbol: String): BigDecimal? = tracker.primaryMfeFor(strategyId, symbol)
}
