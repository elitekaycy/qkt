package com.qkt.broker.continuous

import com.qkt.broker.StreamStopRelease
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * Clears the stop a failed roll left on a strategy, when nothing of the strategy remains on the stream: no
 * position, no contract holding in the lane's [book], no order working or being cancelled, no roll leg out, and no
 * roll in progress. A stop only an operator's resume asks to clear: it is never lifted on a restart or by itself.
 */
internal class LaneStopRelease(
    private val chainOf: () -> ContinuousChain,
    private val positions: Map<String, BigDecimal>,
    private val stops: MutableMap<String, String>,
    private val orders: ContinuousOrderMap,
    private val book: StrategyPositionTracker,
    private val legs: RollLegs,
    private val rolls: RollExecutor,
) {
    /** Clears [strategyId]'s stop if it is safe, and says what happened; null when the strategy has no stop here. */
    fun release(strategyId: String): StreamStopRelease? {
        val stop = stops[strategyId] ?: return null
        val stands = holdups(strategyId).joinToString("; ").ifEmpty { null }
        if (stands == null) stops.remove(strategyId)
        return StreamStopRelease(strategyId, chainOf().symbol, stop, stands)
    }

    private fun holdups(strategyId: String): List<String> =
        buildList {
            if (rolls.inFlight) add("a roll is in progress")
            positions[strategyId]?.takeIf { it.signum() != 0 }?.let { add("its position is ${it.toPlainString()}") }
            book
                .positionsFor(strategyId)
                .values
                .filter { it.quantity.signum() != 0 }
                .forEach { add("the venue holds ${it.quantity.toPlainString()} of ${it.symbol} for it") }
            val working = orders.all + legs.awaitedCancels
            working
                .filter { it.request.strategyId == strategyId }
                .map { it.request.id }
                .distinct()
                .forEach { add("order $it is still working") }
            legs.inFlight
                .filter { it.leg.strategyId == strategyId }
                .forEach { add("roll leg ${it.leg.id} is still out") }
        }
}
