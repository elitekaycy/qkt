package com.qkt.connector.mt5

import java.util.concurrent.ConcurrentHashMap

/**
 * The orders this session has resting at the venue, both ways round: engine order id → MT5 ticket
 * (to cancel one) and ticket → owner (to publish the fill when the ticket turns into a position),
 * e.g. dsl-gold--1 ↔ 3258722177 owned by (dsl-gold--1, gold_trend).
 *
 * Operations are single map steps or pairs of them and take no lock of their own: callers that must
 * change this book together with the partial-entry or early-position state hold the broker's
 * pending-transition lock, exactly as they did when these were two maps inside the broker.
 */
internal class MT5PendingBook {
    private val ticketByOrderId: MutableMap<String, Long> = ConcurrentHashMap()
    private val metaByTicket: MutableMap<Long, MT5TicketMeta> = ConcurrentHashMap()

    /** A resting order: cancellable by [MT5TicketMeta.orderId], attributable by [ticket]. */
    fun register(
        ticket: Long,
        meta: MT5TicketMeta,
    ) {
        ticketByOrderId[meta.orderId] = ticket
        metaByTicket[ticket] = meta
    }

    /** Who owns [ticket], without making the order cancellable by id (a fill seen during recovery). */
    fun attribute(
        ticket: Long,
        meta: MT5TicketMeta,
    ) {
        metaByTicket[ticket] = meta
    }

    fun ticketOf(orderId: String): Long? = ticketByOrderId[orderId]

    fun meta(ticket: Long): MT5TicketMeta? = metaByTicket[ticket]

    fun requireMeta(ticket: Long): MT5TicketMeta = metaByTicket.getValue(ticket)

    fun isPending(ticket: Long): Boolean = metaByTicket.containsKey(ticket)

    /** True while [orderId] and [ticket] still name each other and [meta] still owns the ticket. */
    fun stillIs(
        orderId: String,
        ticket: Long,
        meta: MT5TicketMeta,
    ): Boolean = metaByTicket[ticket] == meta && ticketByOrderId[orderId] == ticket

    /** The ticket became a position: take its owner out, leaving the id link for the caller to drop. */
    fun takeMeta(ticket: Long): MT5TicketMeta? = metaByTicket.remove(ticket)

    fun forgetOrderId(orderId: String) {
        ticketByOrderId.remove(orderId)
    }

    /** Drop one leg by both of its keys, whatever they currently point at (OCO rollback, cancel). */
    fun forgetLeg(
        orderId: String,
        ticket: Long,
    ) {
        ticketByOrderId.remove(orderId)
        metaByTicket.remove(ticket)
    }

    /** Drop the pair only if it is still exactly this one: a newer registration is left alone. */
    fun forgetIfStill(
        orderId: String,
        ticket: Long,
        meta: MT5TicketMeta,
    ) {
        metaByTicket.remove(ticket, meta)
        ticketByOrderId.remove(orderId, ticket)
    }

    /** The ticket is gone from the venue: drop it and every order id that pointed at it. */
    fun forgetTicket(ticket: Long) {
        metaByTicket.remove(ticket)
        ticketByOrderId.entries.removeIf { it.value == ticket }
    }
}
