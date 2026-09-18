package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal

/**
 * How [OrderRequest.ScaleOut] wrappers survive a restart: which wrappers the snapshot stores in
 * place of their bare basis or exits, and how each is re-created from the snapshot.
 */
internal class ScaleOutRecovery(
    private val scaleOuts: ScaleOutBook,
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val clock: Clock,
) {
    /** Wrappers to persist in place of their bare basis / exits, keyed by the id they restore under. */
    fun recoverySnapshot(strategyId: String): Map<String, OrderRequest.ScaleOut> {
        val result = LinkedHashMap<String, OrderRequest.ScaleOut>()
        scaleOuts.pendingByBasis.forEach { (basisId, scaleOut) ->
            if (scaleOut.strategyId == strategyId && book[basisId]?.state?.isTerminal == false) {
                result[basisId] = scaleOut
            }
        }
        scaleOuts.activeById.forEach { (scaleOutId, scaleOut) ->
            if (scaleOut.strategyId == strategyId && scaleOuts.remainingExitIds[scaleOutId].orEmpty().isNotEmpty()) {
                result[scaleOutId] = scaleOut
            }
        }
        return result
    }

    /** Overlays every strategy's live wrappers onto [pendingByStrategy] for the routine snapshot. */
    fun overlay(pendingByStrategy: MutableMap<String, MutableMap<String, OrderRequest>>) {
        for ((basisId, scaleOut) in scaleOuts.pendingByBasis) {
            val strategyId = scaleOut.strategyId
            if (strategyId.isBlank() || book[basisId]?.state?.isTerminal != false) continue
            pendingByStrategy.getOrPut(strategyId) { mutableMapOf() }[basisId] = scaleOut
        }
        for ((scaleOutId, scaleOut) in scaleOuts.activeById) {
            val strategyId = scaleOut.strategyId
            if (strategyId.isBlank() || scaleOuts.remainingExitIds[scaleOutId].orEmpty().isEmpty()) continue
            pendingByStrategy.getOrPut(strategyId) { mutableMapOf() }[scaleOutId] = scaleOut
        }
    }

    /** Re-creates a pre-fill wrapper and its basis after a restart. */
    fun restorePending(
        request: OrderRequest.ScaleOut,
        recovered: MutableList<ManagedOrder>,
    ) {
        require(request.basis.id != request.id) { "ScaleOut ${request.id} basis must have a distinct id" }
        require(book[request.id] == null) {
            "persisted ScaleOut ${request.id} collides with already-restored order state"
        }
        val now = clock.now()
        val wrapper =
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(request.basis.id),
                createdAt = now,
                lastUpdatedAt = now,
            )
        val basis =
            ManagedOrder(
                id = request.basis.id,
                request = request.basis,
                state = OrderState.WORKING,
                parentClientOrderId = request.id,
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(wrapper)
        book.put(basis)
        scaleOuts.pendingByBasis[basis.id] = request
        exposure.register(exposureEntryRequest(request.basis))
        recovered += basis
    }

    /** Re-creates a filled wrapper whose exits [persistedIds] survived the restart. */
    fun restoreActive(
        request: OrderRequest.ScaleOut,
        persistedIds: Set<String>,
    ) {
        val exitIds =
            request.legs.indices
                .map { "${request.id}-leg-$it" }
                .filterTo(linkedSetOf()) { it in persistedIds }
        if (exitIds.isEmpty()) return
        require(book[request.id] == null) {
            "persisted active ScaleOut ${request.id} collides with already-restored order state"
        }
        val now = clock.now()
        val wrapper =
            ManagedOrder(
                id = request.id,
                request = request,
                state = OrderState.WORKING,
                childClientOrderIds = listOf(request.basis.id) + request.legs.indices.map { "${request.id}-leg-$it" },
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(wrapper)
        scaleOuts.activeById[request.id] = request
        scaleOuts.remainingExitIds[request.id] = exitIds
        for (exitId in exitIds) scaleOuts.wrapperByExitId[exitId] = request.id
    }
}
