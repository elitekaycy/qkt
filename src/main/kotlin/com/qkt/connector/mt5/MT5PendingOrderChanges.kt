package com.qkt.connector.mt5

import com.qkt.broker.OrderModification
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * Cancels or re-prices an order resting at the venue, addressed by its engine order id.
 * E.g. cancelling `dsl-gold--1` looks up ticket 3258722177, asks the gateway to delete it, and only
 * when the venue confirms drops the ticket from the books and publishes `OrderCancelled`; a refused
 * cancel publishes `OrderCancelFailed` and keeps the ticket, because the order may still fill.
 */
internal class MT5PendingOrderChanges(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val partialEntries: MT5PartialEntries,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun cancel(orderId: String) {
        val ticket = books.pendingBook.ticketOf(orderId) ?: return
        val meta = books.pendingBook.meta(ticket) ?: return
        // Non-blocking: OCO sibling-cancels and the halt kill-switch sweep call this from the
        // engine thread, and serialized round-trips stall it exactly when it must stop fast.
        // Keep both ticket maps until the venue confirms success. A rejected or ambiguous cancel
        // can race a fill; retaining the metadata lets the position poller attribute that fill.
        client.cancelOrderAsync(ticket) { response ->
            if (!isOrderSuccessful(response.result.retcode)) {
                log.warn(
                    "MT5Broker {} cancel({}, ticket={}) remains unresolved: {}",
                    profile.name,
                    orderId,
                    ticket,
                    response.errorMessage ?: "retcode=${response.result.retcode}",
                )
                bus.publish(
                    BrokerEvent.OrderCancelFailed(
                        clientOrderId = orderId,
                        brokerOrderId = ticket.toString(),
                        reason = response.errorMessage ?: "retcode=${response.result.retcode}",
                        strategyId = meta.strategyId,
                        timestamp = clock.now(),
                    ),
                )
                return@cancelOrderAsync
            }
            val cancelled =
                synchronized(books.pendingTransitionLock) {
                    if (!books.pendingBook.stillIs(orderId, ticket, meta)) {
                        false
                    } else {
                        books.pendingBook.forgetLeg(orderId, ticket)
                        partialEntries.removePartialEntryByResidualTicket(ticket)
                        true
                    }
                }
            if (!cancelled) return@cancelOrderAsync
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = orderId,
                    brokerOrderId = ticket.toString(),
                    reason = "user cancel",
                    strategyId = meta.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
    }

    fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val ticket =
            books.pendingBook.ticketOf(orderId) ?: return SubmitAck(
                clientOrderId = orderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "modify: no working order with id=$orderId",
            )
        val mt5Mods =
            MT5OrderModification(
                price = changes.newStopPrice ?: changes.newLimitPrice,
            )
        val resp = client.modifyOrder(ticket, mt5Mods)
        if (!isOrderSuccessful(resp.result.retcode)) {
            val reason = resp.errorMessage ?: "modify rejected: retcode=${resp.result.retcode}"
            log.warn("MT5Broker ${profile.name} modify($orderId, ticket=$ticket) rejected: $reason")
            return SubmitAck(
                clientOrderId = orderId,
                brokerOrderId = ticket.toString(),
                accepted = false,
                rejectReason = reason,
            )
        }
        bus.publish(
            BrokerEvent.OrderModified(
                clientOrderId = orderId,
                brokerOrderId = ticket.toString(),
                changes = changes,
                strategyId = books.pendingBook.meta(ticket)?.strategyId ?: "",
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = orderId,
            brokerOrderId = ticket.toString(),
            accepted = true,
        )
    }
}
