package com.qkt.connector.mt5

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** The stop and target an order asked the venue to hold on its position. */
internal data class MT5PositionProtection(
    val stopLoss: BigDecimal?,
    val takeProfit: BigDecimal?,
)

/** Who a venue ticket belongs to: the engine order that created it, its strategy, the protection it asked for. */
internal data class MT5TicketMeta(
    val orderId: String,
    val strategyId: String,
    val protection: MT5PositionProtection? = null,
)

/**
 * The open positions this session attributes, by MT5 ticket: whose they are, which qkt symbol they
 * trade, and when they opened. The position poller resolves a ticket that disappears back to its
 * order and strategy from here, e.g. ticket 3258722072 → (dsl-atto_oto--0, atto_oto, EXNESS:BTCUSD,
 * 11:29:02). The three facts arrive and leave at slightly different moments on some paths (a
 * recovered position is attributed before its open time is known; an engine close keeps the open
 * time until the venue confirms), so each can be set and cleared on its own.
 */
internal class MT5PositionBook {
    private val metaByTicket: MutableMap<Long, MT5TicketMeta> = ConcurrentHashMap()
    private val symbolByTicket: MutableMap<Long, String> = ConcurrentHashMap()
    private val openedAtByTicket: MutableMap<Long, Long> = ConcurrentHashMap()

    /** A position whose owner, symbol and open time are all known at once. */
    fun track(
        ticket: Long,
        meta: MT5TicketMeta,
        symbol: String,
        openedAtMs: Long,
    ) {
        metaByTicket[ticket] = meta
        symbolByTicket[ticket] = symbol
        openedAtByTicket[ticket] = openedAtMs
    }

    fun attribute(
        ticket: Long,
        meta: MT5TicketMeta,
    ) {
        metaByTicket[ticket] = meta
    }

    fun setSymbol(
        ticket: Long,
        symbol: String,
    ) {
        symbolByTicket[ticket] = symbol
    }

    fun setOpenedAt(
        ticket: Long,
        openedAtMs: Long,
    ) {
        openedAtByTicket[ticket] = openedAtMs
    }

    fun meta(ticket: Long): MT5TicketMeta? = metaByTicket[ticket]

    fun symbol(ticket: Long): String? = symbolByTicket[ticket]

    fun openedAt(ticket: Long): Long? = openedAtByTicket[ticket]

    fun isAttributed(ticket: Long): Boolean = metaByTicket.containsKey(ticket)

    fun updateMeta(
        ticket: Long,
        change: (MT5TicketMeta) -> MT5TicketMeta,
    ) {
        metaByTicket.computeIfPresent(ticket) { _, meta -> change(meta) }
    }

    /** Forget whose the ticket is and what it trades; its open time is cleared separately. */
    fun forgetAttribution(ticket: Long) {
        metaByTicket.remove(ticket)
        symbolByTicket.remove(ticket)
    }

    fun forgetOpenedAt(ticket: Long) {
        openedAtByTicket.remove(ticket)
    }

    fun forget(ticket: Long) {
        forgetAttribution(ticket)
        forgetOpenedAt(ticket)
    }

    /** Ticket → strategy id for every attributed position, as the insights mirror wants it. */
    fun attributions(): Map<String, String> =
        metaByTicket.entries.associate { (ticket, meta) ->
            ticket.toString() to
                meta.strategyId
        }
}
