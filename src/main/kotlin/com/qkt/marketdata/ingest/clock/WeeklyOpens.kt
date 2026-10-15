package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.parse.ParsedTime
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Collects the first timestamp after each weekend from a stream of times read in file order (either
 * direction: a newest-first file is fine). A weekend is a silence of at least [MIN_GAP] ending on a
 * Sunday or Monday; the time after it is that week's open, as the source wrote it.
 */
class WeeklyOpens {
    private val opens = mutableListOf<ParsedTime>()
    private var previous: ParsedTime? = null

    /** The opens seen so far, oldest first. */
    val all: List<ParsedTime> get() = opens.sortedBy(::sortKey)

    /** Feeds the next time in file order. */
    fun add(time: ParsedTime) {
        val prev = previous
        previous = time
        if (prev == null || time is ParsedTime.Date || prev is ParsedTime.Date) return
        val gap = Duration.ofMillis(sortKey(time) - sortKey(prev))
        if (gap.abs() < MIN_GAP) return
        val later = if (gap.isNegative) prev else time
        if (wallOf(later).dayOfWeek in OPEN_DAYS) opens += later
    }

    private fun wallOf(t: ParsedTime): LocalDateTime =
        when (t) {
            is ParsedTime.Local -> t.value
            is ParsedTime.Instant -> LocalDateTime.ofInstant(Instant.ofEpochMilli(t.epochMs), ZoneOffset.UTC)
            is ParsedTime.Date -> t.value.atStartOfDay()
        }

    private fun sortKey(t: ParsedTime): Long = wallOf(t).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        val MIN_GAP: Duration = Duration.ofHours(40)
        val OPEN_DAYS = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)
    }
}
