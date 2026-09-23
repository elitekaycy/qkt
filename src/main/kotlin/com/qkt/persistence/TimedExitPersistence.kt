package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * One armed fill-anchored timed exit (`EXIT AFTER`): the leg it closes and the absolute deadline,
 * so a restart can re-arm it instead of leaving the leg with no exit at all.
 *
 * [legId] is the leg the exit closes on a hedging venue (null when the venue nets), [ticket] its
 * venue ticket once known, and [protectiveIds] the bracket orders to cancel before closing.
 */
data class PersistedTimeExit(
    val id: String,
    val strategyId: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val legId: String?,
    val ticket: String?,
    val deadlineMs: Long,
    val protectiveIds: List<String> = emptyList(),
)

/**
 * Storage for armed timed exits. Kept apart from the rest of [StatePersistor] so the defaults
 * leave persistors that predate timed exits compiling.
 */
interface TimedExitPersistence {
    /** Persist [strategyId]'s armed timed exits, replacing what was stored. */
    fun saveTimedExits(
        strategyId: String,
        exits: List<PersistedTimeExit>,
    ) {}

    /** Restore [strategyId]'s armed timed exits; empty when none persisted. */
    fun loadTimedExits(strategyId: String): List<PersistedTimeExit> = emptyList()
}
