package com.qkt.positions

import com.qkt.events.FillAccountedEvent
import java.math.BigDecimal

/**
 * What was held of each key over time, from each change's time and the quantity before and after it, to tell what
 * was held just before a moment a venue names (a funding record's time): a change at or after that moment is not
 * held then. A change older than [keepMs] before the one recorded last is dropped; the oldest kept change's
 * quantity before it is what was held before any kept change, so a moment within [keepMs] of the last change is
 * still told right. A key no change is kept for has held what it holds now. Thread-safe.
 */
class HoldingTimeline<K>(
    private val keepMs: Long,
) {
    private class Change(
        val atMs: Long,
        val before: BigDecimal,
        val after: BigDecimal,
    )

    private val changes = HashMap<K, ArrayDeque<Change>>()

    /** [key]'s holding went from [before] to [after] at [atMs]. */
    @Synchronized
    fun record(
        key: K,
        atMs: Long,
        before: BigDecimal,
        after: BigDecimal,
    ) {
        if (before.compareTo(after) == 0) return
        val kept = changes.getOrPut(key) { ArrayDeque() }
        kept.addLast(Change(atMs, before, after))
        while (kept.first().atMs < atMs - keepMs) kept.removeFirst()
    }

    /** What [key] held just before [atMs]; [now] what it holds now, when no change of it is kept. */
    @Synchronized
    fun heldAt(
        key: K,
        atMs: Long,
        now: () -> BigDecimal,
    ): BigDecimal {
        val kept = changes[key] ?: return now()
        return kept.lastOrNull { it.atMs < atMs }?.after ?: kept.first().before
    }

    companion object {
        /** When [e]'s fill moved a holding: its execution time, or its bus stamp when it carries none. */
        fun movedAt(e: FillAccountedEvent): Long = e.executedAt.takeIf { it > 0 } ?: e.timestamp
    }
}
