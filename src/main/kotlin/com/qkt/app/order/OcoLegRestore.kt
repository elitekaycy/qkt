package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderState
import com.qkt.persistence.PersistedTrailingStop
import com.qkt.persistence.StatePersistor
import org.slf4j.Logger

/**
 * Restores the legs of OCOs qkt holds together. A live leg resumes WORKING (or as an engine-held
 * monitor) for the venue to reconcile. A leg that had already executed while its other leg was
 * still live comes back FILLED: e.g. l1 filled, the cancel of l2 was not yet confirmed when qkt
 * stopped. The restart then cancels l2 again ([cancelSiblingsOfExecuted]), and should l2 fill
 * anyway the OCO guard closes it as the second execution.
 */
internal class OcoLegRestore(
    private val persistor: StatePersistor,
    private val book: OrderBook,
    private val siblings: SiblingLinks,
    private val ocoGuard: OcoExecutionGuard,
    private val exposure: PendingExposureBook,
    private val engineHeld: EngineHeldRestore,
    private val cancelSiblingsOf: (String) -> Unit,
    private val clock: Clock,
    private val log: Logger,
) {
    private val executed = mutableListOf<String>()

    /** Restores [sid]'s persisted OCO legs; venue-held live legs are added to [recovered]. */
    fun restore(
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
            siblings[leg.clientOrderId] = leg.siblingIds
            if (leg.executed) {
                val now = clock.now()
                book.put(
                    ManagedOrder(
                        id = leg.clientOrderId,
                        request = leg.request,
                        state = OrderState.FILLED,
                        brokerOrderId = leg.brokerOrderId,
                        cumulativeFilledQuantity = leg.request.quantity,
                        createdAt = now,
                        lastUpdatedAt = now,
                    ),
                )
                executed += leg.clientOrderId
                continue
            }
            if (engineHeld.isEngineHeld(leg.request)) {
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
            exposure.register(leg.request, groupId)
            recovered += managed
        }
    }

    /** Cancels, again, the legs still live beside a restored executed leg; run after venue recovery. */
    fun cancelSiblingsOfExecuted() {
        for (id in executed) {
            log.warn("[restore] OCO leg {} executed before the restart; cancelling its other leg again", id)
            cancelSiblingsOf(id)
        }
        executed.clear()
    }
}
