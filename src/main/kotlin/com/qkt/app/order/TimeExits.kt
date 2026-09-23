package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.execution.LegIntent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.entryFillId
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isTerminal
import com.qkt.persistence.PersistedTimeExit
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * [OrderRequest.TimeExit] wrappers: a target order with a deadline. At the deadline an unfilled
 * target is cancelled; a filled one is either left alone (CANCEL) or closed at market
 * (CLOSE_AT_MARKET). A fill-anchored exit ([OrderRequest.TimeExit.holdMs]) is armed in [armed]
 * when its entry fills, persisted there, and re-armed by [restore] after a restart. Deadlines are
 * compared against the tick clock on the engine thread, so exits fire on the first tick at or
 * after the deadline in backtest and live.
 */
internal class TimeExits(
    private val book: OrderBook,
    private val exposure: PendingExposureBook,
    private val clock: Clock,
    private val ops: OrderOps,
    private val armed: TimedExitBook = TimedExitBook(),
    private val log: Logger? = null,
    private val closeTicketFor: ((String, String) -> String?)? = null,
    private val openLegQuantity: ((String, String) -> BigDecimal?)? = null,
    private val strategyNetQty: ((String, String) -> BigDecimal)? = null,
) {
    private val closer = TimedLegCloser(book, clock, ops, closeTicketFor, openLegQuantity, strategyNetQty)
    private val byId: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()
    private val deadlines: MutableMap<String, Long> = mutableMapOf()
    private val awaitingFill: MutableMap<String, String> = mutableMapOf()

    // Set by a fill or a restore; the next tick re-checks that every armed leg is still open.
    private var legCheckDue = false

    // Reused every tick; cleared and refilled, so steady-state allocation is zero.
    private val expiredScratch = ArrayList<String>()

    /** True while any time exit is waiting for its fill or its deadline. */
    fun isNotEmpty(): Boolean = byId.isNotEmpty() || !armed.isEmpty()

    /** True when a waiting time exit targets [id] or its entry, so that order must stay tracked. */
    fun targets(id: String): Boolean = byId.values.any { it.target.id == id || it.entryFillId == id }

    /** Starts [req]: tracks its target and dispatches it; the deadline is watched per tick. */
    fun submit(req: OrderRequest.TimeExit): SubmitAck {
        val now = clock.now()
        ops.update(req.id) {
            it.copy(state = OrderState.WORKING, childClientOrderIds = listOf(req.target.id), lastUpdatedAt = now)
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
        if (req.holdMs ==
            null
        ) {
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
        if (!armed.isEmpty()) legCheckDue = true
        if (awaitingFill.isEmpty()) return
        val id = awaitingFill.remove(clientOrderId) ?: return
        val te = byId.remove(id) ?: return
        val hold = te.holdMs ?: return
        val legId = (te.target.exitLegIntent() as? LegIntent.Close)?.legId
        armed[id] =
            PersistedTimeExit(
                id = id,
                strategyId = te.strategyId,
                symbol = te.symbol,
                side = te.target.side,
                quantity = te.target.quantity,
                legId = legId,
                ticket = null,
                deadlineMs = clock.now() + hold,
                protectiveIds = closer.protectiveIdsFor(te.target),
            )
        legCheckDue = true
        ops.update(te.id) { it.copy(state = OrderState.FILLED, lastUpdatedAt = clock.now()) }
    }

    /** Re-arms exits persisted before a restart; overdue ones close on the first tick. */
    fun restore(exits: List<PersistedTimeExit>) {
        if (exits.isEmpty()) return
        val now = clock.now()
        for (exit in exits) {
            armed[exit.id] = exit
            if (exit.deadlineMs <= now) {
                log?.warn(
                    "timed exit {} for leg {} ({} {}) passed its deadline during downtime by {}ms; " +
                        "closing on the first tick",
                    exit.id,
                    exit.legId ?: exit.symbol,
                    exit.strategyId,
                    exit.symbol,
                    now - exit.deadlineMs,
                )
            } else {
                val leg = exit.legId ?: exit.symbol
                log?.info("restored timed exit {} for leg {}, closes in {}ms", exit.id, leg, exit.deadlineMs - now)
            }
        }
        legCheckDue = true
    }

    /** Fires every exit due at [now]; drops armed exits whose leg is gone and exits whose entry never filled. */
    fun expireDue(now: Long) {
        if (byId.isEmpty() && armed.isEmpty()) return
        expireLegacy(now)
        dropUnfilled()
        var changed = false
        if (legCheckDue) {
            legCheckDue = false
            changed = dropClosedLegs()
        }
        if (armed.isEmpty()) {
            if (changed) ops.persistAll()
            return
        }
        expiredScratch.clear()
        for (exit in armed.values) if (now >= exit.deadlineMs) expiredScratch.add(exit.id)
        for (i in expiredScratch.indices) {
            val exit = armed.remove(expiredScratch[i]) ?: continue
            closer.close(exit)
            changed = true
        }
        if (changed) ops.persistAll()
    }

    private fun expireLegacy(now: Long) {
        if (deadlines.isEmpty()) return
        expiredScratch.clear()
        for ((id, deadline) in deadlines) if (now >= deadline) expiredScratch.add(id)
        for (i in expiredScratch.indices) {
            deadlines.remove(expiredScratch[i])
            byId.remove(expiredScratch[i])?.let(closer::expireLegacy)
        }
    }

    private fun dropUnfilled() {
        if (awaitingFill.isEmpty()) return
        expiredScratch.clear()
        for ((entryId, id) in awaitingFill) {
            val entry = book[entryId] ?: continue
            if (entry.state.isTerminal && entry.state != OrderState.FILLED) expiredScratch.add(id)
        }
        for (i in expiredScratch.indices) {
            val te = byId.remove(expiredScratch[i]) ?: continue
            awaitingFill.remove(te.entryFillId)
            ops.update(te.id) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
        }
    }

    // A leg that closed first (take-profit, stop, CLOSE) or vanished at the venue needs no timer.
    private fun dropClosedLegs(): Boolean {
        var changed = false
        expiredScratch.clear()
        for (exit in armed.values) {
            val qty = closer.openQuantity(exit)
            if (qty == null || qty.signum() <= 0) {
                expiredScratch.add(exit.id)
            } else {
                closer.withTicket(exit)?.let { armed[exit.id] = it }?.also { changed = true }
            }
        }
        for (i in expiredScratch.indices) {
            val exit = armed.remove(expiredScratch[i]) ?: continue
            log?.info("timed exit {} dropped: leg {} is no longer open", exit.id, exit.legId ?: exit.symbol)
            changed = true
        }
        return changed
    }
}
