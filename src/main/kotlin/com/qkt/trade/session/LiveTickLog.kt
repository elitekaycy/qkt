package com.qkt.trade.session

import com.qkt.marketdata.Tick
import java.io.BufferedWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `ticks.csv` of a live session: every tick the engine handed it, in the tick store's column order with the
 * full qkt symbol. A live record replays over these ticks rather than over a later fetch of the venue's
 * history, so the replay sees the market the session traded on. Appended per tick and flushed, so a
 * session that dies keeps what it saw.
 */
class LiveTickLog(
    val path: Path,
) : AutoCloseable {
    private val out: BufferedWriter

    init {
        path.parent?.let { Files.createDirectories(it) }
        val fresh = !Files.exists(path) || Files.size(path) == 0L
        out =
            Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        if (fresh) {
            out.write(HEADER)
            out.newLine()
            out.flush()
        }
    }

    @Synchronized
    fun append(tick: Tick) {
        fun n(v: BigDecimal?) = v?.toPlainString().orEmpty()
        out.write(
            "${tick.timestamp},${tick.symbol},${tick.price.toPlainString()},${n(tick.volume)},${n(tick.bid)}," +
                "${n(tick.ask)},${n(tick.bidVolume)},${n(tick.askVolume)}",
        )
        out.newLine()
        out.flush()
    }

    @Synchronized
    override fun close() = out.close()

    companion object {
        const val FILE = "ticks.csv"
        private const val HEADER = "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"

        /** The ticks in [path], oldest first; empty when the file is absent. */
        fun read(path: Path): List<Tick> {
            if (!Files.exists(path)) return emptyList()
            return Files.readAllLines(path).drop(1).filter { it.isNotBlank() }.map { line ->
                val c = line.split(',')

                fun d(i: Int) = c.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let(::BigDecimal)
                Tick(c[1], BigDecimal(c[2]), c[0].toLong(), d(3), d(4), d(5), d(6), d(7))
            }
        }
    }
}
