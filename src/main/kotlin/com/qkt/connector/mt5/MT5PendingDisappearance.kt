package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * Decides what it meant when a resting order left the venue's order list: filled, or cancelled.
 * E.g. ticket 3258722177 vanishes from `/orders`; if `/positions` now holds it the fill is
 * published, otherwise the owner gets `OrderCancelled` ("external or gtd-expired"). A failed
 * `/positions` read decides nothing and the ticket is asked about again next round.
 */
internal class MT5PendingDisappearance(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val bus: EventBus,
    private val clock: Clock,
    private val books: MT5BrokerState,
    private val partialEntries: MT5PartialEntries,
    private val pendingFills: MT5PendingFills,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /**
     * Called by [MT5PendingOrderPoller] when a tracked ticket leaves `/orders`.
     *
     * Resolves the fill-vs-cancel ambiguity:
     *
     *   1. If the ticket was very recently filled (within the TTL), [onPendingPositionOpened]
     *      already emitted [BrokerEvent.OrderFilled]. Consume the marker and exit.
     *
     *   2. Otherwise the pending was cancelled externally or its GTD expired. Emit
     *      [BrokerEvent.OrderCancelled] with a clear reason.
     *
     *   3. If we don't track this ticket, it's an external pending (manual MetaTrader
     *      placement, another qkt instance with the same magic) — ignore.
     */
    fun onPendingDisappeared(ticket: Long): Boolean {
        val meta = books.pendingBook.meta(ticket) ?: return true

        val ttlMs = profile.pollIntervalMs * MT5BrokerLimits.DISAMBIGUATION_TTL_MULTIPLIER
        val recentlyFilledAt = books.recentlyFilledTickets[ticket]
        val now = clock.now()
        if (recentlyFilledAt != null && now - recentlyFilledAt < ttlMs) {
            books.pendingBook.forgetTicket(ticket)
            books.recentlyFilledTickets.remove(ticket)
            return true
        }

        // Cross-check /positions before treating as cancel. If the ticket is now a
        // position, the pending-poller observed the transition before the position-poller
        // did — synthesize the fill path here instead of phantom-cancelling. A FAILED
        // read leaves fill-vs-cancel unresolved: keep the order tracked and let the next
        // poll cycle re-resolve rather than phantom-cancelling a possibly-filled leg.
        val positionsNow =
            client.getPositions(magic = profile.magic) ?: run {
                log.warn(
                    "MT5Broker ${profile.name} pending {} disappeared but /positions read failed — " +
                        "deferring fill-vs-cancel resolution",
                    ticket,
                )
                return false
            }
        val partialPositionTicket = books.partialPositionByResidualTicket[ticket]
        val asPosition =
            positionsNow.firstOrNull {
                it.ticket == (partialPositionTicket ?: ticket)
            }
        if (asPosition != null) {
            if (books.partialPositionByResidualTicket.containsKey(ticket)) {
                partialEntries.reconcilePartialEntry(asPosition)
                partialEntries.cancelPartialEntryResidual(
                    ticket,
                    "residual disappeared from venue after partial fill",
                )
                return true
            }
            pendingFills.onPendingPositionOpened(asPosition)
            return true
        }

        if (books.partialPositionByResidualTicket.containsKey(ticket)) {
            partialEntries.cancelPartialEntryResidual(
                ticket,
                "residual disappeared from venue after partial fill",
            )
            return true
        }

        books.pendingBook.forgetTicket(ticket)
        // Evict stale entries opportunistically — cheap and prevents unbounded growth
        // if positions close before their pending-disappearance signal arrives.
        books.recentlyFilledTickets.entries.removeIf { now - it.value >= ttlMs }
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = meta.orderId,
                brokerOrderId = ticket.toString(),
                reason = "external or gtd-expired (pending disappeared from venue)",
                strategyId = meta.strategyId,
                timestamp = clock.now(),
            ),
        )
        return true
    }
}
