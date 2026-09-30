package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.RollHistory
import java.time.Instant

/** One stretch of a continuous stream served by contract [index], over `[fromMs, toMs)`. */
data class ChainSegment(
    val index: Int,
    val fromMs: Long,
    val toMs: Long,
)

/**
 * A continuous futures stream: which contract of [root] the [selector] follows at any instant, and
 * the forward adjustment that joins them, taken from the measured [history]. Pure and immutable; the
 * same catalog, policy and history give the same answers in backtest and live.
 *
 * The adjustment is anchored at the first contract the history has a roll for ([anchorIndex]) and
 * covers every contract up to the last measured roll; asking for a price mapping outside that range
 * fails with the command that measures more rolls.
 */
class ContinuousChain(
    private val root: FuturesRoot,
    catalog: ContractCatalog,
    history: RollHistory?,
    private val selector: ContinuousSelector,
) {
    private val policy =
        requireNotNull(root.roll) { "futures root ${root.root} has no roll policy; a continuous stream needs one" }

    /** The continuous symbol, e.g. `BINANCE_UM:BTCUSDT@front`. */
    val symbol: String = selector.symbolFor(root.root)

    /** When each contract is front. */
    val schedule: RollSchedule = RollSchedule(catalog.contracts, policy)

    /** The first contract this stream's adjustment is anchored at. */
    val anchorIndex: Int

    /**
     * The first instant this stream is served: the first measured roll. The anchor contract before it
     * is never served — the roll into it was not measurable (on Binance before late 2023 it was not
     * even listed yet), so its stretch could start before it traded.
     */
    val servedFromMs: Long

    private val adjustment: AdjustmentChain

    init {
        val measured = requireNotNull(history) { "no roll history for ${root.root}; build it with ${buildHint()}" }
        require(measured.policy == policy.key) {
            "roll history for ${root.root} was built for policy ${measured.policy}, " +
                "but the root rolls ${policy.key}; rebuild it with ${buildHint()}"
        }
        val prices = schedule.transitions.map { t -> pricesAt(measured, t) }
        val first = prices.indexOfFirst { it != null }
        require(first >= 0) { "roll history for ${root.root} has no roll for $symbol; build it with ${buildHint()}" }
        val run = prices.drop(first).takeWhile { it != null }.filterNotNull()
        val resumed = prices.drop(first + run.size).indexOfFirst { it != null }
        require(resumed < 0) {
            val missing = Instant.ofEpochMilli(schedule.transitions[first + run.size].atMs)
            "roll history for ${root.root} skips the $missing roll of $symbol; rebuild it with ${buildHint()}"
        }
        anchorIndex = first + selector.offset
        servedFromMs = schedule.transitions[first].atMs
        adjustment = AdjustmentChain(policy.adjust, run)
    }

    /** Contract index followed at [tMs], or null when this stream has no contract then. */
    fun indexAt(tMs: Long): Int? = schedule.indexAt(tMs, selector)

    /** `VENUE:CODE` of the contract followed at [tMs], or null. */
    fun contractSymbolAt(tMs: Long): String? = indexAt(tMs)?.let(::contractSymbol)

    /** `VENUE:CODE` of contract [index]. */
    fun contractSymbol(index: Int): String = "${root.venue}:${schedule.contracts[index].symbol}"

    /** The price mapping of contract [index]; fails outside the measured history. */
    fun spaceFor(index: Int): PriceSpace {
        val position = index - anchorIndex
        require(position >= 0) {
            "$symbol needs ${contractSymbol(index)}, before the roll history starts; build more with ${buildHint()}"
        }
        require(position < adjustment.size) {
            "$symbol needs ${contractSymbol(index)}, after the last measured roll; build more with ${buildHint()}"
        }
        return PriceSpace(policy.adjust, adjustment.shiftFor(position), root.tickSize)
    }

    /**
     * The contiguous contract stretches covering `[fromMs, toMs)`, starting no earlier than
     * [servedFromMs] and each clipped to its contract's expiry. Empty when the whole range is before
     * the stream is served.
     */
    fun segments(
        fromMs: Long,
        toMs: Long,
    ): List<ChainSegment> {
        val start = maxOf(fromMs, servedFromMs)
        if (start >= toMs) return emptyList()
        val rolls = schedule.transitions.map { it.atMs }.filter { it > start && it < toMs }
        return (listOf(start) + rolls + toMs)
            .zipWithNext()
            .mapNotNull { (a, b) -> indexAt(a)?.let { ChainSegment(it, a, minOf(b, schedule.contracts[it].expiryMs)) } }
            .filter { it.fromMs < it.toMs }
    }

    private fun pricesAt(
        history: RollHistory,
        transition: RollTransition,
    ): RollPrices? {
        val from = schedule.contracts.getOrNull(transition.fromIndex + selector.offset) ?: return null
        val to = schedule.contracts.getOrNull(transition.toIndex + selector.offset) ?: return null
        return history
            .find(
                transition.atMs,
                from.symbol,
                to.symbol,
            )?.let { RollPrices(it.fromPriceValue(), it.toPriceValue()) }
    }

    private fun buildHint(): String = "qkt fetch ${root.root} --rolls"
}
