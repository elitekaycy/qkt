package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * After a restart, recognises an entry that had filled only in part and picks it up where it was.
 * E.g. `entry-1` asked for 1.00 lot and the venue holds a 0.40 position plus a resting residual
 * under that id: the 0.40 is published as a partial fill (unless the ledger already booked the
 * ticket) and the residual is tracked again; with no residual left the order is cancelled instead.
 */
internal class MT5PartialEntryRecovery(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val partialEntries: MT5PartialEntries,
    private val requestedProtection: MT5RequestedProtection,
    private val seedTrackedTickets: (Set<Long>) -> Unit,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun recoverPartialEntries(
        orders: List<com.qkt.execution.ManagedOrder>,
        pending: List<MT5PendingOrder>,
        positions: List<MT5Position>,
        bookedTickets: Set<String>,
    ): Set<String> {
        val recovered = mutableSetOf<String>()
        for (order in orders) {
            val pendingMatches =
                pending.filter {
                    it.clientOrderId == order.id || matchesOrderComment(it.comment, order.id)
                }
            val positionMatches =
                positions.filter {
                    it.clientOrderId == order.id || matchesOrderComment(it.comment, order.id)
                }
            if (pendingMatches.size > 1 || positionMatches.size != 1) continue
            val position = positionMatches.single()
            val requestedQuantity = order.request.quantity
            if (position.volume.signum() <= 0 || position.volume >= requestedQuantity) continue

            val meta =
                MT5TicketMeta(
                    order.id,
                    order.request.strategyId,
                    requestedProtection.protectionFor(order.request),
                )
            books.positionBook.track(position.ticket, meta, order.request.symbol, position.openTime)
            if (position.ticket.toString() in bookedTickets) {
                // Already in the ledger from before the restart: keep the ticket tracked and let
                // the residual resolve, but never republish the booked execution (#1096).
                log.info(
                    "MT5Broker ${profile.name} recovery: partial entry ${order.id} ticket=${position.ticket} already booked; not republishing",
                )
                recovered.add(order.id)
                continue
            }
            val partialEvent =
                BrokerEvent.OrderPartiallyFilled(
                    clientOrderId = order.id,
                    brokerOrderId = position.ticket.toString(),
                    symbol = order.request.symbol,
                    side = order.request.side,
                    price = position.priceOpen,
                    quantity = position.volume,
                    cumulativeFilled = position.volume,
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                )
            val residual = pendingMatches.singleOrNull()
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = order.id,
                    brokerOrderId = (residual?.ticket ?: position.ticket).toString(),
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            if (residual != null) {
                partialEntries.registerPartialEntry(
                    PartialEntryState(
                        meta = meta,
                        residualTicket = residual.ticket,
                        positionTicket = position.ticket,
                        symbol = order.request.symbol,
                        side = order.request.side,
                        requestedQuantity = requestedQuantity,
                        cumulativeFilled = position.volume,
                        averageFillPrice = position.priceOpen,
                    ),
                    openedAtMs = position.openTime,
                )
                bus.publish(partialEvent)
                seedTrackedTickets(setOf(residual.ticket))
                log.info(
                    "MT5Broker {} recovery: restored partial entry {} residual={} position={} cumulative={}",
                    profile.name,
                    order.id,
                    residual.ticket,
                    position.ticket,
                    position.volume,
                )
            } else {
                bus.publish(partialEvent)
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = order.id,
                        brokerOrderId = position.ticket.toString(),
                        reason = "residual absent during partial-entry recovery",
                        strategyId = order.request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
            recovered += order.id
        }
        return recovered
    }
}
