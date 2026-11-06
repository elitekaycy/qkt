package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.entryFillId
import com.qkt.execution.exitLegIntent
import com.qkt.execution.isTerminal
import com.qkt.persistence.PersistedTimeExit
import org.slf4j.Logger

/**
 * Fill-anchored time exits (`EXIT AFTER`) whose entry order has not filled yet. They live in
 * [TimedExitBook.pending], so they are persisted with the armed exits and a restart taken while the
 * entry works re-arms them on its fill (#1660). The hold runs from the venue's fill time.
 */
internal class PendingTimeExits(
    private val book: OrderBook,
    private val clock: Clock,
    private val ops: OrderOps,
    private val exits: TimedExitBook,
    private val log: Logger?,
) {
    private val pending get() = exits.pending

    // Reused every tick; cleared and refilled, so steady-state allocation is zero.
    private val ended = ArrayList<String>()

    fun isEmpty(): Boolean = pending.isEmpty()

    /** True when a pending exit waits on [id]: its entry, or the bracket and protective orders it holds. */
    fun targets(id: String): Boolean = pending.values.any { it.entryId == id || id in it.protectiveIds }

    /** Waits for [req]'s entry to fill; [protectiveIds] are the bracket orders cancelled before its close. */
    fun add(
        req: OrderRequest.TimeExit,
        holdMs: Long,
        protectiveIds: List<String>,
    ) {
        pending[req.entryFillId] =
            PersistedTimeExit(
                id = req.id,
                strategyId = req.strategyId,
                symbol = req.symbol,
                side = req.target.side,
                quantity = req.target.quantity,
                legId = (req.target.exitLegIntent() as? LegIntent.Close)?.legId,
                ticket = null,
                deadlineMs = req.deadline.toEpochMilli(),
                protectiveIds = protectiveIds,
                entryId = req.entryFillId,
                holdMs = holdMs,
            )
    }

    /**
     * The exit armed by entry [entryId]'s fill at venue time [filledAtMs], or null when none waits for it.
     * A fill time of 0 (not reported) or ahead of the clock counts from now.
     */
    fun arm(
        entryId: String,
        filledAtMs: Long,
    ): PersistedTimeExit? {
        val exit = pending.remove(entryId) ?: return null
        val hold = exit.holdMs ?: return null
        val now = clock.now()
        val from = if (filledAtMs in 1..now) filledAtMs else now
        if (from + hold <= now) {
            log?.warn(
                "timed exit {}: entry {} filled at {}, heard {}ms later, past its {}ms hold; closing on the next tick",
                exit.id,
                entryId,
                from,
                now - from,
                hold,
            )
        }
        ops.update(exit.id) { it.copy(state = OrderState.FILLED, lastUpdatedAt = now) }
        return exit.copy(deadlineMs = from + hold, entryId = null, holdMs = null)
    }

    /** Re-attaches a pending exit persisted before a restart; one whose entry is not restored is dropped loudly. */
    fun restore(exit: PersistedTimeExit) {
        val entryId = exit.entryId ?: return
        if (book[entryId] == null && exit.protectiveIds.none { book[it] != null }) {
            log?.error(
                "timed exit {} for {} {} NOT re-attached: its entry {} was not restored; " +
                    "if that entry filled, its leg has no time exit",
                exit.id,
                exit.strategyId,
                exit.symbol,
                entryId,
            )
            return
        }
        pending[entryId] = exit
        log?.info("restored timed exit {}: waits for entry {} to fill, then holds {}ms", exit.id, entryId, exit.holdMs)
    }

    /** Drops exits whose entry ended unfilled; true when any was dropped. */
    fun dropUnfilled(): Boolean {
        if (pending.isEmpty()) return false
        ended.clear()
        for (entryId in pending.keys) {
            val entry = book[entryId] ?: continue
            if (entry.state.isTerminal && entry.state != OrderState.FILLED) ended.add(entryId)
        }
        for (entryId in ended) {
            val exit = pending.remove(entryId) ?: continue
            ops.update(exit.id) { it.copy(state = OrderState.CANCELLED, lastUpdatedAt = clock.now()) }
        }
        return ended.isNotEmpty()
    }
}
