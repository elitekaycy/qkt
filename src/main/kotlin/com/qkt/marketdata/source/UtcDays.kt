package com.qkt.marketdata.source

import com.qkt.common.TimeRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** UTC calendar days a half-open [range] touches, in order: the day files a local read must open. */
internal fun daysCovering(range: TimeRange): List<LocalDate> {
    val fromDay = range.from.atZone(ZoneOffset.UTC).toLocalDate()
    val toInclusiveDay = Instant.ofEpochMilli(range.to.toEpochMilli() - 1).atZone(ZoneOffset.UTC).toLocalDate()
    val days = mutableListOf<LocalDate>()
    var d = fromDay
    while (!d.isAfter(toInclusiveDay)) {
        days.add(d)
        d = d.plusDays(1)
    }
    return days
}
