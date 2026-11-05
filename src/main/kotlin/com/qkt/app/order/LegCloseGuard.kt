package com.qkt.app.order

import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.common.Side
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.isTerminal
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * At most one close per leg, whatever sends it (#1638). A book-halt flatten, an overdue `EXIT AFTER`,
 * a `CLOSE` and an operator flatten each size their close from the open leg, and none of them saw
 * another's close still in flight: on a netting venue the second fill opened a fresh opposite position
 * the ledger never booked. A close is covered when a market close of the same strategy and symbol, on
 * the same side, is still working and either names the same leg (by id or venue ticket) or, where
 * the venue nets, already covers the strategy's whole open position. A covered close is held here,
 * not sent; when the close covering it ends, it is cut to what is still open, dropped if nothing is
 * left, and otherwise sent. Only in-flight market closes count: a resting stop or target is not a close
 * in flight. Cold path: runs once per close request, scanning only the symbol's live orders.
 */
internal class LegCloseGuard(
    private val book: OrderBook,
    private val positionMode: (symbol: String) -> PositionAccountingMode,
    private val strategyNetQty: ((strategyId: String, symbol: String) -> BigDecimal)?,
    private val openLegQuantity: ((strategyId: String, legId: String) -> BigDecimal?)?,
    private val log: Logger,
) {
    /** Held closes keyed by the working close that covers them. */
    private val held: MutableMap<String, MutableList<OrderRequest.Market>> = mutableMapOf()

    /** The close being released, already ended though the order book may not have heard yet. */
    private var ended: String? = null

    /** True while nothing is held, so the terminal-event hook costs one map check. */
    fun isEmpty(): Boolean = held.isEmpty()

    /**
     * Holds [request] when a working close already covers its leg, and returns the refusal ack;
     * null when it may be sent.
     */
    fun hold(request: OrderRequest): SubmitAck? {
        if (request !is OrderRequest.Market) return null
        val intent = request.legIntent as? LegIntent.Close ?: return null
        val cover = coveringClose(request.strategyId, request.symbol, request.side, intent, request.id) ?: return null
        held.getOrPut(cover.id) { mutableListOf() }.add(request)
        val reason = "close ${cover.id} of the same position is still working; held until it ends"
        log.warn(
            "close {} for {} on {} (leg {} / ticket {}) not sent: {}",
            request.id,
            request.strategyId,
            request.symbol,
            intent.legId,
            intent.ticket,
            reason,
        )
        return SubmitAck(clientOrderId = request.id, brokerOrderId = null, accepted = false, rejectReason = reason)
    }

    /**
     * The working market close of [strategyId] on [symbol] that already closes the leg a [closingSide]
     * close would ([intent] names it, null for a net close), or null when there is none.
     */
    fun coveringClose(
        strategyId: String,
        symbol: String,
        closingSide: Side,
        intent: LegIntent.Close?,
        excludingId: String? = null,
    ): ManagedOrder? {
        val ids = book.liveIdsFor(symbol) ?: return null
        var first: ManagedOrder? = null
        var covered = BigDecimal.ZERO
        for (id in ids) {
            if (id == excludingId || id == ended) continue
            val order = book[id] ?: continue
            val other = order.request as? OrderRequest.Market ?: continue
            if (order.state.isTerminal || other.strategyId != strategyId || other.side != closingSide) continue
            val otherIntent = other.legIntent
            if (intent != null && otherIntent is LegIntent.Close && sameLeg(otherIntent, intent)) return order
            if (otherIntent is LegIntent.Close || otherIntent == LegIntent.Net) {
                covered = covered.add(other.quantity.subtract(order.cumulativeFilledQuantity))
                if (first == null) first = order
            }
        }
        if (first == null || positionMode(symbol) != PositionAccountingMode.NETTING) return null
        return first.takeIf { openOnSide(strategyId, symbol, closingSide)?.let { it <= covered } == true }
    }

    /**
     * The close [id] ended: each close it held is cut to what the strategy still holds on its leg (a
     * covering close may have filled in part before it was cancelled), dropped when nothing is left,
     * and otherwise passed to [submit], which judges it afresh. [id] counts as ended even before the
     * order book hears of it, since this may run ahead of the book's own update.
     */
    fun release(
        id: String,
        filled: Boolean,
        submit: (OrderRequest) -> Unit,
    ) {
        val waiting = held.remove(id) ?: return
        ended = id
        try {
            for (request in waiting) {
                val sized = sizedToOpen(request, filled)
                if (sized == null) {
                    log.info("held close {} dropped: close {} left nothing of the position to close", request.id, id)
                } else {
                    if (sized !== request) log.info("held close {} cut to {} still open", request.id, sized.quantity)
                    submit(sized)
                }
            }
        } finally {
            ended = null
        }
    }

    // Never more than is still open: a close sized before another close filled in part would over-close.
    private fun sizedToOpen(
        request: OrderRequest.Market,
        filled: Boolean,
    ): OrderRequest.Market? {
        val intent = request.legIntent as? LegIntent.Close ?: return request
        val open = openOf(request, intent, filled) ?: return request
        if (open.signum() <= 0) return null
        if (open >= request.quantity) return request
        return request.copy(quantity = open, partialClose = true, legIntent = intent.copy(partial = true))
    }

    // What the request's leg still holds; null when it cannot be known here.
    private fun openOf(
        request: OrderRequest.Market,
        intent: LegIntent.Close,
        filled: Boolean,
    ): BigDecimal? {
        if (positionMode(request.symbol) == PositionAccountingMode.NETTING) {
            return openOnSide(request.strategyId, request.symbol, request.side)
        }
        val legId = intent.legId ?: return if (filled) BigDecimal.ZERO else null
        val resolver = openLegQuantity ?: return null
        return resolver(request.strategyId, legId) ?: BigDecimal.ZERO
    }

    // What the strategy holds that a [closingSide] order reduces: its long for a SELL, its short for a BUY.
    private fun openOnSide(
        strategyId: String,
        symbol: String,
        closingSide: Side,
    ): BigDecimal? {
        val net = strategyNetQty?.invoke(strategyId, symbol) ?: return null
        return if (closingSide == Side.SELL) net else net.negate()
    }

    private fun sameLeg(
        a: LegIntent.Close,
        b: LegIntent.Close,
    ): Boolean = (a.legId != null && a.legId == b.legId) || (a.ticket != null && a.ticket == b.ticket)
}
