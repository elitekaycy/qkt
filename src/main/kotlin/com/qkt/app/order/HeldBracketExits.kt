package com.qkt.app.order

import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState

/**
 * The exits a decomposed bracket holds, unarmed, for an entry the venue is still working when the
 * bracket is cancelled. What happens to them depends on why it is cancelled (#1328):
 * - a plain cancel (strategy `CANCEL`, a risk halt, [OrderCancellation.cancel]) cancels the entry's
 *   remainder and leaves the exits to the entry's end: the part that filled before the venue ended it
 *   gets them, sized to that part ([BracketFills.armPartFilled]), as when the venue cancels the rest
 *   itself (#1324); an entry that ends with nothing filled takes them with it;
 * - a closing cancel (`CLOSE`, `CLOSE_ALL`, `qkt stop --flatten`), which closes the position itself,
 *   drops them at once, so they can never be armed against a position the close has flattened.
 */
internal class HeldBracketExits(
    private val book: OrderBook,
    private val children: PendingChildBook,
    private val brackets: BracketBook,
) {
    /** Ids of [wrapper]'s children held for a bracket entry still at the venue: a plain cancel leaves them to it. */
    fun deferredBy(wrapper: ManagedOrder): Set<String> {
        val entryId = wrapper.childClientOrderIds.firstOrNull(::isWorkingBracketEntry) ?: return emptySet()
        return children.heldFor(entryId).orEmpty().mapTo(HashSet()) { it.id }
    }

    /**
     * Forgets the bracket of entry [entryId], when it is a decomposed bracket entry, and returns the
     * exits held for it, for a closing cancel to cancel; empty for any other order.
     */
    fun drop(entryId: String): List<OrderRequest> {
        val entry = book[entryId] ?: return emptyList()
        if (entryId !in brackets.preFill || entry.request is OrderRequest.Bracket) return emptyList()
        brackets.forgetEntry(entryId)
        return children.take(entryId).orEmpty()
    }

    // A venue-attached entry (its request is the bracket) has its SL/TP at the venue; an engine-held or
    // unsent one ends locally, with no venue answer to arm on, so its exits are cancelled with it.
    private fun isWorkingBracketEntry(id: String): Boolean {
        val entry = book[id] ?: return false
        if (id !in brackets.preFill || entry.request is OrderRequest.Bracket) return false
        return entry.state == OrderState.SUBMITTED ||
            entry.state == OrderState.WORKING ||
            entry.state == OrderState.PARTIALLY_FILLED
    }
}
