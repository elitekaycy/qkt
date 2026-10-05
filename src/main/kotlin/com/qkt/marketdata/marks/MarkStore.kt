package com.qkt.marketdata.marks

import com.qkt.candles.TimeWindow
import com.qkt.instrument.QktSymbols
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Contracts' mark and index histories under `<dataRoot>/marks/<VENUE>/<NAME>/<window>/<day>.csv`
 * (`time,mark,index`, oldest first, a value empty when the venue did not report it), one file per UTC day
 * and per sampling window (`1m`, `1h`), the same whatever the venue. A day's file exists once that whole
 * day was fetched, even when the venue reported nothing in it. Values are kept as their exact text.
 */
class MarkStore(
    private val dataRoot: Path,
) {
    /** The directory of [qktSymbol]'s samples every [windowMs]. */
    fun dir(
        qktSymbol: String,
        windowMs: Long,
    ): Path {
        QktSymbols.requireFileSafe(qktSymbol)
        return dataRoot
            .resolve("marks")
            .resolve(qktSymbol.substringBefore(':'))
            .resolve(qktSymbol.substringAfter(':'))
            .resolve(TimeWindow(windowMs).canonicalSpec())
    }

    /** Whether [day] of [qktSymbol]'s samples every [windowMs] was stored. */
    fun has(
        qktSymbol: String,
        windowMs: Long,
        day: LocalDate,
    ): Boolean = Files.exists(dir(qktSymbol, windowMs).resolve("$day.csv"))

    /** [day]'s samples, oldest first, or null when the day was never stored; a malformed line fails naming the file. */
    fun read(
        qktSymbol: String,
        windowMs: Long,
        day: LocalDate,
    ): List<MarkSample>? {
        val file = dir(qktSymbol, windowMs).resolve("$day.csv")
        if (!Files.exists(file)) return null
        return Files.readAllLines(file).drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
            val cells = line.split(',')
            require(cells.size == 3) { "$file line ${i + 2}: expected time,mark,index: $line" }
            MarkSample(cells[0].toLong(), cells[1].decimal(), cells[2].decimal())
        }
    }

    /** Stores [samples] as [day]'s whole file, replacing what was there. */
    fun write(
        qktSymbol: String,
        windowMs: Long,
        day: LocalDate,
        samples: List<MarkSample>,
    ) {
        val file = dir(qktSymbol, windowMs).resolve("$day.csv")
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("$day.csv.tmp")
        val rows =
            samples.sortedBy { it.timeMs }.joinToString("") {
                "${it.timeMs},${it.mark?.toPlainString().orEmpty()},${it.index?.toPlainString().orEmpty()}\n"
            }
        Files.writeString(staged, HEADER + rows)
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun String.decimal(): BigDecimal? = takeIf { it.isNotEmpty() }?.let(::BigDecimal)

    private companion object {
        const val HEADER = "time,mark,index\n"
    }
}
