package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.RollHistory
import java.time.Instant

/**
 * The one contiguous run of measured rolls a continuous stream is adjusted over: the rolls of
 * [schedule] for [selector] that [history] prices, from the first one ([first], an index into the
 * schedule's transitions) on. Fails when the history has no roll for the stream, or skips one inside
 * the run; [rebuild] names the command that measures more.
 */
internal class MeasuredRun(
    private val schedule: RollSchedule,
    history: RollHistory,
    private val selector: ContinuousSelector,
    symbol: String,
    rebuild: String,
) {
    /** The first transition the history prices. */
    val first: Int

    /** The reference prices of each roll of the run, in order. */
    val rolls: List<RollPrices>

    init {
        val prices = schedule.transitions.map { t -> pricesAt(history, t) }
        first = prices.indexOfFirst { it != null }
        require(first >= 0) { "roll history for ${history.root} has no roll for $symbol; build it with $rebuild" }
        rolls = prices.drop(first).takeWhile { it != null }.filterNotNull()
        val resumed = prices.drop(first + rolls.size).indexOfFirst { it != null }
        require(resumed < 0) {
            val missing = Instant.ofEpochMilli(schedule.transitions[first + rolls.size].atMs)
            "roll history for ${history.root} skips the $missing roll of $symbol; rebuild it with $rebuild"
        }
    }

    private fun pricesAt(
        history: RollHistory,
        transition: RollTransition,
    ): RollPrices? {
        val from = schedule.contracts.getOrNull(transition.fromIndex + selector.offset) ?: return null
        val to = schedule.contracts.getOrNull(transition.toIndex + selector.offset) ?: return null
        return history
            .find(transition.atMs, from.symbol, to.symbol)
            ?.let { RollPrices(it.fromPriceValue(), it.toPriceValue()) }
    }
}
