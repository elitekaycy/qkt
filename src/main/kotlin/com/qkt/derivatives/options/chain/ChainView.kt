package com.qkt.derivatives.options.chain

import com.qkt.instrument.InstrumentRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A strategy's look-back view of its option roots' chains: the latest stored snapshot at or before an
 * instant, from the root's declared series, searching that UTC day and the one before. Decoded days
 * are kept per root (the instant's day and the previous one), so a replay moving forward reads each
 * day file about once.
 */
class ChainView(
    private val instruments: InstrumentRegistry,
) {
    private val days = HashMap<Pair<String, LocalDate>, List<ChainSnapshot>>()

    /** [root]'s latest snapshot taken at or before [atMs], or null (no such root, series or snapshot). */
    fun latest(
        root: String,
        atMs: Long,
    ): ChainSnapshot? {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()
        return snapshots(root, day)?.lastOrNull { it.atMs <= atMs } ?: snapshots(root, day.minusDays(1))?.lastOrNull()
    }

    private fun snapshots(
        root: String,
        day: LocalDate,
    ): List<ChainSnapshot>? {
        val options = instruments.options() ?: return null
        val series = options.root(root)?.chains ?: return null
        val dataRoot = options.dataRoot ?: return null
        days.keys.removeIf { (r, d) -> r == root && d != day && d != day.plusDays(1) && d != day.minusDays(1) }
        return days.getOrPut(root to day) { ChainSnapshotStore(dataRoot, series).readDay(root, day) }
    }
}
