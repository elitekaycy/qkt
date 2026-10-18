package com.qkt.cli.data

import com.qkt.marketdata.store.DayQuality
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The integrity half of the `qkt data verify` verdict for one tick day file: unreadable files, ticks stored in
 * another day's file (`MISPLACED`: every reader trusts the file name, so such a tick is replayed out of order)
 * and a day that starts before the previous day ended (`BACKWARDS`: each reader checks time order within one
 * file only). Empty days and gaps are coverage, judged by [VerifyCoverage].
 */
internal object DayStatus {
    private const val DAY_MS = 86_400_000L

    /**
     * What is wrong with [q], the file of [day], whose previous stored day had its last tick at [previousLastMs];
     * null when the file is sound.
     */
    fun of(
        q: DayQuality,
        day: LocalDate,
        previousLastMs: Long?,
    ): String? {
        val startMs = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val first = q.firstMs
        val last = q.lastMs
        val misplaced = first != null && last != null && (first < startMs || last >= startMs + DAY_MS)
        val backwards = first != null && previousLastMs != null && first < previousLastMs
        return when {
            !q.readable -> "CORRUPT (unreadable)"
            misplaced -> "MISPLACED (ticks outside $day UTC)"
            backwards -> "BACKWARDS (starts before the previous day ended)"
            else -> null
        }
    }
}
