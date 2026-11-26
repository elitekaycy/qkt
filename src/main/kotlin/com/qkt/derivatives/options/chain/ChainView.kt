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
 * instant, from the root's declared series, searching that UTC day and the one before.
 *
 * Readers ask at instants that only move forward (a live clock, a replay's clock), so a day keeps only
 * the snapshots a later question can still need: the latest at or before the newest instant asked and
 * every later one. A live view therefore holds about one snapshot per root however long the daemon
 * runs (#1782). A day file that changed since it was read (a live recorder appends to today's) is
 * streamed again, and so is one asked about an instant before what it kept.
 */
class ChainView(
    private val instruments: InstrumentRegistry,
) {
    private class Day(
        val stamp: List<Any?>,
        var snapshots: List<ChainSnapshot>,
        var askedMs: Long,
    )

    private val days = HashMap<Pair<String, LocalDate>, Day>()

    /** [root]'s latest snapshot taken at or before [atMs], or null (no such root, series or snapshot). */
    fun latest(
        root: String,
        atMs: Long,
    ): ChainSnapshot? {
        val day = Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).toLocalDate()
        return latestOf(root, day, atMs) ?: latestOf(root, day.minusDays(1), Long.MAX_VALUE)
    }

    /** How many snapshots the view holds across its roots and days. */
    internal fun heldSnapshots(): Int = days.values.sumOf { it.snapshots.size }

    private fun latestOf(
        root: String,
        day: LocalDate,
        atMs: Long,
    ): ChainSnapshot? {
        val options = instruments.options() ?: return null
        val series = options.root(root)?.chains ?: return null
        val dataRoot = options.dataRoot ?: return null
        days.keys.removeIf { (r, d) -> r == root && d != day && d != day.plusDays(1) && d != day.minusDays(1) }
        val file = ChainSnapshotStore(dataRoot, series).path(root, day)
        val stamp = stamp(file)
        val held =
            days[root to day]?.takeIf { it.stamp == stamp && atMs >= it.askedMs }
                ?: Day(stamp, ChainDayFile.tail(file, root, series, atMs), atMs).also { days[root to day] = it }
        val at = held.snapshots.indexOfLast { it.atMs <= atMs }
        if (at < 0) return null
        if (at > 0) held.snapshots = held.snapshots.drop(at)
        held.askedMs = maxOf(held.askedMs, atMs)
        return held.snapshots.first()
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
