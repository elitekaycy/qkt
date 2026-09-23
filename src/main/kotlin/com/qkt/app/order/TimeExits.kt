package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.entryFillId
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isTerminal
import java.math.BigDecimal

/**
 * [OrderRequest.TimeExit] wrappers: a target order with a deadline. At the deadline an unfilled
 * target is cancelled; a filled one is either left alone (CANCEL) or closed at market
 * (CLOSE_AT_MARKET). A fill-anchored exit ([OrderRequest.TimeExit.holdMs]) arms its deadline
 * when the target's entry fills. Deadlines are compared against the tick clock on the engine
 * thread, so the exit fires on the first tick at or after the deadline in backtest and live.
 */
internal class TimeExits(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val clock: Clock,
    private val ops: OrderOps,
    private val closeTicketFor: ((String, String) -> String?)? = null,
    private val openLegQuantity: ((String, String) -> BigDecimal?)? = null,
    private val strategyNetQty: ((String, String) -> BigDecimal)? = null,
) {
    private val byId: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()
    private val deadlines: MutableMap<String, Long> = mutableMapOf()
    private val awaitingFill: MutableMap<String, String> = mutableMapOf()

    // Reused every tick; cleared and refilled, so steady-state allocation is zero.
    private val expiredScratch = ArrayList<OrderRequest.TimeExit>()

    /** True while any time exit is waiting for its fill or its deadline. */
    fun isNotEmpty(): Boolean = byId.isNotEmpty()

    /** True when a waiting time exit targets [id] or its entry, so that order must stay tracked. */
    fun targets(id: String): Boolean = byId.values.any { it.target.id == id || it.entryFillId == id }

    /** Starts [req]: tracks its target and dispatches it; the deadline is watched per tick. */
    fun submit(req: OrderRequest.TimeExit): SubmitAck {
        val now = clock.now()
        ops.update(req.id) {
            it.copy(
                state = OrderState.WORKING,
                childClientOrderIds = listOf(req.target.id),
                lastUpdatedAt = now,
            )
        }
        ops.track(
            ManagedOrder(
                id = req.target.id,
                request = req.target,
                state = OrderState.CREATED,
                parentClientOrderId = req.id,
                createdAt = now,
                lastUpdatedAt = now,
            ),
        )
        byId[req.id] = req
        if (req.holdMs == null) {
            deadlines[req.id] = req.deadline.toEpochMilli()
        } else {
            awaitingFill[req.entryFillId] = req.id
        }
        exposure.register(exposureEntryRequest(req.target))
        ops.dispatch(req.target)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    /** Arms the fill-anchored exit whose entry is [clientOrderId], timed from now. */
    fun onFilled(clientOrderId: String) {
        if (awaitingFill.isEmpty()) return
        val id = awaitingFill.remove(clientOrderId) ?: return
        val hold = byId[id]?.holdMs ?: return
        deadlines[id] = clock.now() + hold
    }

    /** Fires every time exit whose deadline is at or before [now]; drops exits whose entry died unfilled. */
    fun expireDue(now: Long) {
        if (byId.isEmpty()) return
        expiredScratch.clear()
        for ((id, deadline) in deadlines) {
            if (now >= deadline) expiredScratch.add(byId.getValue(id))
        }
        if (awaitingFill.isNotEmpty()) {
            for ((entryId, id) in awaitingFill) {
                val entry = book[entryId] ?: continue
                if (entry.state.isTerminal && entry.state != OrderState.FILLED) expiredScratch.add(byId.getValue(id))
            }
        }
        for (i in expiredScratch.indices) {
            val te = expiredScratch[i]
            byId.remove(te.id)
            deadlines.remove(te.id)
            awaitingFill.remove(te.entryFillId)
            onExpiry(te)
        }
    }

    private fun onExpiry(te: OrderRequest.TimeExit) {
        val target = book[te.target.id]
        when (te.onExpiry) {
            ExpiryAction.CANCEL -> {
                if (target != null && !target.state.isTerminal) ops.cancel(te.target.id)
                ops.update(te.id) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            }
            ExpiryAction.CLOSE_AT_MARKET -> {
                if (book[te.entryFillId]?.state == OrderState.FILLED) {
                    closeFilled(te)
                } else if (target != null && !target.state.isTerminal) {
                    ops.cancel(te.target.id)
                }
                ops.update(te.id) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
            }
        }
    }

    // Closes only what is still open of the target's own leg, and stands the target's protective
    // exits down first so a resting TP/SL cannot fire against a position that is already gone.
    private fun closeFilled(te: OrderRequest.TimeExit) {
        val exit = te.target.exitLegIntent()
        val legId = (exit as? LegIntent.Close)?.legId
        val qty = openQuantity(te, legId)
        if (qty == null || qty.signum() <= 0) return
        if (te.target is OrderRequest.Bracket) ops.cancel(te.target.id)
        val ticket = legId?.let { closeTicketFor?.invoke(te.strategyId, it) }
        ops.submit(
            OrderRequest.Market(
                id = "${te.id}-close",
                symbol = te.symbol,
                side = if (te.target.side == Side.BUY) Side.SELL else Side.BUY,
                quantity = qty,
                timeInForce = te.timeInForce,
                timestamp = clock.now(),
                strategyId = te.strategyId,
                closesTicket = ticket,
                closesLegId = legId,
                legIntent = if (legId != null) LegIntent.Close(legId = legId, ticket = ticket) else exit,
            ),
        )
    }

    private fun openQuantity(
        te: OrderRequest.TimeExit,
        legId: String?,
    ): BigDecimal? {
        val filled = te.target.quantity
        if (legId != null) {
            val resolver = openLegQuantity ?: return filled
            return resolver(te.strategyId, legId)?.min(filled)
        }
        val net = strategyNetQty?.invoke(te.strategyId, te.symbol) ?: return filled
        val sameSide = if (te.target.side == Side.BUY) net else net.negate()
        return if (sameSide.signum() > 0) sameSide.min(filled) else null
    }
}
