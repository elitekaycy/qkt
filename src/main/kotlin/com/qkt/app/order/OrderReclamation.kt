package com.qkt.app.order

import com.qkt.execution.OrderRequest
import com.qkt.execution.isTerminal

/**
 * Which terminal orders may be forgotten, and forgetting them. An order stays while an active
 * structure still points at it; once nothing does, its record and every order-keyed satellite
 * entry are dropped. Only dead orders are touched, so this never changes a trading decision.
 */
internal class OrderReclamation(
    private val store: OrderStore,
    private val ocoGuard: OcoExecutionGuard,
    private val siblingCancels: SiblingCancellation,
    private val timeExits: TimeExits,
) {
    /**
     * True while some active structure still points at [id], so reclaiming it would break a later
     * lookup: a pending timed-exit whose target is this order, or an active stack that owns it as
     * the parent, layer-one, or a pending/filled/closed layer. A filled client-emulated OCO leg is
     * also kept until its sibling resolves, so a late second fill can still be compensated. A filled
     * venue-attached bracket entry is kept while its wrapper is live: the venue reports the later
     * SL/TP close under the entry id, and that close is what completes the wrapper.
     */
    fun isReferenced(id: String): Boolean {
        if (ocoGuard.isHoldingForSibling(id)) return true
        if (timeExits.targets(id)) return true
        if (isLiveAttachedEntry(id)) return true
        for (st in store.stacks.all()) {
            if (id == st.id || id == st.layerOneOrderId) return true
            if (id in st.pendingLayerIds || id in st.filledLayerIds || id in st.closedLayerIds) return true
        }
        return false
    }

    private fun isLiveAttachedEntry(id: String): Boolean {
        val entry = store.book[id] ?: return false
        if (entry.request !is OrderRequest.Bracket) return false
        val wrapper = entry.parentClientOrderId?.let { store.book[it] } ?: return false
        return wrapper.request is OrderRequest.Bracket && !wrapper.state.isTerminal
    }

    /** Drop a dead, unreferenced order and all its order-keyed satellite state. */
    fun reclaim(id: String) {
        store.book.evict(id)
        store.stops.forget(id)
        store.siblings.remove(id)
        store.scaleOuts.forget(id)
        siblingCancels.forget(id)
        ocoGuard.forget(id)
        store.children.take(id)
        store.closeTickets.remove(id)
        store.exposure.remove(id)
    }
}
