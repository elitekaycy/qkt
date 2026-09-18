package com.qkt.app.order

import com.qkt.execution.OrderRequest

/**
 * Children of a composite order that wait, unarmed, for their parent to fill: an OTO's
 * follow-on orders, a bracket's exit OCO, an attached bracket's engine-managed stop. They are
 * tracked (so they can be cancelled with the parent) but not sent until the parent's fill
 * arrives; a parent that dies takes its unarmed children with it.
 */
internal class PendingChildBook {
    private val childrenByParent: MutableMap<String, List<OrderRequest>> = mutableMapOf()

    /**
     * OTO wrappers whose parent is live and whose children are still unarmed, keyed by parent id.
     * Persistence snapshots scan only this bounded active set on order-state mutations; the tick
     * path never reads or scans it.
     */
    private val otosByParent: MutableMap<String, OrderRequest.OTO> = mutableMapOf()

    /** OTO wrappers still waiting on their parent, for persistence. */
    val pendingOtos: Map<String, OrderRequest.OTO> get() = otosByParent

    /** Holds [children] until [parentId] fills; [oto] is the wrapper to persist, if any. */
    fun hold(
        parentId: String,
        children: List<OrderRequest>,
        oto: OrderRequest.OTO? = null,
    ) {
        childrenByParent[parentId] = children
        if (oto != null) otosByParent[parentId] = oto
    }

    /** Removes and returns the children waiting on [parentId]; null when there are none. */
    fun take(parentId: String): List<OrderRequest>? {
        val children = childrenByParent.remove(parentId)
        otosByParent.remove(parentId)
        return children
    }

    /** Ids of every child still waiting on its parent; they are persisted inside their wrapper. */
    fun unarmedChildIds(): Set<String> =
        childrenByParent.values
            .asSequence()
            .flatten()
            .map { it.id }
            .toSet()
}
