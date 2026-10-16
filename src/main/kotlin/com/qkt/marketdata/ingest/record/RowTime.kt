package com.qkt.marketdata.ingest.record

import com.qkt.marketdata.ingest.clock.ImportClock
import com.qkt.marketdata.ingest.clock.LocalTimeResolver
import com.qkt.marketdata.ingest.clock.WeeklyOpens
import com.qkt.marketdata.ingest.parse.ParsedTime
import com.qkt.marketdata.ingest.parse.TimeFormat
import com.qkt.marketdata.ingest.parse.TimeText
import com.qkt.marketdata.ingest.rows.CellRef
import com.qkt.marketdata.ingest.rows.ImportRow
import java.time.ZoneOffset

/**
 * Reads one file's timestamps: the [cells] joined with a space, parsed as [format], wall-clock times
 * resolved on [clock] in input order. A calendar date (daily bars, published series) is the start of
 * that UTC day: a date names a session, not an instant, so no clock applies to it. Every parsed time is
 * also fed to [opens], when given, for the clock check.
 */
class RowTime(
    private val cells: List<CellRef>,
    private val format: TimeFormat,
    private val clock: ImportClock?,
    private val opens: WeeklyOpens? = null,
) {
    private val resolver = clock?.let(::LocalTimeResolver)

    /** Count of timestamps whose sub-millisecond digits were dropped. */
    var subMillis: Long = 0
        private set

    /** True once a wall-clock time fell in a DST overlap. */
    val sawOverlap: Boolean get() = resolver?.sawOverlap == true

    /** The raw timestamp text of [row], or null when a time cell is missing. */
    fun text(row: ImportRow): String? {
        val parts = cells.map { row.cell(it)?.trim() ?: return null }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** [row]'s time as written, before any clock; throws [IllegalArgumentException] for unreadable text. */
    fun parse(row: ImportRow): ParsedTime {
        val text = text(row) ?: throw IllegalArgumentException("no timestamp")
        return TimeText.parse(text, format).also { opens?.add(it) }
    }

    /** [row]'s time as UTC epoch milliseconds; throws [IllegalArgumentException] for unreadable text. */
    fun epochMs(row: ImportRow): Long =
        when (val parsed = parse(row)) {
            is ParsedTime.Instant -> {
                if (parsed.subMillis) subMillis++
                parsed.epochMs
            }
            is ParsedTime.Local -> {
                if (parsed.subMillis) subMillis++
                val r =
                    resolver
                        ?: throw IllegalArgumentException("'${parsed.value}' has no zone and no clock was given (--tz)")
                r.toEpochMs(parsed.value, row.where)
            }
            is ParsedTime.Date ->
                parsed.value
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
        }
}
