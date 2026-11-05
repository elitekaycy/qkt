package com.qkt.broker.continuous

/** What waits for a later tick: a roll's next attempt or its next look at the quotes. Driven by ticks, so deterministic. */
internal class RollLegRetries {
    private val waiting = ArrayList<Pair<Long, () -> Unit>>()

    /** Runs [action] on the first [fire] at or after [atMs]. */
    fun after(
        atMs: Long,
        action: () -> Unit,
    ) {
        waiting += atMs to action
    }

    /** Runs what is due at [nowMs]; O(1) when nothing waits. */
    fun fire(nowMs: Long) {
        if (waiting.isEmpty()) return
        val due = waiting.filter { it.first <= nowMs }
        waiting -= due.toSet()
        due.forEach { it.second() }
    }
}
