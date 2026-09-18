package com.qkt.app.order

import com.qkt.execution.isTerminal

/**
 * Cancels an executing order's linked siblings exactly once, starting with its first positive
 * execution slice — so a partial fill of one OCO leg already stops the other from filling too.
 */
internal class SiblingCancellation(
    private val book: OrderBook,
    private val siblings: SiblingLinks,
    private val sequencer: OcoSequencer,
    private val ops: OrderOps,
) {
    /** Orders whose first execution slice already started cancelling their siblings. */
    private val started: MutableSet<String> = mutableSetOf()

    /** Cancel [clientOrderId]'s live siblings, unless that already started. */
    fun onExecution(clientOrderId: String) {
        val siblingIds = siblings[clientOrderId]
        if (siblingIds.isEmpty() || !started.add(clientOrderId)) return
        var deferredSiblingCancel = false
        siblingIds.forEach { sibId ->
            val sib = book[sibId] ?: return@forEach
            if (sib.state.isTerminal) return@forEach
            // If the sibling is an OCO leg2 that the venue hasn't acknowledged yet, its ticket
            // is unknown — a cancel now would no-op at the venue. Defer it to leg2's acceptance.
            if (sequencer.deferCancelUntilAccepted(sibId)) {
                deferredSiblingCancel = true
            } else {
                ops.cancel(sibId)
            }
        }
        // The filled leg resolved its OCO; drop the sequence unless a cancel is still deferred
        // (that path clears it once leg2 is acknowledged and cancelled).
        if (!deferredSiblingCancel) sequencer.clearFor(clientOrderId)
    }

    /** Forgets that [id] started its sibling cancel; it reached a terminal outcome. */
    fun forget(id: String) {
        started.remove(id)
    }
}
