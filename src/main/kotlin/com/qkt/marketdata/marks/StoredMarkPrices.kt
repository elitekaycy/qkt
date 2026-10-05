package com.qkt.marketdata.marks

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Marks a backtest replays from a [MarkStore]: at an instant, the newest stored sample strictly before it
 * (at a bar's close, the last one inside the bar), found in that day's file or, when the day has none yet,
 * the stored days before it (at most [LOOKBACK_DAYS]). Day files are read once and the newest few kept, as a
 * replay asks in time order.
 */
class StoredMarkPrices(
    private val store: MarkStore,
) : MarkPrices {
    private val days =
        object : LinkedHashMap<Triple<String, Long, LocalDate>, List<MarkSample>?>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Triple<String, Long, LocalDate>, List<MarkSample>?>,
            ) = size > CACHED_DAYS
        }

    override fun at(
        symbol: String,
        windowMs: Long,
        atMs: Long,
    ): MarkSample? =
        synchronized(days) {
            var day = LocalDate.ofInstant(Instant.ofEpochMilli(atMs - 1), ZoneOffset.UTC)
            repeat(LOOKBACK_DAYS) {
                val key = Triple(symbol, windowMs, day)
                val samples =
                    (
                        if (days.containsKey(
                                key,
                            )
                        ) {
                            days[key]
                        } else {
                            store.read(symbol, windowMs, day).also { days[key] = it }
                        }
                    )
                        ?: return null
                samples.lastBefore(atMs)?.let { return it }
                day = day.minusDays(1)
            }
            null
        }

    private fun List<MarkSample>.lastBefore(atMs: Long): MarkSample? {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid].timeMs < atMs) lo = mid + 1 else hi = mid
        }
        return if (lo == 0) null else this[lo - 1]
    }

    private companion object {
        const val CACHED_DAYS = 8
        const val LOOKBACK_DAYS = 8
    }
}
