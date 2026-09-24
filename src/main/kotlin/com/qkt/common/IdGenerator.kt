package com.qkt.common

interface IdGenerator {
    fun next(): String
}

class SequentialIdGenerator(
    private val prefix: String = "ORD",
) : IdGenerator {
    companion object {
        /**
         * Ids for the plain BUY/SELL orders of a session running [strategyIds]. The id becomes
         * the venue order comment, and the unknown-outcome resolver attributes fills by it, so
         * sessions under one broker magic must not all mint `ORD-0` (#1155). Backtest and live
         * share this scheme so parity compares like ids.
         */
        fun forSession(strategyIds: Collection<String>): SequentialIdGenerator =
            SequentialIdGenerator(prefix = "ORD-" + strategyIds.joinToString("+"))
    }

    private var counter = 0L

    override fun next(): String = "$prefix-${counter++}"

    /** Never hand out [last] or anything below it again; a no-op when already past it. */
    fun resumeAfter(last: Long) {
        if (last >= counter) counter = last + 1
    }

    /**
     * Resume past every id in [usedIds] minted by this generator. A session that restarts with
     * live state restores orders and legs whose ids this generator issued before; starting at
     * zero again would collide with a restored non-terminal order, which then swallows the new
     * submit as a duplicate.
     */
    fun resumePast(usedIds: Collection<String>) {
        usedIds.mapNotNull { sequenceOf(it) }.maxOrNull()?.let(::resumeAfter)
    }

    /** The counter that produced [id], or null when [id] is not one of this generator's. */
    fun sequenceOf(id: String): Long? {
        if (!id.startsWith("$prefix-")) return null
        return id.substring(prefix.length + 1).toLongOrNull()
    }
}

interface SequenceGenerator {
    fun next(): Long
}

/**
 * The bus event sequence: 0, 1, 2, … in publish order. A session restarted over the same
 * state resumes after the last sequence it already issued, so sequence ids stay unique across
 * the restart for the audit journal and for keys derived from them (fill slices, operation ids).
 */
class MonotonicSequenceGenerator : SequenceGenerator {
    private var counter = 0L

    override fun next(): Long = counter++

    /** Never hand out [last] or anything below it again; a no-op when already past it. */
    fun resumeAfter(last: Long) {
        if (last >= counter) counter = last + 1
    }

    companion object {
        /** A generator whose first id follows [last], or that starts at zero when [last] is null. */
        fun resumingAfter(last: Long?): MonotonicSequenceGenerator =
            MonotonicSequenceGenerator().apply { last?.let(::resumeAfter) }
    }
}
