package com.qkt.positions

interface StrategyPositionView {
    fun positionFor(symbol: String): Position?

    fun allPositions(): Map<String, Position>
}

internal class StrategyPositionViewImpl(
    private val tracker: StrategyPositionTracker,
    private val strategyId: String,
) : StrategyPositionView {
    override fun positionFor(symbol: String): Position? = tracker.positionFor(strategyId, symbol)

    override fun allPositions(): Map<String, Position> = tracker.positionsFor(strategyId)
}
