package com.qkt.marketdata.ingest.clock

import com.qkt.marketdata.ingest.parse.ParsedTime
import java.time.Instant
import java.time.ZoneId

/** How one week opened, read on a candidate clock: its UTC instant and the New York hour it fell in. */
data class WeekOpen(
    val utcMs: Long,
    val newYorkHour: Int,
    val usSummer: Boolean,
    val euSummer: Boolean,
)

/**
 * What the weekly opens of an FX or metals file say about a candidate clock. FX opens at 17:00 New York
 * time and spot metals at 18:00, every week, summer or winter, so read on the right clock the opens fall
 * in one New York hour. A wrong DST rule moves that hour in some weeks: a fixed offset for a DST-switching
 * broker shifts every summer week, `Europe/Athens` for a `ny-close` broker shifts the 3-4 weeks a year when
 * the US and EU switch dates differ. [passes] when nine weeks in ten share one hour inside 16:00-19:00, and
 * neither the summer weeks, the winter weeks nor the US/EU mismatch weeks lean to another hour (one-off
 * holiday weeks such as New Year are allowed).
 */
data class ClockEvidence(
    val clock: ImportClock,
    val weeks: List<WeekOpen>,
) {
    /** The New York hour most weeks open in, or null without weeks. */
    val modalHour: Int? =
        weeks
            .groupingBy { it.newYorkHour }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

    /** Weeks that opened outside [modalHour]. */
    val outliers: List<WeekOpen> = weeks.filter { it.newYorkHour != modalHour }

    /** True when the weeks span both DST seasons, so a wrong DST rule would have shown. */
    val spansSeasons: Boolean = weeks.any { it.usSummer } && weeks.any { !it.usSummer }

    val passes: Boolean
        get() {
            val hour = modalHour ?: return false
            if (hour !in OPEN_HOURS) return false
            if (outliers.size * 10 > weeks.size) return false
            return listOf(
                weeks.filter { it.usSummer },
                weeks.filter { !it.usSummer },
                weeks.filter {
                    it.usSummer !=
                        it.euSummer
                },
            ).none { group -> group.size >= 2 && group.count { it.newYorkHour != hour } * 2 > group.size }
        }

    /** One line for the report, e.g. `ny-close: 52 weekly opens, 51 at 17:00 New York`. */
    fun summary(): String =
        if (weeks.isEmpty()) {
            "${clock.spec}: no weekly opens to check"
        } else {
            "${clock.spec}: ${weeks.size} weekly opens, ${weeks.size - outliers.size} at ${"%02d".format(
                modalHour,
            )}:00 New York" +
                (if (outliers.isEmpty()) "" else ", ${outliers.size} elsewhere") +
                (if (spansSeasons) "" else " (one DST season only)")
        }

    companion object {
        private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
        private val LONDON: ZoneId = ZoneId.of("Europe/London")
        private val OPEN_HOURS = 16..19
        private const val LATE_OPEN_SECONDS = 60L

        /** The evidence [opens] give for [clock]; wall-clock opens in a DST gap are left out. */
        fun of(
            clock: ImportClock,
            opens: List<ParsedTime>,
        ): ClockEvidence {
            val weeks =
                opens.mapNotNull { open ->
                    val utcMs =
                        when (open) {
                            is ParsedTime.Instant -> open.epochMs
                            is ParsedTime.Local -> {
                                val wall = open.value.minusHours(clock.shiftHours)
                                val offset = clock.rules.getValidOffsets(wall).firstOrNull() ?: return@mapNotNull null
                                wall.toInstant(offset).toEpochMilli()
                            }
                            is ParsedTime.Date -> return@mapNotNull null
                        }
                    val instant = Instant.ofEpochMilli(utcMs).plusSeconds(LATE_OPEN_SECONDS)
                    WeekOpen(
                        utcMs,
                        instant.atZone(NEW_YORK).hour,
                        NEW_YORK.rules.isDaylightSavings(instant),
                        LONDON.rules.isDaylightSavings(instant),
                    )
                }
            return ClockEvidence(clock, weeks)
        }

        /** The clocks a naive FX file could be on, tried when no clock is given. */
        val CANDIDATES: List<ImportClock> =
            listOf(
                "UTC",
                "ny-close",
                "Europe/Athens",
                "Europe/London",
                "Europe/Berlin",
                "America/New_York",
                "-05:00",
                "+02:00",
                "+03:00",
            ).map(ImportClock::parse)
    }
}
