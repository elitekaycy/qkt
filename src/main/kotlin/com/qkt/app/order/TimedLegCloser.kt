package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isTerminal
import com.qkt.persistence.PersistedTimeExit
import java.math.BigDecimal

/**
 * Closes the leg an armed timed exit guards: only the quantity still open, by leg id and venue
 * ticket on a hedging venue, by side where the venue nets.
 */
internal class TimedLegCloser(
    private val book: OrderBook,
    private val clock: Clock,
    private val ops: OrderOps,
    private val closeTicketFor: ((String, String) -> String?)?,
    private val openLegQuantity: ((String, String) -> BigDecimal?)?,
    private val strategyNetQty: ((String, String) -> BigDecimal)?,
) {
    // Closes only what is still open of the leg, and stands its protective exits down first so a
    // resting TP/SL cannot fire against a position that is already gone.
    fun close(exit: PersistedTimeExit) {
        val qty = openQuantity(exit)
        if (qty == null || qty.signum() <= 0) return
        for (id in exit.protectiveIds) ops.cancel(id)
        val ticket = exit.legId?.let { closeTicketFor?.invoke(exit.strategyId, it) } ?: exit.ticket
        ops.submit(closeOrder("${exit.id}-close", exit.strategyId, exit.symbol, exit.side, qty, exit.legId, ticket))
    }

    fun closeOrder(
        id: String,
        strategyId: String,
        symbol: String,
        entrySide: Side,
        qty: BigDecimal,
        legId: String?,
        ticket: String?,
    ) = OrderRequest.Market(
        id = id,
        symbol = symbol,
        side = if (entrySide == Side.BUY) Side.SELL else Side.BUY,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = clock.now(),
        strategyId = strategyId,
        closesTicket = ticket,
        closesLegId = legId,
        legIntent =
            if (legId != null ||
                ticket != null
            ) {
                LegIntent.Close(legId = legId, ticket = ticket)
            } else {
                LegIntent.Net
            },
    )

    fun openQuantity(exit: PersistedTimeExit): BigDecimal? {
        if (exit.legId != null) {
            val resolver = openLegQuantity ?: return exit.quantity
            return resolver(exit.strategyId, exit.legId)?.min(exit.quantity)
        }
        val net = strategyNetQty?.invoke(exit.strategyId, exit.symbol) ?: return exit.quantity
        val sameSide = if (exit.side == Side.BUY) net else net.negate()
        return if (sameSide.signum() > 0) sameSide.min(exit.quantity) else null
    }

    fun protectiveIdsFor(target: OrderRequest): List<String> =
        if (target is OrderRequest.Bracket) {
            listOf(target.id, "${target.id}-oco", "${target.id}-tp", "${target.id}-sl")
        } else {
            emptyList()
        }

    /** Expires an absolute-deadline exit: cancel an unfilled target, or close a filled one when asked. */
    fun expireLegacy(te: OrderRequest.TimeExit) {
        val target = book[te.target.id]
        val finalState = if (te.onExpiry == ExpiryAction.CANCEL) OrderState.CANCELLED else OrderState.FILLED
        if (te.onExpiry == ExpiryAction.CLOSE_AT_MARKET && target?.state == OrderState.FILLED) {
            val legId = (te.target.exitLegIntent() as? LegIntent.Close)?.legId
            ops.submit(
                closeOrder(
                    "${te.id}-close",
                    te.strategyId,
                    te.symbol,
                    te.target.side,
                    te.target.quantity,
                    legId,
                    null,
                ),
            )
        } else if (target != null && !target.state.isTerminal) {
            ops.cancel(te.target.id)
        }
        ops.update(te.id) { it.copy(state = finalState, lastUpdatedAt = clock.now()) }
    }

    /** [exit] with its venue ticket filled in, once the ledger knows it; null when unchanged. */
    fun withTicket(exit: PersistedTimeExit): PersistedTimeExit? {
        if (exit.ticket != null || exit.legId == null) return null
        return closeTicketFor?.invoke(exit.strategyId, exit.legId)?.let { exit.copy(ticket = it) }
    }
}
