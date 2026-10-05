package com.qkt.marketdata.depth

import com.qkt.instrument.QktSymbols
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
 * Contracts' order-book snapshots, one gzipped CSV per contract and UTC day under
 * `<dataRoot>/depth/<VENUE>/<NAME>/<yyyy-MM-dd>.csv.gz` (`time,bids,asks`, oldest first, `time` the venue's
 * stamp; each side its levels best first as `price:amount`, space separated), the same file whatever the venue.
 * Prices and amounts are kept as their exact text. A day is read only when a range reaches it.
 */
class BookDepthStore(
    private val dataRoot: Path,
) : BookDepthSource {
    /** The directory holding [qktSymbol]'s days. */
    fun directory(qktSymbol: String): Path {
        QktSymbols.requireFileSafe(qktSymbol)
        return dataRoot
            .resolve(
                DIRECTORY,
            ).resolve(qktSymbol.substringBefore(':'))
            .resolve(qktSymbol.substringAfter(':'))
    }

    /** [qktSymbol]'s stored snapshots from [fromMs] to [toMs], oldest first, read a day at a time. */
    override fun sequence(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<BookDepth> =
        days(qktSymbol, fromMs, toMs).asSequence().flatMap { file ->
            lines(file).asSequence().mapIndexedNotNull { i, line ->
                val time = line.substringBefore(',').toLongOrNull()
                require(time != null) { "$file line ${i + 2}: expected time,bids,asks: $line" }
                if (time in fromMs..toMs) parse(file, i, line, time) else null
            }
        }

    override fun snapshots(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<BookDepth> = sequence(qktSymbol, fromMs, toMs).toList()

    /** The times of [qktSymbol]'s stored snapshots from [fromMs] to [toMs], oldest first, levels left unread. */
    fun times(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<Long> =
        days(qktSymbol, fromMs, toMs).flatMap { file ->
            lines(file).map { it.substringBefore(',').toLong() }.filter { it in fromMs..toMs }
        }

    /** Adds [snapshots] to [qktSymbol]'s days, one at a time already stored replaced; returns how many those days now hold. */
    fun merge(
        qktSymbol: String,
        snapshots: List<BookDepth>,
    ): Int =
        snapshots.groupBy { day(it.timeMs) }.entries.sumOf { (day, fresh) ->
            val file = directory(qktSymbol).resolve("$day.csv.gz")
            val held =
                if (Files.exists(
                        file,
                    )
                ) {
                    lines(file).associateBy { it.substringBefore(',').toLong() }
                } else {
                    emptyMap()
                }
            val all = (held + fresh.associate { it.timeMs to line(it) }).toSortedMap()
            Files.createDirectories(file.parent)
            val staged = file.resolveSibling("${file.fileName}.tmp")
            GZIPOutputStream(Files.newOutputStream(staged)).bufferedWriter().use { out ->
                out.write(HEADER)
                all.values.forEach { out.write(it + "\n") }
            }
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            all.size
        }

    private fun days(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<Path> {
        val dir = directory(qktSymbol)
        if (!Files.isDirectory(dir) || fromMs > toMs) return emptyList()
        val range = day(fromMs)..day(toMs)
        return Files.list(dir).use { files ->
            files
                .filter { DAY_FILE.matches(it.fileName.toString()) }
                .filter { LocalDate.parse(it.fileName.toString().removeSuffix(".csv.gz")) in range }
                .sorted()
                .toList()
        }
    }

    private fun lines(file: Path): List<String> =
        GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { it.readLines() }.drop(1).filter {
            it.isNotBlank()
        }

    private fun parse(
        file: Path,
        index: Int,
        line: String,
        time: Long,
    ): BookDepth {
        val cells = line.split(',')
        return runCatching {
            require(cells.size == 3)
            BookDepth(time, levels(cells[1]), levels(cells[2]))
        }.getOrElse { throw IllegalArgumentException("$file line ${index + 2}: expected time,bids,asks: $line", it) }
    }

    private companion object {
        const val DIRECTORY = "depth"
        const val HEADER = "time,bids,asks\n"
        val DAY_FILE = Regex("""\d{4}-\d{2}-\d{2}\.csv\.gz""")

        fun day(ms: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)

        fun line(d: BookDepth) = "${d.timeMs},${side(d.bids)},${side(d.asks)}"

        fun side(levels: List<BookLevel>) =
            levels.joinToString(" ") {
                "${it.price.toPlainString()}:${it.amount.toPlainString()}"
            }

        fun levels(cell: String): List<BookLevel> =
            cell.split(' ').filter { it.isNotEmpty() }.map {
                val (price, amount) = it.split(':')
                BookLevel(BigDecimal(price), BigDecimal(amount))
            }
    }
}
