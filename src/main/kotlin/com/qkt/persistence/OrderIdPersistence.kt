package com.qkt.persistence

/**
 * Storage for the last order-id sequence each of a strategy's id generators issued, keyed by the generator's
 * prefix (`dsl-<strategy>-`, `ORD-<strategies>`), so a restart on the same state continues every sequence and
 * never sends an id it already sent, flat or not (#1338). The defaults keep persistors that predate it
 * compiling; a persistor that keeps nothing restarts each sequence where its restored orders leave it.
 */
interface OrderIdPersistence {
    /** Persist [marks] for [strategyId], replacing the last save. */
    fun saveOrderIdMarks(
        strategyId: String,
        marks: Map<String, Long>,
    ) {}

    /** The marks last saved for [strategyId]; empty when none were. */
    fun loadOrderIdMarks(strategyId: String): Map<String, Long> = emptyMap()
}
