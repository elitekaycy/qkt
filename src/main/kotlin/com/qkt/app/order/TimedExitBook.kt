package com.qkt.app.order

import com.qkt.persistence.PersistedTimeExit

/**
 * Armed fill-anchored timed exits, keyed by exit id: each one's leg and absolute deadline. Lives
 * in the order store so snapshots persist it and restart restores it; [TimeExits] drives it.
 * [pending] holds the exits whose entry has not filled yet, keyed by entry id ([PendingTimeExits]).
 */
internal class TimedExitBook {
    private val armed: MutableMap<String, PersistedTimeExit> = mutableMapOf()

    /** Exits waiting for their entry's fill, keyed by entry order id; persisted with the armed ones (#1660). */
    val pending: MutableMap<String, PersistedTimeExit> = mutableMapOf()

    val values: Collection<PersistedTimeExit> get() = armed.values

    fun isEmpty(): Boolean = armed.isEmpty()

    operator fun get(id: String): PersistedTimeExit? = armed[id]

    operator fun set(
        id: String,
        exit: PersistedTimeExit,
    ) {
        armed[id] = exit
    }

    fun remove(id: String): PersistedTimeExit? = armed.remove(id)

    /** Armed and pending exits grouped by strategy, for the persistence snapshot. */
    fun byStrategy(): Map<String, List<PersistedTimeExit>> =
        (armed.values + pending.values).groupBy { it.strategyId }.mapValues { (_, v) -> v.sortedBy { it.id } }
}
