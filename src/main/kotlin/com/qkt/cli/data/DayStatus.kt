package com.qkt.cli.data

import com.qkt.marketdata.store.DayQuality
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The `qkt data verify` verdict for one tick day file. Besides unreadable, empty and gappy files it flags
 * ticks stored in another day's file (`MISPLACED`: every reader trusts the file name, so such a tick is
 * replayed out of order) and a day that starts before the previous day ended (`BACKWARDS`: each reader
 * checks time order within one file only).
 */
internal object DayStatus {
    /** Intra-day gap (ms) above which a day file is flagged as possibly partial. 6h. */
    private const val SUSPICIOUS_GAP_MS = 6 * 60 * 60 * 1000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /** The status of [q], the file of [day], whose previous day's last tick was at [previousLastMs]. */
    fun of(
        q: DayQuality,
        day: LocalDate,
        previousLastMs: Long?,
    ): String {
        val startMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val first = q.firstMs
        val last = q.lastMs
        val misplaced = first != null && last != null && (first < startMs || last >= startMs + DAY_MS)
        val backwards = first != null && previousLastMs != null && first < previousLastMs
        return when {
            !q.readable -> "CORRUPT (unreadable)"
            q.isEmpty -> "EMPTY (0 ticks)"
            misplaced -> "MISPLACED (ticks outside $day UTC)"
            backwards -> "BACKWARDS (starts before the previous day ended)"
            q.maxGapMs >= SUSPICIOUS_GAP_MS -> "GAP ${q.maxGapMs / HOUR_MS}h"
            else -> "ok"
        }
    }
}
