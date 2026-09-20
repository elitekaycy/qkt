package com.qkt.app.order

import com.qkt.app.StackTracker
import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal

/**
 * Cancelling orders, and what a risk halt may cancel. A composite cancels its children first;
 * an engine-held or not-yet-sent order ends locally, a venue order asks the broker. A halt
 * cancels risk-increasing entries only: protective stops, risk-reducing exits and the wrappers
 * that carry them stay live, or the halt would strip the stops off open positions.
 */
internal class OrderCancellation(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val stacks: StackTracker,
    private val scaleOuts: ScaleOutBook,
    private val scaleOutExits: ScaleOutExits,
    private val haltCancels: HaltCancellations,
    private val closeTickets: EngineHeldCloseTickets,
    private val broker: Broker,
    private val clock: Clock,
    private val ops: OrderOps,
    private val isRiskReducingForHalt: (OrderRequest) -> Boolean,
) {
    /** Cancels [clientOrderId]; a composite cascades to its children, engine-held orders end locally. */
    fun cancel(clientOrderId: String) {
        val managed = book[clientOrderId] ?: return
        if (managed.state.isTerminal) return
        if (managed.request is OrderRequest.Stack) {
            stacks.get(clientOrderId)?.let { state ->
                for (pid in state.pendingLayerIds.toList()) cancel(pid)
            }
            stacks.terminate(clientOrderId)
            ops.update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            exposure.remove(clientOrderId)
            return
        }
        if (managed.childClientOrderIds.isNotEmpty()) {
            val scaleOutCancellation = managed.request is OrderRequest.ScaleOut
            if (scaleOutCancellation) scaleOuts.cancellingWrappers.add(clientOrderId)
            try {
                for (childId in managed.childClientOrderIds) cancel(childId)
                ops.update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
                exposure.remove(clientOrderId)
            } finally {
                if (scaleOutCancellation) scaleOuts.cancellingWrappers.remove(clientOrderId)
            }
            return
        }
        when (managed.state) {
            OrderState.CREATED, OrderState.PENDING -> {
                ops.update(clientOrderId) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
                exposure.remove(clientOrderId)
                scaleOutExits.completeExit(clientOrderId, OrderState.CANCELLED)
            }
            else -> broker.cancel(clientOrderId)
        }
    }

    /** Cancels every pending stack and resting or engine-held order on [symbol]. */
    fun cancelPendingForSymbol(symbol: String) {
        // Cancel pending stacks targeting this symbol.
        val stackIds =
            stacks
                .all()
                .filter { state ->
                    val managed = book[state.id] ?: return@filter false
                    (managed.request as? OrderRequest.Stack)?.symbol == symbol
                }.map { it.id }
        for (id in stackIds) cancel(id)
        // Cancel any remaining (non-stack) engine-held or venue-resting orders for the symbol
        // that aren't already children of a stack we just cancelled.
        val pending =
            book.orders.values
                .filter {
                    (it.state == OrderState.PENDING || it.state == OrderState.WORKING) &&
                        it.request.symbol == symbol
                }.map { it.id }
        for (id in pending) cancel(id)
    }

    /**
     * Cancel active entry intent after a risk halt, optionally limited to [strategyId].
     *
     * Protective monitors, risk-reducing exits, and composite containers whose entry has filled
     * remain active. Their risk-increasing pending children are still cancelled individually.
     */
    fun cancelEntriesForHalt(strategyId: String? = null) {
        val entryIds =
            book.orders.values
                .filter { managed ->
                    (managed.state == OrderState.PENDING || managed.state == OrderState.WORKING) &&
                        (strategyId == null || managed.request.strategyId == strategyId) &&
                        !mustSurviveHalt(managed)
                }.map { it.id }
        for (id in entryIds) {
            haltCancels.begin(id)
            cancel(id)
        }
    }

    /** True when a halt must leave [managed] live: it protects or reduces an open position. */
    fun mustSurviveHalt(
        managed: ManagedOrder,
        depth: Int = 0,
    ): Boolean {
        if (managed.id in closeTickets) return true
        if (isPersistentManagedStop(managed.request)) return true
        if (isRiskReducingForHalt(managed.request)) return true
        if (managed.childClientOrderIds.any { book[it]?.state == OrderState.FILLED }) return true
        // A wrapper (OTO / OCO / ScaleOut) is cancelled as a whole and the cascade takes every child
        // with it. If any LIVE child is itself a protective exit that must survive, the wrapper
        // must survive too — otherwise a daily halt strips the stops off open positions (observed
        // 2023-12-11 in a BTC replay: the filled bracket's OTO wrapper was not risk-reducing, its
        // entry child had already left the live map, and the cascade cancelled the working stops).
        if (depth >= 4) return false
        return managed.childClientOrderIds.any { childId ->
            val child = book[childId] ?: return@any false
            !child.state.isTerminal && mustSurviveHalt(child, depth + 1)
        }
    }

    /**
     * Count active, risk-increasing entry orders for [strategyId] on [symbol].
     *
     * The count uses the existing per-symbol live index and exposure registry, so its hot-path
     * cost is O(active orders for this symbol), not O(all orders). Dormant composite children and
     * protective or otherwise risk-reducing exits are excluded. Submitted and partially-filled
     * entries count as active to cover the acknowledgement and residual-fill lifecycle windows.
     */
    fun activeEntryOrderCount(
        strategyId: String,
        symbol: String,
    ): Int {
        val ids = book.liveIdsFor(symbol) ?: return 0
        var count = 0
        for (id in ids) {
            if (id !in exposure) continue
            val managed = book[id] ?: error("live order index desync: $id")
            if (managed.request.strategyId != strategyId) continue
            val activeEntry =
                when (managed.state) {
                    OrderState.PENDING,
                    OrderState.SUBMITTED,
                    OrderState.WORKING,
                    OrderState.PARTIALLY_FILLED,
                    -> true
                    else -> false
                }
            if (activeEntry && !mustSurviveHalt(managed)) count++
        }
        return count
    }
}
