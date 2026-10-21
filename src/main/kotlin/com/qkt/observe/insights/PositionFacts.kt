package com.qkt.observe.insights

import com.qkt.broker.CloseReason
import com.qkt.observe.insights.InsightsEntryRisks.Companion.bounded
import java.math.BigDecimal

/**
 * What qkt resolved about each venue position, kept once per process: the strategy whose fill opened
 * it, its initial stop, target and risk, and why each of its closes closed. A venue account's positions
 * and deals are account-wide and every session's state poller reports all of them, so each poller states
 * the same facts whichever session booked the fill (live, 2026-10-21: the BTC session's poller reported
 * the ETH bracket position with no stop, the deal of a timed exit as a signal). Keyed by the account
 * label (a symbol's `LABEL:` prefix) and the position id; the oldest of [capacity] are forgotten.
 * Thread-safe: engine threads write, poller threads read.
 */
class PositionFacts(
    capacity: Int = 10_000,
) {
    /** An entry's levels and the risk it took: [stop] and [target] as attached for it, [risk] in account currency. */
    data class Entry(
        val orderId: String,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val stop: BigDecimal?,
        val target: BigDecimal?,
        val risk: BigDecimal?,
    )

    private val owners = bounded<String, String>(capacity)
    private val entries = bounded<String, Entry>(capacity)
    private val closes = bounded<String, List<Pair<Long, CloseReason>>>(capacity)

    /** Strategy [strategyId]'s fill opened (or added to) position [positionId] of [symbol]. */
    fun opened(
        symbol: String,
        positionId: String,
        strategyId: String,
    ) {
        if (strategyId.isNotBlank()) owners.putIfAbsent(key(symbol, positionId), strategyId)
    }

    /** The strategy whose fill opened position [positionId] of [symbol], or null when no session here booked it. */
    fun ownerOf(
        symbol: String,
        positionId: String,
    ): String? = owners[key(symbol, positionId)]

    /** Position [positionId]'s entry levels and risk, or null when its entry had no bracket here. */
    fun entry(
        symbol: String,
        positionId: String,
    ): Entry? = entries[key(symbol, positionId)]

    /** Records [entry] as position [positionId]'s. */
    fun entry(
        symbol: String,
        positionId: String,
        entry: Entry,
    ) {
        entries[key(symbol, positionId)] = entry
    }

    /** A fill at [tsMs] closed (part of) position [positionId] for [reason]. */
    fun closed(
        symbol: String,
        positionId: String,
        tsMs: Long,
        reason: CloseReason,
    ) {
        val k = key(symbol, positionId)
        synchronized(closes) { closes[k] = (closes[k].orEmpty() + (tsMs to reason)).takeLast(MAX_CLOSES) }
    }

    /** Why qkt closed position [positionId]: its one close, else the close nearest [tsMs]; null when none here. */
    fun closeReason(
        symbol: String,
        positionId: String,
        tsMs: Long,
    ): CloseReason? = closes[key(symbol, positionId)]?.minByOrNull { (at, _) -> kotlin.math.abs(at - tsMs) }?.second

    private fun key(
        symbol: String,
        positionId: String,
    ) = "${symbol.substringBefore(':').uppercase()}|$positionId"

    private companion object {
        const val MAX_CLOSES = 64
    }
}
