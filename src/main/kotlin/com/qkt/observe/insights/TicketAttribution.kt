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
    private val missingCycles = ConcurrentHashMap<String, Int>()

    /** Remember that [ticket] belongs to [strategyId]; blanks and nulls are ignored. */
    fun record(
        ticket: String?,
        strategyId: String?,
    ) {
        if (!ticket.isNullOrBlank() && !strategyId.isNullOrBlank()) {
            owners[ticket] = strategyId
            missingCycles.remove(ticket)
        }
    }

    /** The strategy that owns [ticket], or null when this daemon never attributed it. */
    fun ownerOf(ticket: String): String? = owners[ticket]

    /**
     * Keep live tickets fresh and retire vanished tickets after a bounded grace window.
     *
     * MT5 can report a just-closed position as gone before its OUT deal appears in
     * history. Close deals often have empty or venue-generated comments, so deleting
     * owner attribution on the first missing-position cycle loses the only reliable
     * strategy id. The grace is cycle-based to avoid wall-clock coupling in tests and
     * keeps the map bounded for long-running daemons.
     */
    fun retainAll(liveTickets: Set<String>) {
        for (ticket in owners.keys) {
            if (ticket in liveTickets) {
                missingCycles.remove(ticket)
                continue
            }
            val missing = (missingCycles[ticket] ?: 0) + 1
            if (missing > RETAIN_MISSING_CYCLES) {
                owners.remove(ticket)
                missingCycles.remove(ticket)
            } else {
                missingCycles[ticket] = missing
            }
        }
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

    private companion object {
        const val RETAIN_MISSING_CYCLES = 300
    }
}
