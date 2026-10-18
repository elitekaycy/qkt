package com.qkt.marketdata.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MM_DD_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** [ms] as `HH:mmZ`, `24:00Z` at the end of [day], with the date when it falls on another day, e.g. `02-20 00:00Z`. */
internal fun clock(
    ms: Long,
    day: LocalDate,
): String {
    if (ms == SessionCoverage.startMs(day.plusDays(1))) return "24:00Z"
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
