package com.qkt.app.order

import com.qkt.execution.OrderRequest

/**
 * State of every [OrderRequest.ScaleOut] wrapper, shared by the tracker that drives its
 * lifecycle ([ScaleOutTracker], [ScaleOutExits]) and the restart path ([ScaleOutRecovery]).
 */
internal class ScaleOutBook {
    /** Pre-fill wrappers keyed by basis id so their activation survives restart. */
    val pendingByBasis: MutableMap<String, OrderRequest.ScaleOut> = mutableMapOf()

    /** Owned position ticket reported by the latest partial execution of a basis. */
    val partialPositionTickets: MutableMap<String, String> = mutableMapOf()

    /** Filled wrappers retained while at least one ticketed exit remains live. */
    val activeById: MutableMap<String, OrderRequest.ScaleOut> = mutableMapOf()
    val wrapperByExitId: MutableMap<String, String> = mutableMapOf()
    val remainingExitIds: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** The wrapper that owns the exit [exitId], if any. */
    fun wrapperOf(exitId: String): String? = wrapperByExitId[exitId]

    /**
     * Forgets the pending wrapper of basis [basisId], so its end arms no exits: a closing cancel
     * (`CLOSE`, a flatten) closes the part that filled itself (#1336).
     */
    fun dropPending(basisId: String) {
        pendingByBasis.remove(basisId)
        partialPositionTickets.remove(basisId)
    }

    /** Drops per-order state for [id]; its order was reclaimed. */
    fun forget(id: String) {
        pendingByBasis.remove(id)
        partialPositionTickets.remove(id)
        wrapperByExitId.remove(id)
    }
}
