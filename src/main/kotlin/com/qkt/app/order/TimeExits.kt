package com.qkt.app.order

import com.qkt.broker.SubmitAck
import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.persistence.PersistedTimeExit
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * [OrderRequest.TimeExit] wrappers: a target order with a deadline. At the deadline an unfilled
 * target is cancelled; a filled one is either left alone (CANCEL) or closed at market
 * (CLOSE_AT_MARKET). A fill-anchored exit ([OrderRequest.TimeExit.holdMs]) waits in [PendingTimeExits]
 * until its entry fills, then is armed in [armed], timed from the venue's fill time; both are persisted
 * and re-armed by [restore] after a restart. Deadlines are compared against the tick clock on the
 * engine thread, so exits fire on the first tick at or after the deadline in backtest and live. A fired
 * exit stays armed until its close fills ([TimedCloseRetries]).
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
    closeGuard: LegCloseGuard? = null,
) {
    private val closer = TimedLegCloser(book, clock, ops, closeTicketFor, openLegQuantity, strategyNetQty, closeGuard)

    /** Resends and alerts for fired exits; told of each venue refusal ([TimedCloseRetries.refused]). */
    val retries = TimedCloseRetries(book, closer, ops, log, armed)
    private val byId: MutableMap<String, OrderRequest.TimeExit> = mutableMapOf()
    private val deadlines: MutableMap<String, Long> = mutableMapOf()
    private val pending = PendingTimeExits(book, clock, ops, armed, log)

    // Set by a fill or a restore; the next tick re-checks that every armed leg is still open.
    private var legCheckDue = false

    // Reused every tick; cleared and refilled, so steady-state allocation is zero.
    private val expiredScratch = ArrayList<String>()

    /** True while any time exit is waiting for its fill or its deadline. */
    fun isNotEmpty(): Boolean = byId.isNotEmpty() || !pending.isEmpty() || !armed.isEmpty()

    /** True when a waiting time exit targets [id] or its entry, or [id] is an armed exit's last close: keep it. */
    fun targets(id: String): Boolean =
        byId.values.any { it.target.id == id } || pending.targets(id) || armed.values.any { it.closeId == id }

    /** Starts [req]: tracks its target and dispatches it; the deadline is watched per tick. */
    fun submit(req: OrderRequest.TimeExit): SubmitAck {
        val now = clock.now()
        // Registered first, so the snapshot taken when the target is tracked already holds it (#1660).
        req.holdMs?.let { pending.add(req, it, closer.protectiveIdsFor(req.target)) }
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
        if (req.holdMs == null) {
            byId[req.id] = req
            deadlines[req.id] = req.deadline.toEpochMilli()
        }
        exposure.register(exposureEntryRequest(req.target))
        ops.dispatch(req.target)
        return SubmitAck(req.id, req.id, accepted = true)
    }

    /** Arms the fill-anchored exit whose entry is [clientOrderId], timed from the venue fill time [filledAtMs]. */
    fun onFilled(
        clientOrderId: String,
        filledAtMs: Long = 0L,
    ) {
        if (!armed.isEmpty()) legCheckDue = true
        if (pending.isEmpty()) return
        val exit = pending.arm(clientOrderId, filledAtMs) ?: return
        armed[exit.id] = exit
        legCheckDue = true
        ops.persistAll()
    }

    /** Re-arms exits persisted before a restart; overdue ones close on the first tick. */
    fun restore(exits: List<PersistedTimeExit>) {
        if (exits.isEmpty()) return
        val now = clock.now()
        for (exit in exits) {
            if (exit.isPending) {
                pending.restore(exit)
                continue
            }
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
        if (byId.isEmpty() && pending.isEmpty() && armed.isEmpty()) return
        expireLegacy(now)
        var changed = pending.dropUnfilled()
        if (legCheckDue) {
            legCheckDue = false
            if (dropClosedLegs()) changed = true
        }
        if (armed.isEmpty()) {
            if (changed) ops.persistAll()
            return
        }
        expiredScratch.clear()
        for (exit in armed.values) if (now >= exit.deadlineMs) expiredScratch.add(exit.id)
        for (i in expiredScratch.indices) {
            val exit = armed.remove(expiredScratch[i]) ?: continue
            retries.fire(exit, now)?.let { armed[exit.id] = it }
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
