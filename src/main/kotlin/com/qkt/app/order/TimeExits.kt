package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.ExpiryAction
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isTerminal

/**
 * [OrderRequest.TimeExit] wrappers: a target order with a deadline. At the deadline an unfilled
 * target is cancelled; a filled one is either left alone (CANCEL) or closed at market
 * (CLOSE_AT_MARKET). Deadlines are compared against the tick clock on the engine thread.
 */
internal class TimeExits(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val clock: Clock,
    private val ops: OrderOps,
) {
    private val byId: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()

    // Reused every tick; cleared and refilled, so steady-state allocation is zero.
    private val expiredScratch = ArrayList<OrderRequest.TimeExit>()

    /** True while any time exit is waiting for its deadline. */
    fun isNotEmpty(): Boolean = byId.isNotEmpty()

    /** True when a waiting time exit targets [id], so that order must stay tracked. */
    fun targets(id: String): Boolean = byId.values.any { it.target.id == id }

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
        exposure.register(exposureEntryRequest(req.target))
        ops.dispatch(req.target)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    /** Fires every time exit whose deadline is at or before [now]. */
    fun expireDue(now: Long) {
        if (byId.isEmpty()) return
        expiredScratch.clear()
        for (te in byId.values) {
            if (now >= te.deadline.toEpochMilli()) expiredScratch.add(te)
        }
        for (i in expiredScratch.indices) {
            val te = expiredScratch[i]
            byId.remove(te.id)
            onExpiry(te)
        }
    }

    private fun onExpiry(te: OrderRequest.TimeExit) {
        val target = book[te.target.id] ?: return
        when (te.onExpiry) {
            ExpiryAction.CANCEL -> {
                if (!target.state.isTerminal) ops.cancel(te.target.id)
                ops.update(te.id) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
            }
            ExpiryAction.CLOSE_AT_MARKET -> {
                if (target.state == OrderState.FILLED) {
                    val exitSide = if (te.target.side == Side.BUY) Side.SELL else Side.BUY
                    val closing =
                        OrderRequest.Market(
                            id = "${te.id}-close",
                            symbol = te.symbol,
                            side = exitSide,
                            quantity = te.target.quantity,
                            timeInForce = te.timeInForce,
                            timestamp = clock.now(),
                            strategyId = te.strategyId,
                            legIntent = te.target.exitLegIntent(),
                        )
                    ops.submit(closing)
                } else if (!target.state.isTerminal) {
                    ops.cancel(te.target.id)
                }
                ops.update(te.id) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
            }
        }
    }
}
