package com.qkt.marketdata.store

import com.qkt.marketdata.CsvTickFeed
import java.nio.file.Path

/**
 * Quality summary of one cached day file: how many ticks it holds and the largest gap between
 * consecutive ticks. [readable] is false when the file failed to parse (corrupt or truncated gzip).
 */
data class DayQuality(
    val tickCount: Int,
    val maxGapMs: Long,
    val readable: Boolean,
) {
    /** No ticks at all — a non-trading day, or a fetch that wrote an empty/header-only file. */
    val isEmpty: Boolean get() = readable && tickCount == 0
}

/**
 * Inspects a cached day file for completeness. The data store keys coverage on file *presence*, so
 * a truncated, corrupt, or empty day file would otherwise be concatenated into a backtest as if it
 * were complete — silently skewing the result. This reads the file once and reports its tick count
 * and largest inter-tick gap so callers can warn loudly (or `qkt data verify` can report).
 *
 * e.g. a liquid FX day with a 6h gap, or 12 ticks where thousands are expected, is suspect.
 */
object DayFileIntegrity {
    /** Read [path] fully and summarize it. A parse failure yields `readable = false`, never throws. */
    fun inspect(path: Path): DayQuality {
        var count = 0
        var maxGap = 0L
        var last = Long.MIN_VALUE
        return try {
            val feed = CsvTickFeed(path)
            try {
                while (true) {
                    val tick = feed.next() ?: break
                    if (last != Long.MIN_VALUE) {
                        val gap = tick.timestamp - last
                        if (gap > maxGap) maxGap = gap
                    }
                    last = tick.timestamp
                    count++
                }
            } finally {
                feed.close()
            }
            DayQuality(tickCount = count, maxGapMs = maxGap, readable = true)
        } catch (e: Exception) {
            DayQuality(tickCount = count, maxGapMs = maxGap, readable = false)
        }
    }
}
