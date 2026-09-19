package com.qkt.app.order

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Bracket state that lives beside the order records until an entry resolves, keyed by entry id:
 * the original bracket (so a restart can re-arm it), brackets whose exits are anchored on the
 * actual fill price, attached entries recreated by a restart, and venue-side partial closes.
 */
internal class BracketBook {
    /** Original pre-fill brackets retained until their entry resolves, for durable re-arming. */
    val preFill: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()

    /** Engine-decomposed brackets whose exit OCO is built from the fill price. */
    val fillAnchoredFallback: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()

    /** Venue-attached brackets whose SL/TP are re-anchored on the fill by a position modify. */
    val fillAnchoredAttached: MutableMap<String, OrderRequest.Bracket> = mutableMapOf()

    /**
     * Venue-attached bracket entries recreated by a restore. Their wrapper record is not
     * persisted, and the venue does not republish an execution the ledger already booked, so
     * after recovery they are matched to their booked ticket and marked filled explicitly.
     */
    val restoredAttachedEntries: MutableSet<String> = mutableSetOf()

    /**
     * Quantity the venue has closed against each attached-bracket entry, summed from
     * position-close observations, so a partial close does not complete the wrapper early.
     */
    val venueClosedQuantityByEntry: MutableMap<String, BigDecimal> = mutableMapOf()

    /** Drops the pre-fill and fill-anchored state of an entry that will never fill. */
    fun forgetEntry(entryId: String) {
        preFill.remove(entryId)
        fillAnchoredFallback.remove(entryId)
        fillAnchoredAttached.remove(entryId)
    }
}
