package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.persistence.PersistedTrailingStop

/**
 * Engine-held orders after a restart: trailing and managed stops, and stop-limits the venue
 * cannot hold. They never went to the venue, so they resume locally as PENDING monitors and are
 * kept out of venue reconciliation.
 */
internal class EngineHeldRestore(
    private val book: OrderBook,
    private val stops: ManagedStopBook,
    private val exposure: PendingExposureBook,
    private val broker: Broker,
    private val clock: Clock,
) {
    /** True when [request] is held by the engine rather than resting at the venue. */
    fun isEngineHeld(request: OrderRequest): Boolean =
        when (request) {
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> true
            is OrderRequest.StopLimit ->
                OrderTypeCapability.STOP_LIMIT !in broker.capabilitiesFor(request.symbol)
            else -> isPersistentManagedStop(request)
        }

    /** Resumes an engine-held order as a PENDING monitor, with its persisted moving state if any. */
    fun restore(
        clientOrderId: String,
        brokerOrderId: String?,
        request: OrderRequest,
        dynamicState: PersistedTrailingStop?,
        groupId: String?,
    ) {
        if (book.contains(clientOrderId)) return
        require(request.id == clientOrderId) {
            "persisted engine-held order $clientOrderId contains request ${request.id}"
        }
        require(dynamicState == null || dynamicState.clientOrderId == clientOrderId) {
            "dynamic state ${dynamicState?.clientOrderId} does not belong to $clientOrderId"
        }
        val now = clock.now()
        val managed =
            ManagedOrder(
                id = clientOrderId,
                request = request,
                state = OrderState.PENDING,
                brokerOrderId = brokerOrderId,
                createdAt = now,
                lastUpdatedAt = now,
            )
        book.put(managed)
        stops.restore(clientOrderId, request, dynamicState)
        exposure.register(request, groupId)
    }
}
