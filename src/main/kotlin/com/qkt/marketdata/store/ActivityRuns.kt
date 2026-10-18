package com.qkt.marketdata.store

/**
 * A stretch of stored data with no gap longer than the coverage threshold: from the first event's start to
 * the last event's end (a tick's own time; a bar's start plus its timeframe). e.g. a gold day stores two runs,
 * 00:00-20:59 and 22:00-23:59 UTC, around the daily break when the threshold is below the hour.
 */
data class ActivityRun(
    val startMs: Long,
    val endMs: Long,
)

/**
 * Folds time-ordered event times into [ActivityRun]s, starting a new run whenever the stretch since the
 * previous event's end exceeds [gapMs]. Each event covers [widthMs] from its start (0 for ticks).
 */
class ActivityRunBuilder(
    private val gapMs: Long = SessionCoverage.MAX_GAP_MS,
    private val widthMs: Long = 0L,
) {
    private val runs = mutableListOf<ActivityRun>()
    private var start = Long.MIN_VALUE
    private var end = Long.MIN_VALUE

    /** Adds one event at [atMs]; events must arrive in time order (an earlier one only extends nothing). */
    fun add(atMs: Long) {
        if (start == Long.MIN_VALUE) {
            start = atMs
        } else if (atMs - end > gapMs) {
            runs += ActivityRun(start, end)
            start = atMs
        }
        end = maxOf(end, atMs + widthMs)
    }

    /** The runs so far, the open one included. */
    fun build(): List<ActivityRun> = if (start == Long.MIN_VALUE) runs.toList() else runs + ActivityRun(start, end)

    companion object {
        /** The runs of [times] (any order). */
        fun of(
            times: LongArray,
            widthMs: Long = 0L,
            gapMs: Long = SessionCoverage.MAX_GAP_MS,
        ): List<ActivityRun> {
            val builder = ActivityRunBuilder(gapMs, widthMs)
            times.sorted().forEach { builder.add(it) }
            return builder.build()
        }

        /** [runs] in time order with runs no more than [gapMs] apart joined, e.g. across a day-file boundary. */
        fun merged(
            runs: List<ActivityRun>,
            gapMs: Long = SessionCoverage.MAX_GAP_MS,
        ): List<ActivityRun> {
            val out = mutableListOf<ActivityRun>()
            for (run in runs.sortedBy { it.startMs }) {
                val last = out.lastOrNull()
                if (last != null && run.startMs - last.endMs <= gapMs) {
                    out[out.size - 1] = ActivityRun(last.startMs, maxOf(last.endMs, run.endMs))
                } else {
                    out += run
                }
            }
            return out
        }
    }
}
