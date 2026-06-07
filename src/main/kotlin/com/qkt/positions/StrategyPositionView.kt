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

    /** Real number of open positions (legs) on [symbol] — not the net. Backs `POSITION.<stream>.count`. */
    fun openCountFor(symbol: String): Int = 0

    /** Open long-side legs on [symbol]. Backs `POSITION.<stream>.longs`. */
    fun longCountFor(symbol: String): Int = 0

    /** Open short-side legs on [symbol]. Backs `POSITION.<stream>.shorts`. */
    fun shortCountFor(symbol: String): Int = 0

    /** Gross exposure (side-blind sum of leg sizes) on [symbol]. Backs `POSITION.<stream>.gross`. */
    fun grossFor(symbol: String): BigDecimal = BigDecimal.ZERO
}

internal class StrategyPositionViewImpl(
    private val tracker: StrategyPositionTracker,
    private val strategyId: String,
) : StrategyPositionView {
    override fun positionFor(symbol: String): Position? = tracker.positionFor(strategyId, symbol)

    override fun allPositions(): Map<String, Position> = tracker.positionsFor(strategyId)

    override fun mfeFor(symbol: String): BigDecimal? = tracker.primaryMfeFor(strategyId, symbol)

    override fun openCountFor(symbol: String): Int = tracker.openCountFor(strategyId, symbol)

    override fun longCountFor(symbol: String): Int = tracker.longCountFor(strategyId, symbol)

    override fun shortCountFor(symbol: String): Int = tracker.shortCountFor(strategyId, symbol)

    override fun grossFor(symbol: String): BigDecimal = tracker.grossFor(strategyId, symbol)
}
