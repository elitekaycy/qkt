package com.qkt.events

import com.qkt.instrument.universe.MemberChange
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.MembershipStep

/**
 * One member of strategy [strategyId]'s symbol set [set] changed its place at the evaluation closing the window that
 * ends at [windowEndMs]: [member] is its qkt symbol, [rank] its place in the ranking (null when not ranked). The
 * membership journal: recorded by the engine audit journal as a decision event and never read back, since membership
 * is recomputed from the catalog and as-of data (runtime membership plan §3.1).
 */
data class SetMembershipChanged(
    val strategyId: String,
    val set: String,
    val member: String,
    val change: MembershipChange,
    val rank: Int?,
    val windowEndMs: Long,
    override val timestamp: Long = 0L,
    override val sequenceId: Long = 0L,
) : Event {
    companion object {
        /** The journal events of [step] for [set] of [strategyId] at the close [windowEndMs], in the step's order. */
        fun of(
            strategyId: String,
            set: String,
            windowEndMs: Long,
            step: MembershipStep,
        ): List<SetMembershipChanged> =
            step.changes.map { c: MemberChange ->
                SetMembershipChanged(strategyId, set, c.member, c.change, c.rank, windowEndMs)
            }
    }
}
