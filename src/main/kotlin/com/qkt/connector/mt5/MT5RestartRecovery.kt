package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * After a restart, joins the orders the engine remembers to what the venue actually holds.
 * E.g. an OCO restored with both legs working: a leg still resting is re-registered under its
 * ticket, a leg that filled while the daemon was down has its fill published now (unless the
 * ledger already booked that ticket), and its sibling is then cancelled by the usual fill path.
 * Returns the ids it accounted for; the rest have no venue counterpart and are the caller's to retire.
 */
internal class MT5RestartRecovery(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val pendingFills: MT5PendingFills,
    private val requestedProtection: MT5RequestedProtection,
    private val partialRecovery: MT5PartialEntryRecovery,
    private val seedTrackedTickets: (Set<Long>) -> Unit,
    private val recoveryReadAttempts: Int,
    private val recoveryReadBackoffMs: Long,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    fun recoverPendingOrders(
        orders: List<com.qkt.execution.ManagedOrder>,
        bookedTickets: Set<String>,
    ): Set<String> {
        if (orders.isEmpty()) return emptySet()
        val snapshot =
            readMT5RecoverySnapshot(
                attempts = recoveryReadAttempts,
                backoffMs = recoveryReadBackoffMs,
                onFailedAttempt = { attempt, reason ->
                    log.warn(
                        "MT5Broker {} recovery read failed (attempt {}/{}): {}",
                        profile.name,
                        attempt,
                        recoveryReadAttempts,
                        reason,
                    )
                },
                readPendingOrders = { client.getPendingOrders(magic = profile.magic) },
                readPositions = { client.getPositions(magic = profile.magic) },
            )
        val pending = snapshot.pendingOrders
        val positions = snapshot.positions
        val recoveredPartialIds = partialRecovery.recoverPartialEntries(orders, pending, positions, bookedTickets)
        val resolvedOrders =
            orders.filterNot { it.id in recoveredPartialIds }.map { order ->
                if (order.brokerOrderId != null) return@map order
                val pendingMatch =
                    pending.firstOrNull {
                        it.clientOrderId == order.id ||
                            matchesOrderComment(it.comment, order.id)
                    }
                val positionMatch =
                    positions.firstOrNull {
                        it.clientOrderId == order.id ||
                            matchesOrderComment(it.comment, order.id)
                    }
                val ticket = pendingMatch?.ticket ?: positionMatch?.ticket
                if (ticket == null) {
                    order
                } else {
                    order.copy(brokerOrderId = ticket.toString())
                }
            }
        val actions = classifyOcoRecovery(resolvedOrders, pending.map { it.ticket }.toSet(), positions)
        // Pass 1: re-seed every still-pending leg before any fill is emitted, so a
        // cancel triggered by pass 2 can resolve its sibling's ticket.
        for (a in actions) {
            if (a is OcoRecoveryAction.Reseed) {
                pendingFills.registerPendingTicket(
                    a.ticket,
                    MT5TicketMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        requestedProtection.protectionFor(a.order.request),
                    ),
                )
                log.info(
                    "MT5Broker ${profile.name} recovery: re-seeded pending leg ${a.order.id} ticket=${a.ticket}",
                )
            }
            if (a is OcoRecoveryAction.TrackVanished) {
                pendingFills.registerPendingTicket(
                    a.ticket,
                    MT5TicketMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        requestedProtection.protectionFor(a.order.request),
                    ),
                )
            }
        }
        val vanishedTickets =
            actions
                .filterIsInstance<OcoRecoveryAction.TrackVanished>()
                .mapTo(mutableSetOf()) { it.ticket }
        seedTrackedTickets(vanishedTickets)
        // Pass 2: republish the fill for any leg that filled while the daemon was down;
        // OrderManager's cancel-on-fill then unwinds the still-pending sibling.
        for (a in actions) {
            if (a is OcoRecoveryAction.EmitFill) {
                books.pendingBook.attribute(
                    a.position.ticket,
                    MT5TicketMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        requestedProtection.protectionFor(a.order.request),
                    ),
                )
                if (a.position.ticket.toString() in bookedTickets) {
                    // The ledger booked this execution before the restart; republishing it
                    // would book it again (#1096). The ticket stays tracked for its close.
                    books.positionBook.track(
                        a.position.ticket,
                        books.pendingBook.requireMeta(a.position.ticket),
                        a.order.request.symbol,
                        a.position.openTime,
                    )
                    log.info(
                        "MT5Broker ${profile.name} recovery: leg ${a.order.id} ticket=${a.position.ticket} already booked; not republishing",
                    )
                    // Hand the restored order its venue ticket without republishing the execution:
                    // OrderManager uses it to recognise the entry as position-backed (already
                    // filled and booked) instead of leaving it working for the rest of the session.
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = a.order.id,
                            brokerOrderId = a.position.ticket.toString(),
                            strategyId = a.order.request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    continue
                }
                log.info(
                    "MT5Broker ${profile.name} recovery: leg ${a.order.id} filled during downtime " +
                        "ticket=${a.position.ticket}",
                )
                pendingFills.onPendingPositionOpened(a.position)
            }
        }
        // Accounted for: partial fills adopted above, plus every order joined to a venue ticket
        // (re-seeded, filled during downtime, or vanished-and-tracked). Anything else has no
        // venue counterpart and is the caller's to retire.
        return recoveredPartialIds +
            resolvedOrders.filter { it.brokerOrderId != null }.mapTo(LinkedHashSet()) { it.id }
    }
}
