package com.qkt.marketdata.ingest.record

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.InputLine

/**
 * Collects rows that cannot be read during a pass, so a refusal shows how many rows fail and why (grouped
 * by reason, with example lines) instead of stopping at the first one. Nothing is written when any row
 * fails: a file that is wrong in places is wrong.
 */
class RowFaults {
    private val byReason = linkedMapOf<String, MutableList<InputLine>>()
    private var total = 0

    /** Records that [where] failed for [reason]. */
    fun add(
        where: InputLine,
        reason: String,
    ) {
        total++
        val lines = byReason.getOrPut(reason) { mutableListOf() }
        if (lines.size < ImportRefusal.MAX_LINES) lines += where
    }

    /** True when no row has failed. */
    val isEmpty: Boolean get() = total == 0

    /** Throws an [ImportRefusal] describing every failure, if there were any. */
    fun refuseIfAny() {
        if (total == 0) return
        val summary = byReason.entries.joinToString("; ") { (reason, lines) -> "$reason (${lines.first()})" }
        val examples = byReason.flatMap { (reason, lines) -> lines.take(EXAMPLES).map { "$it  ← $reason" } }
        throw ImportRefusal("$total row(s) could not be read: $summary", examples)
    }

    private companion object {
        const val EXAMPLES = 5
    }
}
