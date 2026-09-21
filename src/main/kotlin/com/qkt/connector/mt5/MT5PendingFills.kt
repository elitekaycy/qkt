package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import org.slf4j.LoggerFactory

/**
 * Turns a resting order into a fill when its ticket shows up as a venue position, whichever side
 * sees it first. E.g. BUY_STOP ticket 3258722177 triggers: the position poller reports a new
 * position with that ticket and the owner registered at placement gets its `OrderFilled`. If the
 * poller wins the race against the placement reply, the position is parked and replayed the moment
 * the ticket is registered.
 */
internal class MT5PendingFills(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val mt5Symbol: MT5Symbol,
    private val books: MT5BrokerState,
    private val partialEntries: MT5PartialEntries,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /**
     * Called by [MT5PositionPoller] when a venue position appears that wasn't in the
     * last snapshot. If the position's ticket matches a tracked pending order, this
     * means the pending filled — emit [BrokerEvent.OrderFilled] with the original
     * client orderId so [com.qkt.app.OrderManager] can:
     *   1. mark the order FILLED
     *   2. iterate `siblings[orderId]` and cancel any OCO siblings
     *   3. update strategy-side position state
     *
     * If the ticket isn't in [pendingBook], the position is external (manual user
     * trade or another qkt instance with the same magic) — ignore it; reconciliation
     * is a separate concern. A ticket in [partialEntryByPositionTicket] remains working until its
     * cumulative position volume reaches the requested quantity or the residual disappears.
     */
    fun onPendingPositionOpened(position: MT5Position): Boolean {
        if (partialEntries.reconcilePartialEntry(position)) return true
        val meta =
            synchronized(books.pendingTransitionLock) {
                books.pendingBook.takeMeta(position.ticket)
                    ?: run {
                        if (!books.positionBook.isAttributed(position.ticket)) {
                            books.earlyPositionByTicket[position.ticket] = position
                        }
                        null
                    }
            }
        if (meta == null) {
            // Already tracked? The Fix A cross-check in onPendingDisappeared may have
            // synthesized this fill on a prior pending-poller tick; the position-poller
            // is now seeing the same ticket in its opened-delta. Silent — already done.
            if (books.positionBook.isAttributed(position.ticket)) return true
            log.warn(
                "MT5Broker {} saw new position ticket={} symbol={} side={} magic={} with no qkt-side " +
                    "pending meta yet; deferring attribution while awaiting a possible asynchronous " +
                    "placement response",
                profile.name,
                position.ticket,
                position.symbol,
                if (position.type == 0) "BUY" else "SELL",
                profile.magic,
            )
            return false
        }
        publishPendingPositionOpened(position, meta)
        return true
    }

    fun registerPendingTicket(
        ticket: Long,
        meta: MT5TicketMeta,
    ) {
        val earlyPosition =
            synchronized(books.pendingTransitionLock) {
                books.pendingBook.register(ticket, meta)
                books.earlyPositionByTicket.remove(ticket)
            }
        if (earlyPosition != null) {
            onPendingPositionOpened(earlyPosition)
        }
    }

    private fun publishPendingPositionOpened(
        position: MT5Position,
        meta: MT5TicketMeta,
    ) {
        books.pendingBook.forgetOrderId(meta.orderId)
        // Mark the ticket as recently filled so the pending-order poller doesn't
        // mistake the subsequent "disappeared from /orders" for an external cancel.
        books.recentlyFilledTickets[position.ticket] = clock.now()
        // Sweep stale entries on this always-firing position path too: the matching sweep in
        // onPendingDisappeared only runs when the gateway exposes an /orders endpoint, so without
        // this a gateway lacking /orders would let books.recentlyFilledTickets grow unbounded.
        books.recentlyFilledTickets.entries.removeIf {
            clock.now() - it.value >= profile.pollIntervalMs * MT5BrokerLimits.DISAMBIGUATION_TTL_MULTIPLIER
        }
        // Keep the meta accessible to the position poller for the eventual close event.
        books.positionBook.attribute(position.ticket, meta)
        books.positionBook.setOpenedAt(position.ticket, position.openTime)
        val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(position.symbol)}"
        books.positionBook.setSymbol(position.ticket, qktSymbol)
        val filledSide = if (position.type == 0) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = meta.orderId,
                brokerOrderId = position.ticket.toString(),
                symbol = qktSymbol,
                side = filledSide,
                price = position.priceOpen,
                quantity = position.volume,
                strategyId = meta.strategyId,
                timestamp = clock.now(),
            ),
        )
    }
}
