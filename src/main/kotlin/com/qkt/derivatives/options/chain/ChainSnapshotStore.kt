package com.qkt.derivatives.options.chain

import com.qkt.instrument.QktSymbols
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Option chain snapshots of one [source] on disk, one gzip CSV per UTC day under
 * `<dataRoot>/chains/<VENUE>/<ROOT>/<source>/<YYYY-MM-DD>.csv.gz`
 * (`chains/DERIBIT/BTC_USDC/book/2026-10-01.csv.gz`). Trade-built and book snapshots are separate
 * series, so neither ever stands in for, or blocks, the other. Rows are sorted by instant then
 * contract in the [ChainCsv] format. A day is replaced through a synced temporary file moved into
 * place; appends to a root are serialized by a lock file.
 */
class ChainSnapshotStore(
    private val dataRoot: Path,
    private val source: QuoteSource,
) {
    /** Where [root]'s snapshots of [day] live. */
    fun path(
        root: String,
        day: LocalDate,
    ): Path = directory(root).resolve("$day.csv.gz")

    /** Whether [root]'s snapshots of [day] have been written. */
    fun hasDay(
        root: String,
        day: LocalDate,
    ): Boolean = Files.exists(path(root, day))

    /** Writes [snapshots] of [root], replacing the file of every UTC day they touch. */
    fun write(
        root: String,
        snapshots: List<ChainSnapshot>,
    ) {
        val foreign = snapshots.flatMap { it.quotes }.firstOrNull { it.source != source }
        require(
            foreign == null,
        ) { "a $source chain store cannot hold ${foreign?.source} quotes (${foreign?.contract})" }
        for ((day, ofDay) in snapshots.groupBy { dayOf(it.atMs) }) {
            val file = path(root, day)
            Files.createDirectories(file.parent)
            val staged = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
            try {
                FileOutputStream(staged.toFile()).use { raw ->
                    val gzip = GZIPOutputStream(raw)
                    val out = gzip.bufferedWriter()
                    out.write(ChainCsv.HEADER)
                    out.newLine()
                    for (quote in ofDay.flatMap { it.quotes }.sortedWith(compareBy({ it.atMs }, { it.contract }))) {
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
    }

    /**
     * Adds [snapshot] to its day, keeping the day's other snapshots and replacing one taken at the
     * same instant. Concurrent appends to one root wait for each other instead of losing a snapshot.
     */
    fun append(
        root: String,
        snapshot: ChainSnapshot,
    ) {
        val lockFile = directory(root).resolve(".lock")
        Files.createDirectories(lockFile.parent)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val day = dayOf(snapshot.atMs)
                write(root, readDay(root, day).filter { it.atMs != snapshot.atMs } + snapshot)
            }
        }
    }

    /** [root]'s snapshots of [day] in time order; empty when none were written. */
    fun readDay(
        root: String,
        day: LocalDate,
    ): List<ChainSnapshot> {
        val file = path(root, day)
        if (!Files.exists(file)) return emptyList()
        return try {
            val lines = GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { it.readLines() }
            require(lines.firstOrNull() == ChainCsv.HEADER) { "header is not '${ChainCsv.HEADER}'" }
            val quotes = lines.drop(1).filter { it.isNotBlank() }.map(ChainCsv::parse)
            require(quotes.all { it.source == source }) { "holds quotes of a source other than $source" }
            quotes.groupBy { it.atMs }.map { (at, rows) -> ChainSnapshot(root, at, rows) }
        } catch (e: IOException) {
            throw IllegalStateException("$file: unreadable chain file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$file: invalid chain file: ${e.message}", e)
        }
    }

    /**
     * [root]'s latest snapshot taken at or before [atMs], searching that UTC day and the one before;
     * null when neither holds one. Never a snapshot after [atMs].
     */
    fun latestAtOrBefore(
        root: String,
        atMs: Long,
    ): ChainSnapshot? {
        val day = dayOf(atMs)
        return readDay(root, day).lastOrNull { it.atMs <= atMs } ?: readDay(root, day.minusDays(1)).lastOrNull()
    }

    private fun directory(root: String): Path {
        QktSymbols.requireFileSafe(root)
        return dataRoot
            .resolve("chains")
            .resolve(root.substringBefore(':'))
            .resolve(root.substringAfter(':'))
            .resolve(source.name.lowercase())
    }

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
}
