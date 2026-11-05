package com.qkt.risk

import com.qkt.common.Clock

/**
 * Whether a daemon mixes standalone strategies and portfolios today, which is when `risk.max_daily_loss` is one
 * total that only blocks entries (#1565). Sticky for the UTC day: undeploying the last standalone must not turn
 * a book already past the limit back into a book whose own rule flattens it, e.g. a book at -9 of 8 stays open
 * until the next UTC day. The current members are read once per day, so the per-tick [mixed] takes no lock and
 * scans nothing.
 */
class DaemonComposition(
    private val clock: Clock,
) {
    @Volatile private var standaloneDay = NEVER

    @Volatile private var bookDay = NEVER

    @Volatile private var mixedDay = NEVER

    @Volatile private var checkedDay = NEVER

    /** Reads whether the daemon runs a standalone (first) and a portfolio (second) right now. */
    @Volatile private var members: () -> Pair<Boolean, Boolean> = { false to false }

    /** Says what the daemon runs, for the day boundary, where the notes below have expired. */
    fun members(read: () -> Pair<Boolean, Boolean>) {
        members = read
        checkedDay = NEVER
    }

    /** A standalone is about to deploy: before it trades, so no book's rule fires in between. */
    fun noteStandalone() {
        standaloneDay = today()
        checkedDay = NEVER
    }

    /** A portfolio is about to deploy. */
    fun noteBook() {
        bookDay = today()
        checkedDay = NEVER
    }

    /** Restores a day this daemon already was mixed. */
    fun restoreMixed(epochDay: Long) {
        mixedDay = epochDay
        checkedDay = NEVER
    }

    /** The day [mixed] last held, for the daemon's record. */
    fun mixedEpochDay(): Long? = mixedDay.takeIf { it != NEVER }

    /** Whether the daemon has mixed standalones and portfolios at any point of this UTC day. */
    fun mixed(): Boolean {
        val today = today()
        if (mixedDay == today) return true
        return checkedDay != today && recheck(today)
    }

    @Synchronized
    private fun recheck(today: Long): Boolean {
        val (standalones, books) = members()
        val mixed = (standalones || standaloneDay == today) && (books || bookDay == today)
        if (mixed) mixedDay = today
        checkedDay = today
        return mixed
    }

    private fun today(): Long = Math.floorDiv(clock.now(), MILLIS_PER_DAY)

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
