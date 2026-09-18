package com.qkt.app.order

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderState
import org.slf4j.LoggerFactory

/**
 * Reconciles restored venue-held orders with what the venue actually holds. Orders the venue
 * cannot account for are retired through the ordinary cancel path; attached-bracket entries
 * whose ticket the position ledger already booked are marked filled without republishing.
 */
internal class VenueRecovery(
    private val book: OrderBook,
    private val brackets: BracketBook,
    private val exposure: PendingExposureBook,
    private val broker: Broker,
    private val bookedVenueTickets: (strategyId: String) -> Set<String>,
    private val clock: Clock,
    private val ops: OrderOps,
    private val retire: (BrokerEvent.OrderCancelled) -> Unit,
) {
    private val log = LoggerFactory.getLogger(VenueRecovery::class.java)

    /** Hands [recovered] to the broker for reconciliation, then retires what it could not match. */
    fun reconcile(
        strategyIds: List<String>,
        recovered: List<ManagedOrder>,
    ) {
        if (recovered.isEmpty()) return
        val booked = strategyIds.flatMapTo(LinkedHashSet()) { bookedVenueTickets(it) }
        val accounted = broker.recoverPendingOrders(recovered, booked)
        // A restored working order the venue cannot account for — no pending ticket, no
        // position, nothing to track — is a phantom: pre-#1048 attached-bracket wrappers
        // whose position closed long ago. Left alone it holds exposure for the whole
        // session and never reaches a terminal state. Retire it through the ordinary cancel
        // path so exposure, children and persistence unwind exactly as a venue cancel would.
        val vanished = recovered.filter { it.id !in accounted }
        for (order in vanished) {
            log.warn(
                "[restore] {} {} {} has no venue counterpart after recovery; retiring stale order",
                order.request.strategyId,
                order.id,
                order.request::class.simpleName,
            )
            retire(
                BrokerEvent.OrderCancelled(
                    clientOrderId = order.id,
                    brokerOrderId = null,
                    reason = "not at venue after recovery",
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
        if (vanished.isNotEmpty()) {
            log.warn("[restore] retired {} stale order(s) with no venue counterpart", vanished.size)
        }
        // A restored attached entry the venue matched to a position the ledger already booked
        // is a filled entry: it must not count as an open entry order (it would block every
        // re-entry once that position closes) nor hold entry exposure on top of the position.
        // The ticket arrives as OrderAccepted — synchronously here on a direct bus, or later
        // on the engine thread in the daemon — so both restore and onAccepted apply the mark.
        for (id in brackets.restoredAttachedEntries.toList()) {
            val ticket = book[id]?.brokerOrderId ?: continue
            markAttachedEntryFilled(id, ticket)
        }
    }

    /** Marks the restored attached entry [id] filled once [ticket] proves the ledger booked it. */
    fun markAttachedEntryFilled(
        id: String,
        ticket: String,
    ) {
        val managed = book[id] ?: return
        if (managed.state != OrderState.WORKING) return
        if (ticket !in bookedVenueTickets(managed.request.strategyId)) return
        ops.update(id) {
            it.copy(
                state = OrderState.FILLED,
                cumulativeFilledQuantity = it.request.quantity,
                lastUpdatedAt = clock.now(),
            )
        }
        exposure.remove(id)
        brackets.restoredAttachedEntries.remove(id)
        log.info(
            "[restore] attached entry {} is backed by booked venue ticket {} — marked filled without republishing",
            id,
            ticket,
        )
    }
}
