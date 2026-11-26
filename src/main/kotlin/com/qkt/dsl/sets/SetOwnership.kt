package com.qkt.dsl.sets

import com.qkt.persistence.PersistedSequenceSnapshot
import com.qkt.persistence.PersistedSequenceState
import com.qkt.persistence.StatePersistor
import java.math.BigDecimal

/**
 * Which members a moving symbol set had active, persisted so a restart can tell the positions the set itself opened
 * from every other position the strategy holds on the same venue. Without it a restart would hand a plain stream's
 * position to the set (or one set's position to another), whose member rules could then close it.
 *
 * e.g. set `m` saves `[A, B]`; after a restart holding `GW:A` and `GW:FOO`, only `A` is restored into `m`.
 *
 * Each member's expiry is saved with it when the venue's listing gave one, so a restart can prove a held contract
 * expired even after the venue stopped listing it.
 *
 * Stored beside the strategy's other state under the id `<strategy>.<set>`.
 */
internal object SetOwnership {
    private const val KEY = "members"

    /**
     * Records [members] (bare symbols, in the set's order) as what [set] of [strategyId] has active now, with the
     * expiry (UTC ms) of each member that has one in [expiries].
     */
    fun save(
        persistor: StatePersistor,
        strategyId: String,
        set: String,
        members: Collection<String>,
        expiries: Map<String, Long> = emptyMap(),
    ) {
        val known =
            members.mapNotNull { m -> expiries[m]?.let { PersistedSequenceSnapshot(m, BigDecimal.ZERO, it) } }
        val state = PersistedSequenceState(KEY, 0, known, members.associateWith { true })
        persistor.saveSequences("$strategyId.$set", mapOf(KEY to state))
    }

    /** The members last saved for [set] of [strategyId], in the saved order; empty when none were. */
    fun load(
        persistor: StatePersistor,
        strategyId: String,
        set: String,
    ): List<String> =
        persistor
            .loadSequences("$strategyId.$set")[KEY]
            ?.lastValues
            ?.keys
            ?.toList()
            .orEmpty()

    /** The expiry (UTC ms) saved with each member of [set] of [strategyId] that had one. */
    fun expiries(
        persistor: StatePersistor,
        strategyId: String,
        set: String,
    ): Map<String, Long> =
        persistor
            .loadSequences("$strategyId.$set")[KEY]
            ?.snapshots
            .orEmpty()
            .associate { it.stage to it.timeMs }
}
