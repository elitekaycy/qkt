package com.qkt.derivatives.futures

import com.qkt.instrument.RollHistory
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.RollRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * The roll records a live session must not take: those its roots' histories held when it started that are dated after
 * that start. Such a record was written before its roll happened (an older `qkt fetch --rolls` priced it from the
 * last bars it had) and measures nothing. A record dated at or before the start is kept: an earlier session may have
 * measured it live a moment before a restart, and a lane restored mid-roll needs it.
 * The session serves its streams without them ([trimmed]), so it reaches the roll and prices it live
 * ([LiveRollMeasurer]), which replaces the record; [settle] then forgets it. A record another session appends while
 * this one runs is trusted: it was measured live, at its roll.
 */
class PrematureRolls private constructor(
    private val records: ConcurrentHashMap<String, MutableSet<RollRecord>>,
) {
    /** Whether [record] of [rootId] was dated in the future when the session started. */
    fun isPremature(
        rootId: String,
        record: RollRecord,
    ): Boolean = records[rootId]?.contains(record) == true

    /** [history] without the records still premature. */
    fun trimmed(history: RollHistory): RollHistory {
        val early = records[history.root].orEmpty()
        if (early.isEmpty()) return history
        return history.copy(rolls = history.rolls.filterNot { it in early })
    }

    /** The roll [measured] of [rootId] now has its live record: any premature one of the same roll is gone. */
    fun settle(
        rootId: String,
        measured: RollRecord,
    ) {
        records[rootId]?.removeIf { it.atMs == measured.atMs && it.from == measured.from && it.to == measured.to }
    }

    companion object {
        /** The records of [roots]' histories in [store] dated after [startedMs], read once, at the session's start. */
        fun at(
            store: RollHistoryStore,
            roots: Collection<String>,
            startedMs: Long,
        ): PrematureRolls {
            val found = ConcurrentHashMap<String, MutableSet<RollRecord>>()
            for (root in roots) {
                val early =
                    store
                        .read(root)
                        ?.rolls
                        .orEmpty()
                        .filter { it.atMs > startedMs }
                if (early.isNotEmpty()) found[root] = ConcurrentHashMap.newKeySet<RollRecord>().apply { addAll(early) }
            }
            return PrematureRolls(found)
        }
    }
}
