package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollRecord
import com.qkt.marketdata.Candle

/** What measuring a live roll gave. */
sealed interface LiveRoll {
    /** The roll's [record], now in the root's history (appended, or found there already). */
    data class Measured(
        val record: RollRecord,
    ) : LiveRoll

    /** A contract has no closed 1-minute bar at or before the roll yet; nothing was written. */
    data class Unpriced(
        val reason: String,
    ) : LiveRoll

    /** The history cannot take this roll (none to extend, another policy, or a roll before it missing); nothing was written. */
    data class NotNext(
        val reason: String,
    ) : LiveRoll
}

/**
 * Measures a roll that happens while live (design: live continuous futures §2.1) by [RollPricing], the
 * rule `qkt fetch --rolls` applies to stored bars, from the venue's own 1-minute bars read live
 * ([minuteBars]: the closed bars of a contract starting in `[fromMs, toMs)`), and appends it to the
 * root's history in [store]. A history stays one contiguous run per selector, as [ContinuousChain]
 * requires: only the roll right after the last measured one is appended; a live session never starts
 * a history (`qkt fetch --rolls` does).
 */
class LiveRollMeasurer(
    private val minuteBars: (contract: String, fromMs: Long, toMs: Long) -> List<Candle>,
    private val store: RollHistoryStore,
) {
    /** Measures [transition] of [root] (contracts from [catalog]) for [selector] and appends it. */
    fun measure(
        root: FuturesRoot,
        catalog: ContractCatalog,
        selector: ContinuousSelector,
        transition: RollTransition,
    ): LiveRoll {
        val policy = requireNotNull(root.roll) { "futures root ${root.root} has no roll policy" }
        val schedule = RollSchedule(catalog.contracts, policy)
        val (from, to) =
            contracts(schedule, transition, selector)
                ?: return LiveRoll.NotNext("the chain has no contract for $selector here")
        val history =
            store.read(root.root)
                ?: return LiveRoll.NotNext(
                    "no roll history for ${root.root}; build it with qkt fetch ${root.root} --rolls",
                )
        if (history.policy !=
            policy.key
        ) {
            return LiveRoll.NotNext("the history was built for ${history.policy}, the root rolls ${policy.key}")
        }
        history.find(transition.atMs, from, to)?.let { return LiveRoll.Measured(it) }
        val previous = schedule.transitions.getOrNull(schedule.transitions.indexOf(transition) - 1)
        val previousPair = previous?.let { contracts(schedule, it, selector) }
        if (previousPair == null || history.find(previous.atMs, previousPair.first, previousPair.second) == null) {
            return LiveRoll.NotNext(
                "the history lacks the roll before ${transition.atMs}; rebuild it with qkt fetch ${root.root} --rolls",
            )
        }
        val since = RollPricing.lookbackStartMs(transition.atMs)
        val fromPrice = RollPricing.closeAtOrBefore(minuteBars(from, since, transition.atMs), transition.atMs)
        val toPrice = RollPricing.closeAtOrBefore(minuteBars(to, since, transition.atMs), transition.atMs)
        if (fromPrice == null || toPrice == null) {
            return LiveRoll.Unpriced(
                "no closed 1-minute bar at or before ${transition.atMs} for ${if (fromPrice == null) from else to}",
            )
        }
        val record = RollRecord(transition.atMs, from, to, fromPrice.toPlainString(), toPrice.toPlainString())
        store.write(history.copy(rolls = (history.rolls + record).sortedWith(compareBy({ it.atMs }, { it.from }))))
        return LiveRoll.Measured(record)
    }

    private fun contracts(
        schedule: RollSchedule,
        transition: RollTransition,
        selector: ContinuousSelector,
    ): Pair<String, String>? {
        val from = schedule.contracts.getOrNull(transition.fromIndex + selector.offset) ?: return null
        val to = schedule.contracts.getOrNull(transition.toIndex + selector.offset) ?: return null
        return from.symbol to to.symbol
    }
}
