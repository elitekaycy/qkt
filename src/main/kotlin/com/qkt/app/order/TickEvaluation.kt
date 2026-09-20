package com.qkt.app.order

import com.qkt.app.StackTracker
import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.isTerminal
import com.qkt.marketdata.Tick
import org.slf4j.Logger

/**
 * The per-tick pass over engine-held orders, run once per tick on the engine thread: advance
 * trailing stops, expire GTD orders the venue cannot expire itself, fire time exits and stack
 * deadlines, fire triggered orders, then reclaim dead orders.
 *
 * Hot path. Work is keyed by the tick's symbol — O(this symbol's live orders), not O(all live
 * orders) — and every list is a reused scratch buffer, so steady-state allocation is zero. The
 * buffers are shareable only because this runs on the single engine thread and is not reentrant
 * (its sole caller is the TickEvent subscription, and TickEvent is feed-sourced).
 */
internal class TickEvaluation(
    private val book: OrderBook,
    private val prices: ObservedPrices,
    private val stops: ManagedStopBook,
    private val stopTicker: ManagedStopTicker,
    private val timeExits: TimeExits,
    private val stacks: StackTracker,
    private val stackExecution: StackExecution,
    private val firing: TriggerFiring,
    private val broker: Broker,
    private val clock: Clock,
    private val ops: OrderOps,
    private val requireArmedTrailTicket: Boolean,
    private val closeTicket: (OrderRequest) -> String?,
    private val log: Logger,
) {
    private val symbolLiveScratch = ArrayList<ManagedOrder>()
    private val triggeredScratch = ArrayList<ManagedOrder>()
    private val gtdExpiredScratch = ArrayList<String>()
    private val expiredStacksScratch = ArrayList<StackTracker.ActiveStack>()

    /** Advances and fires this tick's engine-held orders, then sweeps deadlines and reclaims. */
    fun onTick(tick: Tick) {
        prices.record(tick.symbol, tick.price)
        // Only this symbol's live orders drive trailing + trigger evaluation — O(this symbol),
        // not O(all live). An id in the index with no entry in [orders] is an invariant violation,
        // not an expected absence, so surface it.
        symbolLiveScratch.clear()
        book.liveIdsFor(tick.symbol)?.let { ids ->
            for (id in ids) {
                symbolLiveScratch.add(book[id] ?: error("live order index desync: $id"))
            }
        }
        for (i in symbolLiveScratch.indices) {
            val managed = symbolLiveScratch[i]
            if (managed.state != OrderState.PENDING) continue
            if (isPersistentManagedStop(managed.request) &&
                requireArmedTrailTicket &&
                closeTicket(managed.request) == null
            ) {
                log.warn(
                    "cancelling engine-managed stop {} because its venue position ticket no longer exists",
                    managed.id,
                )
                ops.cancel(managed.id)
                continue
            }
            stopTicker.onTick(managed, tick.price)
        }

        // Phase 38: sweep pending GTD orders past their deadline when the broker doesn't
        // self-cancel. Only runs when the venue can't self-expire — MT5 returns
        // supportsNativeGtd=true and skips it; PaperBroker, Bybit, and LogBroker fall through here.
        // Walks the GTD index (deadline-bearing orders only) and compares longs; the live order is
        // resolved only for the few that actually expired, in the same order a full scan would cancel.
        // One timestamp per pass: GTD, time-exit, and stack deadlines all compare against the same
        // tick instant. The empty guards keep the pass iterator-free when nothing has a deadline.
        val now = clock.now()
        if (!broker.supportsNativeGtd && book.gtdDeadlines.isNotEmpty()) {
            gtdExpiredScratch.clear()
            for ((id, deadline) in book.gtdDeadlines) {
                if (now >= deadline) gtdExpiredScratch.add(id)
            }
            for (i in gtdExpiredScratch.indices) {
                val managed = book[gtdExpiredScratch[i]] ?: continue
                if (managed.state.isTerminal) continue
                if (managed.state != OrderState.PENDING && managed.state != OrderState.WORKING) continue
                ops.cancel(managed.id)
            }
        }

        timeExits.expireDue(now)

        val activeStacks = stacks.activeView()
        if (activeStacks.isNotEmpty()) {
            expiredStacksScratch.clear()
            for (state in activeStacks) {
                val deadline = state.deadlineEpochMs ?: continue
                if (now < deadline) continue
                expiredStacksScratch.add(state)
            }
            for (i in expiredStacksScratch.indices) {
                val state = expiredStacksScratch[i]
                stackExecution.cancelPending(state.id)
                stacks.terminate(state.id)
            }
        }

        triggeredScratch.clear()
        for (i in symbolLiveScratch.indices) {
            val managed = symbolLiveScratch[i]
            if (managed.state == OrderState.PENDING && isTriggered(managed, tick, stops)) {
                triggeredScratch.add(managed)
            }
        }
        for (i in triggeredScratch.indices) {
            firing.fire(triggeredScratch[i], tick.price)
        }

        book.drainGc()
    }
}
