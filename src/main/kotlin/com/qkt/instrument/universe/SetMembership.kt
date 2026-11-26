package com.qkt.instrument.universe

/** How one member's place in a symbol set changed at an evaluation (runtime membership plan §3). */
enum class MembershipChange {
    /** Entered the ranking: an instance is created and warms up. */
    JOIN,

    /** Left the ranking flat with nothing working: its instance is released. */
    LEAVE,

    /** Left the ranking while it holds: its instance stays, and only reduces or closes. */
    STICK,

    /** A sticky member that is now flat: its instance is released. */
    RELEASE,

    /** A sticky member back in the ranking: a normal member again, without a new warmup. */
    STAY,

    /** Active when a run (re)starts: membership is recomputed, never read back. */
    RESTORE,

    /** A live candidate whose market data could not be subscribed: it never joins until it is. */
    UNAVAILABLE,
}

/** One member's [change]; [rank] is its 1-based place in the ranking, null when it is not ranked. */
data class MemberChange(
    val member: String,
    val change: MembershipChange,
    val rank: Int?,
)

/** The members active after one evaluation, ranked members first then sticky ones, and what changed. */
data class MembershipStep(
    val active: List<String>,
    val changes: List<MemberChange>,
)

/**
 * The runtime membership of one symbol set, stepped once per close of its `EVERY` window with the ranked members at
 * that close (the resolver's top n) and the members that hold a position or a working order. Members that left the
 * ranking while they hold stay active (sticky while held) until flat. Pure: equal inputs give equal steps, and the
 * only state is the previous active set, so a backtest and a live run step alike and a restart rebuilds it with
 * [restore].
 *
 * Changes are ordered so caps see freed slots first: leaves, sticks and releases by symbol, then joins and stays by
 * rank.
 */
class SetMembership {
    private var ranked: Set<String> = emptySet()
    private var sticky: Set<String> = emptySet()

    /** The members active now, ranked first. */
    val active: List<String> get() = ranked.toList() + sticky.sorted()

    /** The step from the previous evaluation to [members] (ranked, best first) with [held] holding. */
    fun step(
        members: List<String>,
        held: Set<String>,
    ): MembershipStep {
        val now = members.distinct()
        val nowSet = now.toSet()
        val gone = mutableListOf<MemberChange>()
        val nextSticky = sortedSetOf<String>()
        for (member in (ranked + sticky).filter { it !in nowSet }.sorted()) {
            val wasSticky = member in sticky
            when {
                member in held -> {
                    nextSticky += member
                    if (!wasSticky) gone += MemberChange(member, MembershipChange.STICK, null)
                }
                wasSticky -> gone += MemberChange(member, MembershipChange.RELEASE, null)
                else -> gone += MemberChange(member, MembershipChange.LEAVE, null)
            }
        }
        val came =
            now.mapIndexedNotNull { i, member ->
                when (member) {
                    in sticky -> MemberChange(member, MembershipChange.STAY, i + 1)
                    in ranked -> null
                    else -> MemberChange(member, MembershipChange.JOIN, i + 1)
                }
            }
        ranked = now.toCollection(LinkedHashSet())
        sticky = nextSticky
        return MembershipStep(active, gone + came)
    }

    /** The first step after a (re)start: [members] and every [held] member outside them, each reported as restored. */
    fun restore(
        members: List<String>,
        held: Set<String>,
    ): MembershipStep {
        val now = members.distinct()
        ranked = now.toCollection(LinkedHashSet())
        sticky = held.filter { it !in ranked }.toSortedSet()
        val changes =
            now.mapIndexed { i, member -> MemberChange(member, MembershipChange.RESTORE, i + 1) } +
                sticky.map { MemberChange(it, MembershipChange.RESTORE, null) }
        return MembershipStep(active, changes)
    }
}
