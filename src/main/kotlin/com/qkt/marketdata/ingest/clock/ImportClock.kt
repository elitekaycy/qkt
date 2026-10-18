package com.qkt.marketdata.ingest.clock

import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules

/**
 * The clock a source's wall-clock timestamps were written in. A timestamp that carries its own zone
 * (epoch, `Z`, an offset) needs none; a naive one is meaningless without it.
 *
 * [NyClose] is the retail broker clock written "GMT+2/GMT+3": New York time plus seven hours, so every
 * trading day starts at 00:00 server time at the 17:00 New York rollover. It switches on the **US** DST
 * dates, which no IANA zone does (`Europe/Athens` switches on the EU dates, 2–3 weeks apart each spring
 * and about a week each autumn), so it gets its own name.
 */
sealed interface ImportClock {
    /** The name a preset or `--tz` uses for this clock. */
    val spec: String

    /** The zone rules a wall-clock time is resolved against, after [shiftHours]. */
    val rules: ZoneRules

    /** Hours to subtract from a wall-clock time before resolving it in [rules] (7 for [NyClose], else 0). */
    val shiftHours: Long get() = 0

    data object Utc : ImportClock {
        override val spec: String = "UTC"
        override val rules: ZoneRules = ZoneOffset.UTC.rules
    }

    data class Fixed(
        val offset: ZoneOffset,
    ) : ImportClock {
        override val spec: String = offset.id
        override val rules: ZoneRules = offset.rules
    }

    data class Region(
        val zone: ZoneId,
    ) : ImportClock {
        override val spec: String = zone.id
        override val rules: ZoneRules = zone.rules
    }

    data object NyClose : ImportClock {
        override val spec: String = "ny-close"
        override val rules: ZoneRules = ZoneId.of("America/New_York").rules
        override val shiftHours: Long = 7
    }

    companion object {
        private val FIXED = Regex("""[+-]\d{1,2}(:\d{2})?""")
        private val AMBIGUOUS = Regex("""(?i)(GMT|UTC)\s*[+-]\s*\d{1,2}(:?\d{2})?""")

        /**
         * The clock [text] names: `UTC`, a fixed offset (`+02:00`, `-5`), `ny-close`, or an IANA zone
         * (`Europe/Athens`, `America/Chicago`). `GMT+2` is refused: brokers write it for a clock that
         * changes with DST, and only the user knows whether theirs does.
         */
        fun parse(text: String): ImportClock {
            val t = text.trim()
            return when {
                t.equals("UTC", ignoreCase = true) || t == "Z" || t.equals("GMT", ignoreCase = true) -> Utc
                t.equals("ny-close", ignoreCase = true) -> NyClose
                FIXED.matches(t) -> Fixed(fixedOffset(t))
                AMBIGUOUS.matches(t) ->
                    throw IllegalArgumentException(
                        "'$t' is ambiguous: write a fixed offset (e.g. +02:00) for a clock that never changes, " +
                            "or ny-close / an IANA zone (e.g. Europe/Athens) for one that follows DST",
                    )
                else ->
                    try {
                        Region(ZoneId.of(t))
                    } catch (e: DateTimeException) {
                        throw IllegalArgumentException(
                            "unknown clock '$t' (expected UTC, +HH:MM, ny-close or an IANA zone)",
                            e,
                        )
                    }
            }
        }

        private fun fixedOffset(text: String): ZoneOffset {
            val sign = if (text.startsWith('-')) -1 else 1
            val body = text.drop(1)
            val hours = body.substringBefore(':').toInt()
            val minutes = body.substringAfter(':', "0").toInt()
            return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
        }
    }
}

/** The UTC instant of [wall] on this clock, or null when the wall time falls in a DST gap. */
fun ImportClock.utcOf(wall: java.time.LocalDateTime): Long? {
    val shifted = wall.minusHours(shiftHours)
    val offset = rules.getValidOffsets(shifted).firstOrNull() ?: return null
    return shifted.toInstant(offset).toEpochMilli()
}

/**
 * True when [clocks] put every wall-clock time in [span] at the same instant (checked hour by hour), so for this
 * file the choice between them changes nothing: `ny-close` and `+03:00` agree through a US summer.
 */
fun agreeOver(
    clocks: List<ImportClock>,
    span: ClosedRange<java.time.LocalDateTime>,
): Boolean {
    var t =
        span.start
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
    while (!t.isAfter(span.endInclusive.plusHours(1))) {
        if (clocks.map { it.utcOf(t) }.distinct().size > 1) return false
        t = t.plusHours(1)
    }
    return true
}
