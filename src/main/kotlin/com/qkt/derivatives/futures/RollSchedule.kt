package com.qkt.derivatives.futures

import com.qkt.instrument.ContinuousSelector
import com.qkt.instrument.ListedContract
import com.qkt.instrument.RollPolicy

/** The instant a continuous series moves from contract [fromIndex] to [toIndex] of a [RollSchedule]. */
data class RollTransition(
    val atMs: Long,
    val fromIndex: Int,
    val toIndex: Int,
)

/**
 * Which contract of a root is front at any instant, under one [RollPolicy]. Pure and deterministic:
 * the same catalog and policy give the same schedule in backtest and live. Lookups are a binary
 * search over the transitions. The catalog carries no listing times, so on a venue that lists
 * only two quarterlies at once `NEXT` can name a contract that is not trading yet; its data simply
 * starts later. Delivery months the catalog shows were never traded are left out of the chain
 * ([ActiveDeliveryMonths]).
 */
class RollSchedule(
    contracts: List<ListedContract>,
    policy: RollPolicy,
) {
    /** The chain in expiry order: the catalog's contracts that traded ([ActiveDeliveryMonths]). */
    val contracts: List<ListedContract> = ActiveDeliveryMonths.of(contracts.sortedBy { it.expiryMs })

    /** One transition per consecutive pair, strictly ascending. */
    val transitions: List<RollTransition>

    private val rollTimes: LongArray

    init {
        require(this.contracts.isNotEmpty()) { "roll schedule has no contracts" }
        transitions =
            (0 until this.contracts.size - 1).map { i ->
                RollTransition(
                    policy.rollAtMs(this.contracts[i].expiryMs),
                    i,
                    i + 1,
                )
            }
        for (k in 1 until transitions.size) {
            require(transitions[k].atMs > transitions[k - 1].atMs) {
                "contracts ${this.contracts[k - 1].symbol} and ${this.contracts[k].symbol} roll at the same or reversed instants"
            }
        }
        rollTimes = LongArray(transitions.size) { transitions[it].atMs }
    }

    /** Index of the front contract at [tMs], or null once the last contract has expired. */
    fun frontIndexAt(tMs: Long): Int? {
        var lo = 0
        var hi = rollTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rollTimes[mid] <= tMs) lo = mid + 1 else hi = mid
        }
        return if (lo == contracts.lastIndex && tMs >= contracts.last().expiryMs) null else lo
    }

    /** Index followed by [selector] at [tMs], or null when that contract does not exist. */
    fun indexAt(
        tMs: Long,
        selector: ContinuousSelector,
    ): Int? {
        val front = frontIndexAt(tMs) ?: return null
        val index = front + selector.offset
        return index.takeIf { it < contracts.size }
    }

    /** Contract followed by [selector] at [tMs], or null when there is none. */
    fun contractAt(
        tMs: Long,
        selector: ContinuousSelector,
    ): ListedContract? = indexAt(tMs, selector)?.let(contracts::get)
}
