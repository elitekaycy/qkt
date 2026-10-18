package com.qkt.marketdata.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MM_DD_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * [ms] as `HH:mmZ`, with the date when it falls on another day, e.g. `02-20 00:00Z`; the end of [day] as `24:00Z`
 * when [endOfStretch] (a stretch running to midnight), else as the next day's `00:00Z` (a first tick then).
 */
internal fun clock(
    ms: Long,
    day: LocalDate,
    endOfStretch: Boolean = true,
): String {
    if (endOfStretch && ms == SessionCoverage.startMs(day.plusDays(1))) return "24:00Z"
    val at = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
    val pattern = if (at.toLocalDate() == day) HH_MM else MM_DD_HH_MM
    return at.format(pattern) + "Z"
}

/** [holes] in time order with stretches that touch joined, e.g. an early close running into the next open. */
internal fun mergedHoles(holes: List<Pair<Finding, Span>>): List<Pair<Finding, Span>> {
    val out = mutableListOf<Pair<Finding, Span>>()
    for (hole in holes.sortedBy { it.second.fromMs }) {
        val last = out.lastOrNull()
        out +=
            if (last != null && last.second.toMs == hole.second.fromMs) {
                out.removeAt(out.size - 1)
                last.first to Span(last.second.fromMs, hole.second.toMs)
            } else {
                hole
            }
    }
    return out
}

/** The empty stretch [s] of hole [f] on [day] in words: `12:05Z-15:15Z`, `after 16:59Z (nothing stored later)`. */
internal fun holeText(
    f: Finding,
    s: Span,
    day: LocalDate,
): String =
    when {
        !f.dataAfter && !f.storedLater -> "after ${clock(s.fromMs, day)} (nothing stored later)"
        !f.dataBefore -> "before ${clock(s.toMs, day)}"
        else -> "${clock(s.fromMs, day)}-${clock(s.toMs, day)}"
    }
