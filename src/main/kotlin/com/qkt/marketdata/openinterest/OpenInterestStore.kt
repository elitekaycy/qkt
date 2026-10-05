package com.qkt.marketdata.openinterest

import com.qkt.instrument.QktSymbols
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Contracts' open interest, one CSV per contract under `<dataRoot>/open_interest/<VENUE>/<NAME>.csv`
 * (`time,open_interest`, oldest first, `time` the instant the figure became known), the same file whatever
 * the venue. Figures are kept as their exact text.
 */
class OpenInterestStore(
    private val dataRoot: Path,
) : OpenInterestSource {
    /** Where [qktSymbol]'s open interest lives. */
    fun path(qktSymbol: String): Path {
        QktSymbols.requireFileSafe(qktSymbol)
        return dataRoot
            .resolve(DIRECTORY)
            .resolve(qktSymbol.substringBefore(':'))
            .resolve("${qktSymbol.substringAfter(':')}.csv")
    }

    /** [qktSymbol]'s figures, oldest first, or null when none were fetched; a malformed line fails naming the file. */
    fun read(qktSymbol: String): List<OpenInterest>? {
        val file = path(qktSymbol)
        if (!Files.exists(file)) return null
        return Files.readAllLines(file).drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
            val cells = line.split(',')
            require(cells.size == 2) { "$file line ${i + 2}: expected time,open_interest: $line" }
            OpenInterest(cells[0].toLong(), BigDecimal(cells[1]))
        }
    }

    /** [qktSymbol]'s stored figures known from [fromMs] to [toMs], oldest first; none when nothing was fetched. */
    override fun figures(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<OpenInterest> = read(qktSymbol).orEmpty().filter { it.timeMs in fromMs..toMs }

    /** Adds [figures] to [qktSymbol]'s file, a figure at a time already stored replaced; returns how many it now holds. */
    fun merge(
        qktSymbol: String,
        figures: List<OpenInterest>,
    ): Int {
        val all = (read(qktSymbol).orEmpty() + figures).associateBy { it.timeMs }.toSortedMap().values
        val file = path(qktSymbol)
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(staged, HEADER + all.joinToString("") { "${it.timeMs},${it.openInterest.toPlainString()}\n" })
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return all.size
    }

    private companion object {
        const val DIRECTORY = "open_interest"
        const val HEADER = "time,open_interest\n"
    }
}
