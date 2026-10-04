package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Perpetuals' funding rate histories, one CSV per perpetual under `<dataRoot>/funding/<VENUE>/<NAME>.csv`
 * (`time,rate,price`, oldest first, `price` empty when the venue published none), the same file whatever
 * the venue. Rates are kept as their exact text.
 */
class FundingRateStore(
    private val dataRoot: Path,
) {
    /** Where [qktSymbol]'s rates live. */
    fun path(qktSymbol: String): Path {
        QktSymbols.requireFileSafe(qktSymbol)
        return dataRoot
            .resolve(
                "funding",
            ).resolve(qktSymbol.substringBefore(':'))
            .resolve("${qktSymbol.substringAfter(':')}.csv")
    }

    /** [qktSymbol]'s rates, oldest first, or null when none were fetched; a malformed line fails naming the file. */
    fun read(qktSymbol: String): List<FundingRate>? {
        val file = path(qktSymbol)
        if (!Files.exists(file)) return null
        return Files.readAllLines(file).drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
            val cells = line.split(',')
            require(cells.size == 3) { "$file line ${i + 2}: expected time,rate,price: $line" }
            FundingRate(cells[0].toLong(), BigDecimal(cells[1]), cells[2].takeIf { it.isNotEmpty() }?.let(::BigDecimal))
        }
    }

    /** Adds [rates] to [qktSymbol]'s file, a rate at a time already stored replaced; returns how many it now holds. */
    fun merge(
        qktSymbol: String,
        rates: List<FundingRate>,
    ): Int {
        val all = (read(qktSymbol).orEmpty() + rates).associateBy { it.timeMs }.toSortedMap().values
        val file = path(qktSymbol)
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("${file.fileName}.tmp")
        val text =
            all.joinToString(
                "",
            ) { "${it.timeMs},${it.rate.toPlainString()},${it.price?.toPlainString().orEmpty()}\n" }
        Files.writeString(staged, HEADER + text)
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return all.size
    }

    private companion object {
        const val HEADER = "time,rate,price\n"
    }
}
