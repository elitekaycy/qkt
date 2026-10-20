package com.qkt.marketdata.ingest.clock

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The two compound clock forms [ImportClock.parse] reads: a zone shifted by hours, and eras of clocks. */
internal object ClockGrammar {
    private val SHIFTED = Regex("""([A-Za-z_]+(?:/[A-Za-z0-9_+-]+)+)\s*([+-]\d{1,2})h""")

    /** `Europe/London-5h`, or null when [text] is not a shifted zone. */
    fun shifted(text: String): ImportClock.Shifted? {
        val m = SHIFTED.matchEntire(text) ?: return null
        val zone =
            try {
                ZoneId.of(m.groupValues[1])
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("unknown zone '${m.groupValues[1]}' in clock '$text'", e)
            }
        return ImportClock.Shifted(zone, m.groupValues[2].toLong())
    }

    /** `<clock> until <date-time>, then <clock>[ until …, then <clock>]`, each `until` later than the last. */
    fun eras(
        text: String,
        clockOf: (String) -> ImportClock,
    ): ImportClock.Eras {
        val parts = text.split(", then ").map { it.trim() }
        val eras =
            parts.mapIndexed { i, part ->
                val last = i == parts.lastIndex
                val until = part.substringAfter(" until ", "").trim()
                require(last == until.isEmpty()) {
                    "clock '$text': every era but the last needs 'until <date-time>', and the last none"
                }
                val clock = clockOf(if (last) part else part.substringBefore(" until "))
                (if (last) null else dateTime(text, until)) to clock
            }
        val ends = eras.mapNotNull { it.first }
        require(ends.zipWithNext().all { (a, b) -> a < b }) {
            "clock '$text': each 'until' must be later than the one before"
        }
        return ImportClock.Eras(eras)
    }

    private fun dateTime(
        text: String,
        until: String,
    ): LocalDateTime =
        try {
            LocalDateTime.parse(until)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("clock '$text': '$until' is not a date-time like 2018-12-16T17:00", e)
        }
}
