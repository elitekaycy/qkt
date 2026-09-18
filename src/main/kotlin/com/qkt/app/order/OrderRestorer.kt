package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.persistence.PersistedTrailingStop
import com.qkt.persistence.StatePersistor
import org.slf4j.LoggerFactory

/**
 * Rebuilds order tracking and sibling linkage from the persistor at session startup, strategy by
 * strategy: live OCO legs, bracket stop/target pairs, then pending orders (composites re-created
 * whole, engine-held orders resumed as monitors), then legacy trailing-stop snapshots. Orders the
 * venue must confirm are reconciled by [VenueRecovery] at the end. A persistence read failure
 * aborts startup rather than silently discarding live order state.
 */
internal class OrderRestorer(
    private val persistor: StatePersistor,
    private val book: OrderBook,
    private val siblings: SiblingLinks,
    private val ocoGuard: OcoExecutionGuard,
    private val exposure: PendingExposureBook,
    private val scaleOuts: ScaleOutBook,
    private val scaleOutRecovery: ScaleOutRecovery,
    private val composites: CompositeRestore,
    private val engineHeld: EngineHeldRestore,
    private val venueRecovery: VenueRecovery,
    private val snapshots: OrderStateSnapshots,
    private val broker: Broker,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OrderRestorer::class.java)

    /** Restores every order of [strategyIds], then reconciles the venue-held ones. */
    fun restore(strategyIds: List<String>) {
        val recovered = mutableListOf<ManagedOrder>()
        for (sid in strategyIds) {
            snapshots.remember(sid)
            val dynamicStops =
                persistor
                    .loadTrailingStops(sid)
                    .associateBy { it.clientOrderId }
                    .toMutableMap()
            restoreOcoLegs(sid, dynamicStops, recovered)
            restoreBracketPairs(sid)
            restorePendingOrders(sid, dynamicStops, recovered)
            // Older journals may contain a dynamic stop without the duplicate pending-order
            // snapshot. Keep accepting that shape after the current OCO and pending snapshots have
            // consumed their matching state.
            for (stop in dynamicStops.values) {
                engineHeld.restore(
                    clientOrderId = stop.clientOrderId,
                    brokerOrderId = stop.brokerOrderId,
                    request = stop.request,
                    dynamicState = stop,
                    groupId = null,
                )
            }
        }
        venueRecovery.reconcile(strategyIds, recovered)
    }

    private fun restoreOcoLegs(
        sid: String,
        dynamicStops: MutableMap<String, PersistedTrailingStop>,
        recovered: MutableList<ManagedOrder>,
    ) {
        for (leg in persistor.loadOcoLegs(sid)) {
            if (book.contains(leg.clientOrderId)) continue
            val groupId =
                (leg.siblingIds + leg.clientOrderId)
                    .sorted()
                    .joinToString(prefix = "restored-oco:", separator = "|")
            ocoGuard.markEmulated(leg.clientOrderId, groupId)
            if (engineHeld.isEngineHeld(leg.request)) {
                siblings[leg.clientOrderId] = leg.siblingIds
                val persisted = dynamicStops.remove(leg.clientOrderId)
                if (persisted == null && hasPersistentDynamicState(leg.request)) {
                    log.warn(
                        "[restore] dynamic state missing for {}; restarting from its available anchor",
                        leg.clientOrderId,
                    )
                }
                engineHeld.restore(
                    clientOrderId = leg.clientOrderId,
                    brokerOrderId = leg.brokerOrderId,
                    request = leg.request,
                    dynamicState = persisted,
                    groupId = groupId,
                )
                continue
            }
            val now = clock.now()
            val managed =
                ManagedOrder(
                    id = leg.clientOrderId,
                    request = leg.request,
                    state = OrderState.WORKING,
                    brokerOrderId = leg.brokerOrderId,
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            book.put(managed)
            siblings[leg.clientOrderId] = leg.siblingIds
            exposure.register(leg.request, groupId)
            recovered += managed
        }
    }

    private fun restoreBracketPairs(sid: String) {
        for (pair in persistor.loadBracketPairs(sid)) {
            val exitIds = listOfNotNull(pair.stopLossClientOrderId, pair.takeProfitClientOrderId)
            for (exitId in exitIds) {
                siblings[exitId] = exitIds.filter { it != exitId }
            }
        }
    }

    private fun restorePendingOrders(
        sid: String,
        dynamicStops: MutableMap<String, PersistedTrailingStop>,
        recovered: MutableList<ManagedOrder>,
    ) {
        val pendingOrders = routablePendingOrders(sid)
        for ((id, request) in pendingOrders) {
            if (request is OrderRequest.ScaleOut && id == request.id) {
                scaleOutRecovery.restoreActive(request, pendingOrders.keys)
            }
        }
        for ((id, request) in pendingOrders) {
            if (book.contains(id)) continue
            if (request is OrderRequest.OTO) {
                require(id == request.parent.id) {
                    "persisted OTO ${request.id} keyed by $id instead of parent ${request.parent.id}"
                }
                composites.restoreOto(request, recovered)
                continue
            }
            if (request is OrderRequest.ScaleOut) {
                if (id == request.id) continue
                require(id == request.basis.id) {
                    "persisted ScaleOut ${request.id} keyed by $id instead of basis ${request.basis.id}"
                }
                scaleOutRecovery.restorePending(request, recovered)
                continue
            }
            if (request is OrderRequest.Bracket) {
                composites.restoreBracket(request, recovered)
                continue
            }
            if (engineHeld.isEngineHeld(request)) {
                engineHeld.restore(
                    clientOrderId = id,
                    brokerOrderId = null,
                    request = request,
                    dynamicState = dynamicStops.remove(id),
                    groupId = null,
                )
                continue
            }
            val now = clock.now()
            val engineHeldScaleOutExit =
                request is OrderRequest.IfTouched && request.closesTicket != null
            val managed =
                ManagedOrder(
                    id = id,
                    request = request,
                    state = if (engineHeldScaleOutExit) OrderState.PENDING else OrderState.WORKING,
                    parentClientOrderId = scaleOuts.wrapperOf(id),
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            book.put(managed)
            exposure.register(request)
            if (!engineHeldScaleOutExit) recovered += managed
        }
    }

    // A pending order whose symbol no venue routes any more (a broker profile removed from the
    // config since it was persisted) can never be quoted, recovered, or filled; keeping it would
    // fail every deploy of this strategy from now on.
    private fun routablePendingOrders(sid: String): Map<String, OrderRequest> {
        val persistedPending = persistor.loadPendingOrders(sid)
        val unroutable = persistedPending.filterValues { !broker.supports(it.symbol) }
        for ((id, request) in unroutable) {
            log.warn(
                "[restore] dropping pending order {} for {}: no configured venue routes that symbol",
                id,
                request.symbol,
            )
        }
        val pendingOrders = persistedPending - unroutable.keys
        if (unroutable.isNotEmpty()) persistor.savePendingOrders(sid, pendingOrders)
        return pendingOrders
    }
}
