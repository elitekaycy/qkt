package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import com.qkt.marketdata.Tick
import com.qkt.marketdata.openDayFeed
import com.qkt.marketdata.store.DataStore
import java.time.Instant
import java.time.LocalDate

/**
 * Serves [LocalMarketSource.tickSlice]: short time-window tick reads from the day files of
 * [store], keeping one open binary feed per (store key, day) so the many per-bar slices of a
 * `--tick-fills` replay seek instead of reopening files.
 */
internal class DayTickSliceReader(
    private val store: DataStore,
) {
    // One mmap'd binary feed per (storeKey, day), reused across the many per-bar slices a
    // --tick-fills replay does. A null value marks a day with no usable binary file.
    private val sliceFeeds = mutableMapOf<Pair<String, LocalDate>, com.qkt.marketdata.BinaryTickFeed?>()

    // The text (csv) days' cursors; only the newest is kept, since a replay moves forward day by day.
    private val textDays =
        object : LinkedHashMap<Pair<String, LocalDate>, CsvDaySlices>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, LocalDate>, CsvDaySlices>?) =
                (size > 1).also { if (it) eldest?.value?.close() }
        }

    fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        val storeKey = symbol.substringAfter(':')
        val range = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
        return sequence {
            for (day in daysCovering(range)) {
                val feed =
                    sliceFeeds.getOrPut(storeKey to day) {
                        val path = store.dayFile(storeKey, day)
                        if (path != null &&
                            path.toString().endsWith(".bin")
                        ) {
                            com.qkt.marketdata.BinaryTickFeed(path)
                        } else {
                            null
                        }
                    }
                if (feed != null) {
                    feed.slice(fromMs, toMs) // O(log n) seek; decodes only the window
                    while (true) {
                        val t = feed.next() ?: break
                        yield(if (t.symbol == symbol) t else t.copy(symbol = symbol))
                    }
                } else {
                    // A text day cannot seek: one open cursor per day serves the replay's in-order slices.
                    val path = store.dayFile(storeKey, day) ?: continue
                    val text = textDays.getOrPut(storeKey to day) { CsvDaySlices { openDayFeed(path) } }
                    for (t in text.slice(fromMs, toMs)) yield(if (t.symbol == symbol) t else t.copy(symbol = symbol))
                }
            }
        }
    }
}
