package com.qkt.marketdata.store

import java.time.LocalDate

/**
 * Completeness of one UTC day key's data, as [SessionCoverage] judges it. [detail] says what the verdict rests
 * on in words a report can print, e.g. `holiday`, `short session (last tick 19:42Z)`, `no ticks 12:05Z-15:15Z`;
 * [emptyHours] are the UTC hours a hole touches. [atEdge] marks an incomplete day whose every hole lies where
 * the checked data starts or ends (nothing before it, or nothing stored after it): a store's last day cut off
 * by the end of an import, or a truncated fetch.
 */
data class DayCompleteness(
    val day: LocalDate,
    val status: Status,
    val emptyHours: List<Int> = emptyList(),
    val detail: String = "",
    val atEdge: Boolean = false,
) {
    /**
     * `NON_TRADING`: no trading session touches the day (weekend, or a holiday such as Good Friday).
     * `COMPLETE`: every session hour holds data. `SHORT`: a session opened late or closed early (holiday hours)
     * and the store holds data after it, so nothing is missing. `INCOMPLETE`: a stretch inside a session has no
     * data. `MISSING`: the day should hold data and holds none.
     */
    enum class Status { NON_TRADING, COMPLETE, SHORT, INCOMPLETE, MISSING }

    /** The day needs no data: a closed day. */
    val holiday: Boolean get() = status == Status.NON_TRADING && detail == HOLIDAY

    /** The day holds what its sessions need (a short session counts). */
    val covered: Boolean get() = status == Status.COMPLETE || status == Status.SHORT

    /** The day should hold data it lacks. */
    val hole: Boolean get() = status == Status.MISSING || status == Status.INCOMPLETE

    companion object {
        const val HOLIDAY = "holiday"
        const val CLOSED = "closed"
    }
}

/** Completeness report for a symbol over a date range. */
data class CompletenessReport(
    val symbol: String,
    val days: List<DayCompleteness>,
) {
    /** Days that should have data but don't (missing, or a stretch inside a session is empty). */
    val holes: List<DayCompleteness>
        get() = days.filter { it.hole }

    val hasHoles: Boolean get() = holes.isNotEmpty()
}
