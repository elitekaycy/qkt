package com.qkt.derivatives.options.chain

import com.qkt.instrument.InstrumentRegistry
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A strategy's look-back view of its option roots' chains: the latest stored snapshot at or before an
 * instant, from the root's declared series, searching that UTC day and the one before. Decoded days
 * are kept per root (the instant's day and its neighbours), so a replay moving forward reads each day
 * file about once; a day file that changed since it was read (a live recorder appends to today's) is
 * read again, parsing only the snapshots added since ([ChainSnapshotStore.readDayAfter]).
 */
class ChainView(
    private val instruments: InstrumentRegistry,
) {
    private class Day(
        val stamp: List<Any?>,
        val snapshots: List<ChainSnapshot>,
    )

    private val days = HashMap<Pair<String, LocalDate>, Day>()

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
        val store = ChainSnapshotStore(dataRoot, series)
        val stamp = stamp(store.path(root, day))
        val held = days[root to day]
        if (held != null && held.stamp == stamp) return held.snapshots
        // A changed file is mostly the same day with later snapshots: re-read only those.
        return Day(stamp, store.readDayAfter(root, day, held?.snapshots.orEmpty()))
            .also { days[root to day] = it }
            .snapshots
    }

    /** What identifies one version of [file]: a replaced file has a new key, an appended one a new time and size. */
    private fun stamp(file: Path): List<Any?> =
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            listOf(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
        } catch (e: NoSuchFileException) {
            emptyList()
        }
}
