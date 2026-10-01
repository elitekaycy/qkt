package com.qkt.derivatives.options.chain

import com.qkt.instrument.QktSymbols
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Option chain snapshots on disk, one gzip CSV per UTC day under
 * `<dataRoot>/chains/<VENUE>/<ROOT>/<YYYY-MM-DD>.csv.gz` (`chains/DERIBIT/BTC_USDC/2026-10-01.csv.gz`).
 * Rows are sorted by instant then contract; decimals keep their exact digits; an absent value is an
 * empty cell. Writing a day replaces its file through a temporary file moved into place.
 */
class ChainSnapshotStore(
    private val dataRoot: Path,
) {
    /** Where [root]'s snapshots of [day] live. */
    fun path(
        root: String,
        day: LocalDate,
    ): Path {
        QktSymbols.requireFileSafe(root)
        return dataRoot
            .resolve(
                "chains",
            ).resolve(root.substringBefore(':'))
            .resolve(root.substringAfter(':'))
            .resolve("$day.csv.gz")
    }

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
        for ((day, ofDay) in snapshots.groupBy { dayOf(it.atMs) }) {
            val file = path(root, day)
            Files.createDirectories(file.parent)
            val staged = file.resolveSibling("${file.fileName}.tmp")
            GZIPOutputStream(Files.newOutputStream(staged)).bufferedWriter().use { out ->
                out.write(HEADER)
                out.newLine()
                for (quote in ofDay.flatMap { it.quotes }.sortedWith(compareBy({ it.atMs }, { it.contract }))) {
                    out.write(row(quote))
                    out.newLine()
                }
            }
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** [root]'s snapshots of [day] in time order; empty when none were written. */
    fun readDay(
        root: String,
        day: LocalDate,
    ): List<ChainSnapshot> {
        val file = path(root, day)
        if (!Files.exists(file)) return emptyList()
        val quotes =
            try {
                GZIPInputStream(Files.newInputStream(file)).bufferedReader().useLines { lines ->
                    lines
                        .drop(1)
                        .filter { it.isNotBlank() }
                        .map(::parse)
                        .toList()
                }
            } catch (e: IOException) {
                throw IllegalStateException("$file: unreadable chain file: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("$file: invalid chain file: ${e.message}", e)
            }
        return quotes.groupBy { it.atMs }.map { (at, rows) -> ChainSnapshot(root, at, rows) }
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

    private fun row(q: ChainQuote): String =
        listOf(
            q.atMs.toString(),
            q.contract,
            plain(q.bid),
            plain(q.ask),
            plain(q.mark),
            plain(q.markIv),
            plain(q.underlying),
            plain(q.rate),
            q.markAgeMs.toString(),
            q.source.name,
        ).joinToString(",")

    private fun parse(line: String): ChainQuote {
        val cells = line.split(',')
        require(cells.size == COLUMNS) { "expected $COLUMNS columns, got ${cells.size}: $line" }

        fun decimal(i: Int): BigDecimal? = cells[i].takeIf { it.isNotEmpty() }?.let(::BigDecimal)
        return ChainQuote(
            atMs = cells[0].toLong(),
            contract = cells[1],
            bid = decimal(2),
            ask = decimal(3),
            mark = requireNotNull(decimal(4)) { "mark missing: $line" },
            markIv = decimal(5),
            underlying = requireNotNull(decimal(6)) { "underlying missing: $line" },
            rate = decimal(7),
            markAgeMs = cells[8].toLong(),
            source = QuoteSource.valueOf(cells[9]),
        )
    }

    private fun plain(value: BigDecimal?): String = value?.toPlainString() ?: ""

    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    private companion object {
        const val HEADER = "atMs,contract,bid,ask,mark,markIv,underlying,rate,markAgeMs,source"
        const val COLUMNS = 10
    }
}
