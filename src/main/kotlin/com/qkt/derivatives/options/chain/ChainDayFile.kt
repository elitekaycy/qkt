package com.qkt.derivatives.options.chain

import com.qkt.instrument.QuoteSource
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Streaming access to one chain day file that never holds the whole day in memory. A day of a
 * 700-contract root snapshotted every 60 s is about a million rows; a live daemon reads the latest
 * snapshot and appends the next one all day, so both stream the file instead of decoding it.
 */
internal object ChainDayFile {
    /**
     * The snapshots of [file] a reader at [atMs] can still need: the latest taken at or before [atMs]
     * and every later one, oldest first. Earlier rows are skipped unparsed; empty when [file] is missing.
     */
    fun tail(
        file: Path,
        root: String,
        source: QuoteSource,
        atMs: Long,
    ): List<ChainSnapshot> {
        if (!Files.exists(file)) return emptyList()
        var latest = emptyList<String>()
        var group = ArrayList<String>()
        var groupAt = Long.MIN_VALUE
        val later = ArrayList<String>()
        rows(file) { row ->
            val at = row.substringBefore(',').toLong()
            when {
                at > atMs -> later.add(row)
                at == groupAt -> group.add(row)
                else -> {
                    if (group.isNotEmpty()) latest = group
                    group = arrayListOf(row)
                    groupAt = at
                }
            }
        }
        if (group.isNotEmpty()) latest = group
        return guarded(file) {
            val quotes = (latest + later).map(ChainCsv::parse)
            require(quotes.all { it.source == source }) { "holds quotes of a source other than $source" }
            quotes.groupBy { it.atMs }.map { (at, ofAt) -> ChainSnapshot(root, at, ofAt) }
        }
    }

    /** The instant of [file]'s last row, or null when [file] is missing, empty or in the legacy format. */
    fun lastInstant(file: Path): Long? {
        if (!Files.exists(file)) return null
        var last: String? = null
        val header = rows(file) { last = it }
        return last?.takeIf { header == ChainCsv.HEADER }?.substringBefore(',')?.toLong()
    }

    /**
     * Adds [snapshot]'s rows to the end of [file] as one more gzip member, through a synced copy moved
     * into place. The caller holds the root's lock and has checked that [snapshot] is later than every row.
     */
    fun append(
        file: Path,
        snapshot: ChainSnapshot,
    ) {
        val staged = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
        try {
            Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING)
            FileOutputStream(staged.toFile(), true).use { raw ->
                val gzip = GZIPOutputStream(raw)
                val out = gzip.bufferedWriter()
                for (quote in snapshot.quotes.sortedBy { it.contract }) {
                    out.write(ChainCsv.row(quote))
                    out.newLine()
                }
                out.flush()
                gzip.finish()
                raw.channel.force(true)
            }
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    /** Calls [each] with every non-blank data row of [file] and returns its header. */
    private fun rows(
        file: Path,
        each: (String) -> Unit,
    ): String =
        guarded(file) {
            GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { reader: BufferedReader ->
                val header = reader.readLine()
                require(header == ChainCsv.HEADER || header == ChainCsv.LEGACY_HEADER) {
                    "header is not '${ChainCsv.HEADER}'"
                }
                reader.lineSequence().filter { it.isNotBlank() }.forEach(each)
                header
            }
        }

    private fun <T> guarded(
        file: Path,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (e: IOException) {
            throw IllegalStateException("$file: unreadable chain file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$file: invalid chain file: ${e.message}", e)
        }
}
