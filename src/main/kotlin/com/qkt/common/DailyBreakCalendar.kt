package com.qkt.common

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A [TradingCalendar] that adds one scheduled daily pause on top of a base calendar.
 *
 * Sessions, anchors, and ranges are the base's; only [isScheduledBreak] changes, so nothing
 * that polls or backs off on [isInSession] behaves differently — a wrong break window can
 * misclassify a quote gap, never silence a feed. The window is a wall-clock range in [zone],
 * so a venue that pauses at 17:00 New York keeps the right UTC hour across daylight saving.
 *
 * ```
 * DailyBreakCalendar(FxCalendar, LocalTime.of(17, 0), LocalTime.of(18, 0), ZoneId.of("America/New_York"))
 * ```
 */
class DailyBreakCalendar(
    private val base: TradingCalendar,
    private val breakStart: LocalTime,
    private val breakEnd: LocalTime,
    private val zone: ZoneId,
) : TradingCalendar by base {
    init {
        require(breakStart != breakEnd) { "daily break must span a non-empty window: $breakStart-$breakEnd" }
    }

    override val name: String = "${base.name} pause $breakStart-$breakEnd ${zone.id}"

    override fun isScheduledBreak(
        symbol: String,
        t: Instant,
    ): Boolean {
        val local = t.atZone(zone).toLocalTime()
        // A window that wraps midnight (23:30-00:30) matches either side of it.
        return if (breakStart < breakEnd) {
            !local.isBefore(breakStart) && local.isBefore(breakEnd)
        } else {
            !local.isBefore(breakStart) || local.isBefore(breakEnd)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DailyBreakCalendar &&
            base == other.base &&
            breakStart == other.breakStart &&
            breakEnd == other.breakEnd &&
            zone == other.zone

    override fun hashCode(): Int = listOf(base, breakStart, breakEnd, zone).hashCode()

    override fun toString(): String = name

    companion object {
        private val SPEC =
            Regex("""^(\S+)\s+pause\s+(\d{2}:\d{2})-(\d{2}:\d{2})(?:\s+(\S+))?$""", RegexOption.IGNORE_CASE)

        /**
         * Parses `<base> pause HH:MM-HH:MM [Zone]` — e.g. `fx pause 17:00-18:00 America/New_York`.
         * The zone defaults to UTC. Returns null when [spec] carries no `pause` clause, so callers
         * fall through to plain calendar names.
         */
        fun parse(
            spec: String,
            baseByName: (String) -> TradingCalendar,
        ): TradingCalendar? {
            if (!spec.contains("pause", ignoreCase = true)) return null
            val m =
                SPEC.matchEntire(spec.trim())
                    ?: error(
                        "calendar '$spec' must read '<base> pause HH:MM-HH:MM [Zone]', e.g. 'fx pause 17:00-18:00 America/New_York'",
                    )
            val zone = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { ZoneId.of(it) } ?: java.time.ZoneOffset.UTC
            return DailyBreakCalendar(
                base = baseByName(m.groupValues[1]),
                breakStart = LocalTime.parse(m.groupValues[2]),
                breakEnd = LocalTime.parse(m.groupValues[3]),
                zone = zone,
            )
        }
    }
}
