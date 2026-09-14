package com.qkt.common

interface IdGenerator {
    fun next(): String
}

class SequentialIdGenerator(
    private val prefix: String = "ORD",
) : IdGenerator {
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

class MonotonicSequenceGenerator : SequenceGenerator {
    private var counter = 0L

    override fun next(): Long = counter++
}
