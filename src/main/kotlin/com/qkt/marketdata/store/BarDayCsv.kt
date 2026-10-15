package com.qkt.marketdata.store

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * The bar store's day-file codec: a `timestamp,open,high,low,close,volume` header, then one
 * row per bar keyed by its start time in UTC epoch ms, prices as plain decimal strings.
 */
internal object BarDayCsv {
    /** The day file text for [bars], sorted by start time. */
    fun render(bars: List<Candle>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (b in bars.sortedBy { it.startTime }) {
            sb.append(b.startTime).append(',')
            sb.append(b.open.toPlainString()).append(',')
            sb.append(b.high.toPlainString()).append(',')
            sb.append(b.low.toPlainString()).append(',')
            sb.append(b.close.toPlainString()).append(',')
            sb.append(b.volume.toPlainString()).append('\n')
        }
        return sb.toString()
    }

    /**
     * Bars in the day file at [path], stamped [qktSymbol] and [windowMs] long. The file must start with the
     * header and hold rows of at least six fields in strictly rising time; anything else fails naming the
     * file and line, so a damaged day is never read as a shorter one. Fields past the sixth are ignored, so
     * files with more columns stay readable.
     */
    fun read(
        path: Path,
        qktSymbol: String,
        windowMs: Long,
    ): List<Candle> {
        val out = mutableListOf<Candle>()
        Files.newBufferedReader(path).use { reader ->
            val header = reader.readLine() ?: return emptyList()
            require(header.trim() == HEADER) { "unexpected header at $path:1: got '$header', expected '$HEADER'" }
            var lineNo = 1
            var previous: Long? = null
            while (true) {
                val line = reader.readLine() ?: break
                lineNo++
                if (line.isBlank()) continue
                val candle = parse(line, "$path:$lineNo", qktSymbol, windowMs)
                require(previous == null || candle.startTime > previous) {
                    "$path:$lineNo: bar times must rise (got ${candle.startTime} after $previous): $line"
                }
                previous = candle.startTime
                out.add(candle)
            }
        }
        return out
    }

    private fun parse(
        line: String,
        where: String,
        qktSymbol: String,
        windowMs: Long,
    ): Candle {
        val parts = line.split(',')
        require(parts.size >= FIELDS) { "$where: expected $FIELDS fields, got ${parts.size}: $line" }
        return try {
            val ts = parts[0].toLong()
            Candle(
                symbol = qktSymbol,
                open = BigDecimal(parts[1]),
                high = BigDecimal(parts[2]),
                low = BigDecimal(parts[3]),
                close = BigDecimal(parts[4]),
                volume = BigDecimal(parts[5]),
                startTime = ts,
                endTime = ts + windowMs,
            )
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("$where: not a number (${e.message}): $line", e)
        }
    }

    private const val HEADER = "timestamp,open,high,low,close,volume"
    private const val FIELDS = 6
}
