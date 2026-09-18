package com.qkt.marketdata.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** UTC calendar days the half-open `[fromMs, toMs)` range touches, in order. */
internal fun daysCovering(
    fromMs: Long,
    toMs: Long,
): List<LocalDate> {
    val fromDay = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC).toLocalDate()
    val toInclusiveDay = Instant.ofEpochMilli(toMs - 1).atZone(ZoneOffset.UTC).toLocalDate()
    val days = mutableListOf<LocalDate>()
    var d = fromDay
    while (!d.isAfter(toInclusiveDay)) {
        days.add(d)
        d = d.plusDays(1)
    }
    return days
}

/** ISO day names inside the half-open manifest [range]. */
internal fun dayList(range: DayRange): List<String> {
    val days = mutableListOf<String>()
    var d = LocalDate.parse(range.from)
    val end = LocalDate.parse(range.to)
    while (d.isBefore(end)) {
        days.add(d.toString())
        d = d.plusDays(1)
    }
    return days
}

/** Coalesces sorted ISO [days] into half-open manifest ranges of consecutive days. */
internal fun contiguousDayRanges(days: List<String>): List<DayRange> {
    val ranges = mutableListOf<DayRange>()
    var rangeStart: String? = null
    var rangeEnd: String? = null
    for (day in days) {
        val date = LocalDate.parse(day)
        if (rangeStart == null) {
            rangeStart = day
            rangeEnd = date.plusDays(1).toString()
        } else if (rangeEnd == day) {
            rangeEnd = date.plusDays(1).toString()
        } else {
            ranges.add(DayRange(rangeStart, rangeEnd!!))
            rangeStart = day
            rangeEnd = date.plusDays(1).toString()
        }
    }
    if (rangeStart != null) ranges.add(DayRange(rangeStart, rangeEnd!!))
    return ranges
}
