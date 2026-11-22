package com.qkt.dsl.sets

import com.qkt.instrument.InstrumentMeta

/**
 * One `DYNAMIC` symbol set a [DynamicSetHost] runs: its member [template], its point-in-time [membership] and the
 * [venue] its bare member symbols belong to.
 */
internal class HostedSet(
    val template: SetMemberTemplate,
    val membership: DynamicSetMembership,
    val venue: String,
) {
    private var laddered: Pair<List<InstrumentMeta>, StrikeLadder>? = null

    /**
     * The strike ladders of the rows the set ranks now. A live session replaces those rows as it subscribes more
     * contracts, so the ladder is rebuilt when they change: a contract listed after the start is a neighbour too.
     */
    fun ladder(): StrikeLadder {
        val rows = membership.rows
        laddered?.takeIf { it.first === rows }?.let { return it.second }
        return StrikeLadder(rows).also { laddered = rows to it }
    }
}
