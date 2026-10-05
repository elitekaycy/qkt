package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.PriceAdjustment
import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollPolicy
import java.time.Instant

/** One stretch of a continuous stream served by contract [index], over `[fromMs, toMs)`. */
data class ChainSegment(
    val index: Int,
    val fromMs: Long,
    val toMs: Long,
)

/**
 * A continuous futures stream: which contract of [root] the [selector] follows at any instant, and
 * the adjustment that joins them, taken from the measured [history]. Pure and immutable; the same
 * catalog, policy and history give the same answers in backtest and live.
 *
 * The adjustment covers every contract from the first one the history has a roll for ([firstIndex])
 * up to the last measured roll; asking for a price mapping outside that range fails with the command
 * that measures more rolls. It is anchored at the policy's [RollPolicy.anchor] contract when it
 * declares one, which must lie in that range, and otherwise at [firstIndex] (forward adjustment).
 */
class ContinuousChain(
    /** The root this stream follows. */
    val root: FuturesRoot,
    catalog: ContractCatalog,
    history: RollHistory?,
    private val selector: ContinuousSelector,
) {
    private val policy =
        requireNotNull(root.roll) { "futures root ${root.root} has no roll policy; a continuous stream needs one" }

    /** The continuous symbol, e.g. `BINANCE_UM:BTCUSDT@front`. */
    val symbol: String = selector.symbolFor(root.root)

    /** How this stream's series is adjusted across rolls. */
    val adjust: PriceAdjustment get() = policy.adjust

    /** When each contract is front. */
    val schedule: RollSchedule = RollSchedule(catalog.contracts, policy)

    /** The first contract this stream's adjustment covers. */
    val firstIndex: Int

    /**
     * The first instant this stream is served: the first measured roll. The anchor contract before it
     * is never served — the roll into it was not measurable (on Binance before late 2023 it was not
     * even listed yet), so its stretch could start before it traded.
     */
    val servedFromMs: Long

    private val adjustment: AdjustmentChain
    private val measuredRolls: Map<Int, MeasuredRoll>

    init {
        val measured = requireNotNull(history) { "no roll history for ${root.root}; build it with ${buildHint()}" }
        require(measured.policy == policy.key) {
            "roll history for ${root.root} was built for policy ${measured.policy}, " +
                "but the root rolls ${policy.key}; rebuild it with ${buildHint()}"
        }
        val run = MeasuredRun(schedule, measured, selector, symbol, buildHint())
        val first = run.first
        firstIndex = first + selector.offset
        servedFromMs = schedule.transitions[first].atMs
        if (policy.adjust == PriceAdjustment.PANAMA) requireRollsBeforeGuard(schedule.transitions.drop(first))
        adjustment = AdjustmentChain(policy.adjust, run.rolls, anchorPosition(run.rolls.size + 1))
        measuredRolls =
            run.rolls.withIndex().associate { (k, roll) ->
                val transition = schedule.transitions[first + k]
                transition.fromIndex + selector.offset to MeasuredRoll(transition.atMs, roll)
            }
    }

    /**
     * The first instant this stream has no contract: the last contract's expiry for `@front`; for
     * `@next`, the roll that makes its last contract the front one. A chain is only built with a
     * measured roll for its selector, so that roll exists.
     */
    val endsAtMs: Long
        get() =
            if (selector.offset == 0) {
                schedule.contracts.last().expiryMs
            } else {
                schedule.transitions[schedule.contracts.size - selector.offset - 1].atMs
            }

    /** Why this stream ends at [endsAtMs], for messages. */
    val endReason: String
        get() =
            if (selector.offset ==
                0
            ) {
                "its catalog's last contract expires"
            } else {
                "its last contract becomes the front one"
            }

    /** Contract index followed at [tMs], or null when this stream has no contract then. */
    fun indexAt(tMs: Long): Int? = schedule.indexAt(tMs, selector)

    /** `VENUE:CODE` of the contract followed at [tMs], or null. */
    fun contractSymbolAt(tMs: Long): String? = indexAt(tMs)?.let(::contractSymbol)

    /** `VENUE:CODE` of contract [index]. */
    fun contractSymbol(index: Int): String = "${root.venue}:${schedule.contracts[index].symbol}"

    /** The index of contract `VENUE:CODE` [contract], or null when the schedule does not list it. */
    fun indexOf(contract: String): Int? = schedule.contracts.indices.firstOrNull { contractSymbol(it) == contract }

    /** Whether contract [index] lies inside the measured history, so [spaceFor] can map it. */
    fun covers(index: Int): Boolean = index - firstIndex in 0 until adjustment.size

    /** The price mapping of contract [index]; fails outside the measured history. */
    fun spaceFor(index: Int): PriceSpace {
        val position = index - firstIndex
        require(position >= 0) {
            "$symbol needs ${contractSymbol(index)}, before the roll history starts; build more with ${buildHint()}"
        }
        require(position < adjustment.size) {
            "$symbol needs ${contractSymbol(index)}, after the last measured roll; build more with ${buildHint()}"
        }
        return PriceSpace(policy.adjust, adjustment.shiftFor(position), root.tickSize)
    }

    /** The measured roll out of contract [index]: its instant and reference prices; fails when unmeasured. */
    fun rollOutOf(index: Int): MeasuredRoll =
        requireNotNull(measuredRolls[index]) {
            "$symbol has no measured roll out of ${contractSymbol(index)}; build more with ${buildHint()}"
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

    /** Where the policy's anchor sits among the [covered] contracts from [firstIndex]; 0 without one. */
    private fun anchorPosition(covered: Int): Int {
        val anchor = policy.anchor ?: return 0
        val index = schedule.contracts.indexOfFirst { it.symbol == anchor }
        require(index >= 0) { "futures root ${root.root}: roll.anchor $anchor is not a contract of its chain" }
        val position = index - firstIndex
        require(position in 0 until covered) {
            "futures root ${root.root}: roll.anchor $anchor is outside the measured history of $symbol " +
                "(${contractSymbol(firstIndex)} to ${contractSymbol(firstIndex + covered - 1)}); " +
                "anchor inside it or build more with ${buildHint()}"
        }
        return position
    }

    /**
     * A tradeable stream must leave each contract before that contract's expiry guard window opens,
     * or the exchange would refuse the roll's closing leg.
     */
    private fun requireRollsBeforeGuard(served: List<RollTransition>) {
        val guardMs = root.expiryGuardHours * 3_600_000L
        val late =
            served.firstOrNull { t ->
                val left = schedule.contracts.getOrNull(t.fromIndex + selector.offset)
                left != null && left.expiryMs - t.atMs < guardMs
            } ?: return
        throw IllegalArgumentException(
            "futures root ${root.root}: $symbol leaves ${contractSymbol(late.fromIndex + selector.offset)} at " +
                "${Instant.ofEpochMilli(late.atMs)}, inside its ${root.expiryGuardHours}h expiry guard " +
                "(expiryGuardHours); roll earlier or lower the guard",
        )
    }

    private fun buildHint(): String = "qkt fetch ${root.root} --rolls"
}
