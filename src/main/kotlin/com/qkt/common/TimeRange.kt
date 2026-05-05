package com.qkt.common

import java.time.Instant
import java.time.ZoneOffset

data class TimeRange(
    val from: Instant,
    val to: Instant,
) {
    init {
        require(from < to) { "TimeRange requires from < to: from=$from, to=$to" }
    }

    val durationMs: Long
        get() = to.toEpochMilli() - from.toEpochMilli()

    operator fun contains(t: Instant): Boolean = t >= from && t < to

    companion object {
        fun of(
            from: TimeMark,
            to: TimeMark,
            clock: Clock,
            calendar: TradingCalendar,
        ): TimeRange {
            val now = Instant.ofEpochMilli(clock.now())
            val resolvedFrom = resolve(from, now, calendar, isStart = true)
            val resolvedTo = resolve(to, now, calendar, isStart = false)
            require(resolvedFrom < resolvedTo) {
                "Inverted TimeRange: from=$resolvedFrom to=$resolvedTo"
            }
            require(resolvedTo <= now) {
                "look-ahead bias: TimeRange.to ($resolvedTo) must be <= now ($now)"
            }
            return TimeRange(resolvedFrom, resolvedTo)
        }

        private fun resolve(
            mark: TimeMark,
            now: Instant,
            cal: TradingCalendar,
            isStart: Boolean,
        ): Instant =
            when (mark) {
                is TimeMark.Now -> now
                is TimeMark.Absolute -> mark.instant
                is TimeMark.RelativeToNow -> now.plus(mark.offset)
                is TimeMark.AtSessionAnchor -> resolveAnchor(mark, now, cal, isStart)
            }

        private fun resolveAnchor(
            mark: TimeMark.AtSessionAnchor,
            now: Instant,
            cal: TradingCalendar,
            @Suppress("UNUSED_PARAMETER") isStart: Boolean,
        ): Instant {
            val anchorEpoch = cal.anchorEpochFor(mark.anchor, now)
            val range = cal.rangeFor(mark.anchor, anchorEpoch)
            if (mark.timeOfDay == null) {
                return range.from
            }
            val day = range.from.atZone(ZoneOffset.UTC).toLocalDate()
            return day.atTime(mark.timeOfDay).toInstant(ZoneOffset.UTC)
        }
    }
}
