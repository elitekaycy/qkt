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
        sb.append("timestamp,open,high,low,close,volume\n")
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

    /** Bars in the day file at [path], stamped [qktSymbol] and [windowMs] long; rows under six fields are skipped. */
    fun read(
        path: Path,
        qktSymbol: String,
        windowMs: Long,
    ): List<Candle> {
        val out = mutableListOf<Candle>()
        Files.newBufferedReader(path).use { reader ->
            if (reader.readLine() == null) return emptyList()
            var line: String? = reader.readLine()
            while (line != null) {
                val parts = line.split(',')
                if (parts.size >= 6) {
                    val ts = parts[0].toLong()
                    out.add(
                        Candle(
                            symbol = qktSymbol,
                            open = BigDecimal(parts[1]),
                            high = BigDecimal(parts[2]),
                            low = BigDecimal(parts[3]),
                            close = BigDecimal(parts[4]),
                            volume = BigDecimal(parts[5]),
                            startTime = ts,
                            endTime = ts + windowMs,
                        ),
                    )
                }
                line = reader.readLine()
            }
        }
        return out
    }
}
