package com.qkt.app.order

import com.qkt.persistence.StatePersistor

/**
 * Writes order state for restart recovery, per strategy, into five slots: pending orders,
 * bracket pairs, OCO legs, trailing stops and armed timed exits.
 *
 * Routine mutation snapshots ([persistAll]) are best-effort so an asynchronous persistence
 * failure does not block event dispatch, and a slot is rewritten only when its content changed —
 * [persistAll] runs on every order state change and walks every strategy that ever traded.
 * Venue-bound intent takes the fail-closed synchronous path, [persistSubmissionIntent].
 */
internal class OrderStateSnapshots(
    private val persistor: StatePersistor,
    private val book: OrderBook,
    private val children: PendingChildBook,
    private val brackets: BracketBook,
    private val scaleOuts: ScaleOutRecovery,
    private val siblings: SiblingLinks,
    private val stops: ManagedStopBook,
    private val timedExits: TimedExitBook,
) {
    private val persistedStrategies = mutableSetOf<String>()

    /** Last snapshot written per (strategy, slot). */
    private val lastPersisted: MutableMap<Pair<String, String>, Any> = mutableMapOf()

    /** Includes [strategyId] in every future snapshot, so its slots are cleared when it goes flat. */
    fun remember(strategyId: String) {
        persistedStrategies.add(strategyId)
    }

    /** Snapshot all active leaf orders and linked engine state per strategy. */
    fun persistAll() {
        runCatching {
            val pendingByStrategy = pendingOrdersByStrategy(book, children, brackets, scaleOuts)
            val pairsByStrategy = bracketPairsByStrategy(book, siblings)
            val ocoLegsByStrategy = ocoLegsByStrategy(book, siblings)
            val trailingStopsByStrategy = trailingStopSnapshot(book.orders, stops)
            val timedExitsByStrategy = timedExits.byStrategy()
            val strategies =
                (
                    persistedStrategies + pendingByStrategy.keys + pairsByStrategy.keys + ocoLegsByStrategy.keys +
                        trailingStopsByStrategy.keys + timedExitsByStrategy.keys
                ).toSet()
            for (sid in strategies) {
                persistIfChanged(sid, PENDING_SLOT, pendingByStrategy[sid] ?: emptyMap(), persistor::savePendingOrders)
                persistIfChanged(sid, PAIRS_SLOT, pairsByStrategy[sid] ?: emptyList(), persistor::saveBracketPairs)
                persistIfChanged(sid, OCO_SLOT, ocoLegsByStrategy[sid] ?: emptyList(), persistor::saveOcoLegs)
                persistIfChanged(
                    sid,
                    TRAILING_SLOT,
                    trailingStopsByStrategy[sid] ?: emptyList(),
                    persistor::saveTrailingStops,
                )
                persistIfChanged(sid, TIMED_SLOT, timedExitsByStrategy[sid] ?: emptyList(), persistor::saveTimedExits)
            }
            stops.dirty = false
        }
    }

    /** Synchronously writes [strategyId]'s pending orders before venue-bound intent leaves the engine. */
    fun persistSubmissionIntent(strategyId: String) {
        if (strategyId.isBlank()) return
        persistedStrategies.add(strategyId)
        val active = recoveryPendingOrders(strategyId, book, children, brackets, scaleOuts)
        lastPersisted[strategyId to PENDING_SLOT] = active
        persistor.savePendingOrdersSync(strategyId, active)
    }

    /** Flushes HWM-only trailing-stop changes at the live heartbeat cadence. */
    fun persistTrailingStateIfDirty() {
        if (!stops.dirty) return
        runCatching {
            for ((strategyId, snapshot) in trailingStopSnapshot(book.orders, stops)) {
                persistor.saveTrailingStops(strategyId, snapshot)
            }
            stops.dirty = false
        }
    }

    private fun <T : Any> persistIfChanged(
        strategyId: String,
        slot: String,
        value: T,
        save: (String, T) -> Unit,
    ) {
        val key = strategyId to slot
        if (lastPersisted[key] == value) return
        lastPersisted[key] = value
        save(strategyId, value)
    }

    private companion object {
        const val PENDING_SLOT = "pending-orders"
        const val PAIRS_SLOT = "bracket-pairs"
        const val OCO_SLOT = "oco-legs"
        const val TRAILING_SLOT = "trailing-stops"
        const val TIMED_SLOT = "timed-exits"
    }
}
