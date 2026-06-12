package com.qkt.observe.insights

import java.util.concurrent.ConcurrentHashMap

/**
 * Broker-ticket → strategy-id mirror, written on the engine thread and read by the
 * insights state poller (which must not touch engine-thread-only trackers like
 * [com.qkt.positions.StrategyPositionTracker]).
 *
 * e.g. a fill for ticket 2832831596 on hedge_straddle → `record("2832831596",
 * "hedge_straddle")`; the poller later asks `ownerOf("2832831596")` to stamp the
 * strategy onto that ticket's `state.positions` entry.
 */
class TicketAttribution {
    private val owners = ConcurrentHashMap<String, String>()

    /** Remember that [ticket] belongs to [strategyId]; blanks and nulls are ignored. */
    fun record(
        ticket: String?,
        strategyId: String?,
    ) {
        if (!ticket.isNullOrBlank() && !strategyId.isNullOrBlank()) owners[ticket] = strategyId
    }

    /** The strategy that owns [ticket], or null when this daemon never attributed it. */
    fun ownerOf(ticket: String): String? = owners[ticket]

    /** Drop tickets the broker no longer reports — keeps the map bounded by open positions. */
    fun retainAll(liveTickets: Set<String>) {
        owners.keys.retainAll(liveTickets)
    }

    /**
     * Fallback attribution from an MT5 comment: the venue stores only a truncated prefix
     * of the `dsl-<strategy>` comment, so match in both directions ("hedge_stradd" is a
     * prefix of "hedge_straddle", and vice versa for short ids). Returns null when zero
     * or more than one deployed id matches — guessing between two candidates would
     * silently misattribute realized P&L.
     */
    fun fromComment(
        comment: String?,
        deployedIds: Collection<String>,
    ): String? {
        val c = comment?.removePrefix("dsl-") ?: return null
        if (c.isBlank()) return null
        val hits = deployedIds.filter { it.startsWith(c) || c.startsWith(it) }
        return hits.singleOrNull()
    }
}
