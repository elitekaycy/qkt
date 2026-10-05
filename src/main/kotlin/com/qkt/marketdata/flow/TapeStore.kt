package com.qkt.marketdata.flow

import com.qkt.common.Side
import com.qkt.instrument.QktSymbols
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Contracts' prints under `<dataRoot>/<kind dir>/<VENUE>/<NAME>/<day>.csv.gz` (`tape/` for the trade tape,
 * `liquidations/` for liquidations; columns `id,time,price,size,side`, oldest first, `side` `buy` or `sell`), one
 * file per UTC day, the same whatever the venue. A day's file exists once that whole day was fetched, even when
 * nothing printed. Prices and sizes are kept as their exact text.
 */
class TapeStore(
    private val dataRoot: Path,
) {
    /** The directory of [qktSymbol]'s [kind] files. */
    fun dir(
        qktSymbol: String,
        kind: FlowKind,
    ): Path {
        QktSymbols.requireFileSafe(qktSymbol)
        return dataRoot.resolve(kind.dir).resolve(qktSymbol.substringBefore(':')).resolve(qktSymbol.substringAfter(':'))
    }

    /** Whether [day] of [qktSymbol]'s [kind] was stored. */
    fun has(
        qktSymbol: String,
        kind: FlowKind,
        day: LocalDate,
    ): Boolean = Files.exists(file(qktSymbol, kind, day))

    /** [day]'s prints, oldest first, or null when the day was never stored; a malformed line fails naming the file. */
    fun read(
        qktSymbol: String,
        kind: FlowKind,
        day: LocalDate,
    ): List<Print>? {
        val file = file(qktSymbol, kind, day)
        if (!Files.exists(file)) return null
        return GZIPInputStream(Files.newInputStream(file)).bufferedReader().useLines { lines ->
            lines
                .drop(1)
                .filter { it.isNotBlank() }
                .mapIndexed { i, line -> parse(file, i + 2, line) }
                .toList()
        }
    }

    /** Stores [prints] as [day]'s whole file, replacing what was there. */
    fun write(
        qktSymbol: String,
        kind: FlowKind,
        day: LocalDate,
        prints: List<Print>,
    ) {
        val file = file(qktSymbol, kind, day)
        Files.createDirectories(file.parent)
        val staged = file.resolveSibling("${file.fileName}.tmp")
        GZIPOutputStream(Files.newOutputStream(staged)).bufferedWriter().use { out ->
            out.write(HEADER)
            for (p in prints.sortedBy { it.timeMs }) {
                require(',' !in p.id) { "print id ${p.id} holds a comma" }
                out.write("${p.id},${p.timeMs},${p.price.toPlainString()},${p.size.toPlainString()},")
                out.write(if (p.side == Side.BUY) "buy\n" else "sell\n")
            }
        }
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun file(
        qktSymbol: String,
        kind: FlowKind,
        day: LocalDate,
    ) = dir(qktSymbol, kind).resolve("$day.csv.gz")

    private fun parse(
        file: Path,
        lineNo: Int,
        line: String,
    ): Print {
        val c = line.split(',')
        require(c.size == 5 && c[4] in SIDES) { "$file line $lineNo: expected id,time,price,size,side: $line" }
        return Print(
            c[0],
            c[1].toLong(),
            BigDecimal(c[2]),
            BigDecimal(c[3]),
            if (c[4] ==
                "buy"
            ) {
                Side.BUY
            } else {
                Side.SELL
            },
        )
    }

    private companion object {
        const val HEADER = "id,time,price,size,side\n"
        val SIDES = setOf("buy", "sell")
    }
}
