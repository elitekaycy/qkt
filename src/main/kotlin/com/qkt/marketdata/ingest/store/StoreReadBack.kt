package com.qkt.marketdata.ingest.store

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.BarDayCsv
import java.nio.file.Path

/**
 * Reads freshly written day files back through the readers backtests use, so an import is accepted only
 * when qkt itself reads exactly what was written: the same number of rows, all inside the day's file.
 */
object StoreReadBack {
    /** Ticks in the tick day file at [path] as [CsvTickFeed] reads them; fails if it reads a different count. */
    fun ticks(
        path: Path,
        expected: Int,
    ) {
        var count = 0
        CsvTickFeed(path).use { feed ->
            while (feed.next() != null) count++
        }
        check(count == expected) { "read back $count ticks from $path, wrote $expected" }
    }

    /** Bars in the bar day file at [path] as the bar store reads them; fails if it reads a different count. */
    fun bars(
        path: Path,
        timeframe: String,
        expected: Int,
    ) {
        val read = BarDayCsv.read(path, "READBACK:X", TimeWindow.parse(timeframe).durationMs)
        check(read.size == expected) { "read back ${read.size} bars from $path, wrote $expected" }
    }
}
