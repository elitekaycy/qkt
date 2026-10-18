package com.qkt.marketdata.store

import com.qkt.common.TradingCalendar
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The one rule deciding whether stored data covers a date range, shared by the backtest's tick and bar coverage
 * checks and `qkt data verify`, so they agree by construction.
 *
 * Days stay keyed by UTC date, as the store files them. Expected data comes from the symbol's calendar
 * sessions (FX and metals: 17:00 to 17:00 New York, daylight saving followed), not the UTC day: a session not in
 * the calendar, or one it closes for a holiday (Dec 25, Jan 1 and Good Friday for FX), needs no data. Gaps are
 * measured between the stored data only:
 * - a stretch of session time with no data longer than [MAX_GAP_MS] (90 minutes, as a dataset snapshot's quality
 *   policy) is a hole, so an hour-long daily break passes and a missed fetch hour does not;
 * - an empty stretch from a session's open to its first data (late open), or from its last data to its close
 *   (early close, e.g. 2024-02-19 Presidents' Day gold stops before 19:00 UTC), of at most [MAX_SHORT_MS] is a
 *   short session, not a hole, provided the store holds data after it: the last stored day ending early is a
 *   truncated fetch and stays a hole;
 * - a whole trading session with no data is a hole (2023-11-24 with no file is MISSING).
 * A symbol whose hours qkt does not know (a stock or futures root on the FX default) is judged by UTC weekdays
 * ([UnknownHoursCalendar]).
 * A day is then MISSING (a hole, no data at all), INCOMPLETE (a hole, some data), SHORT, COMPLETE or NON_TRADING.
 */
class SessionCoverage(
    calendar: TradingCalendar,
    private val symbol: String,
    /** What the data is called in [DayCompleteness.detail]: `tick` or `bar`. */
    private val unit: String = "tick",
) {
    private val calendar = UnknownHoursCalendar.coverageCalendar(calendar, symbol)

    /** Hours unknown: a day's edges are not judged, so neither the late-open limit nor a truncated end applies. */
    private val hoursKnown = this.calendar !== UnknownHoursCalendar

    /**
     * Classifies each day of [from]..[to]. [activity] gives a day's stored runs: null when the day has no file
     * (a requested day without one counts as stored empty), empty when its file holds nothing. The day before
     * and after the range are read too, as their sessions reach into it. [storedLater] says whether the store
     * holds data on a day after the one given, for days beyond what was read.
     */
    fun classify(
        from: LocalDate,
        to: LocalDate,
        activity: (LocalDate) -> List<ActivityRun>?,
        storedLater: (LocalDate) -> Boolean = { false },
    ): List<DayCompleteness> {
        if (to.isBefore(from)) return emptyList()
        val before = from.minusDays(1)
        val after = to.plusDays(1)
        val loaded = generateSequence(before) { it.plusDays(1) }.takeWhile { it <= after }.associateWith(activity)
        val window = Span(startMs(if (loaded[before] != null) before else from), startMs(endDay(loaded, after)))
        val runs =
            ActivityRunBuilder
                .merged(loaded.values.filterNotNull().flatten())
                .mapNotNull { Span(it.startMs, it.endMs.coerceAtLeast(it.startMs + 1)).intersect(window) }
        // Read only for a gap with no data after it inside the window: it looks past the window, file by file.
        val later = lazy { !hoursKnown || storedLater(if (loaded[after] != null) after else to) }
        val sessions = coverageSessions(calendar, symbol, window)
        val findings =
            gaps(window, runs).flatMap { (gap, dataBefore, dataAfter) ->
                val laterData = !dataAfter && later.value
                val found =
                    classifyGap(gap, dataBefore, dataAfter, laterData, sessions, calendar, MAX_GAP_MS, maxShortMs)
                chargeHoles(found.filter { hoursKnown || it.kind == FindingKind.HOLE }, MAX_GAP_MS)
            }
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { it <= to }
            .map { day ->
                verdict(day, sessions, findings, hasData = loaded[day].orEmpty().isNotEmpty())
            }.toList()
    }

    private val maxShortMs = if (hoursKnown) MAX_SHORT_MS else Long.MAX_VALUE

    private fun verdict(
        day: LocalDate,
        sessions: List<CoverageSession>,
        findings: List<Finding>,
        hasData: Boolean,
    ): DayCompleteness {
        val span = daySpan(day)
        val touching = sessions.filter { it.span.overlaps(span) }
        val holiday = touching.firstOrNull { it.holiday }
        if (touching.none { it.trading }) {
            val why = if (holiday != null) DayCompleteness.HOLIDAY else DayCompleteness.CLOSED
            return DayCompleteness(day, DayCompleteness.Status.NON_TRADING, detail = why)
        }
        val here = findings.mapNotNull { f -> f.span.intersect(span)?.let { f to it } }
        val holes = here.filter { it.first.kind == FindingKind.HOLE && day in it.first.days }
        if (holes.isNotEmpty()) {
            val hours = holes.flatMap { (_, s) -> hoursOf(s, span) }.distinct().sorted()
            if (!hasData) return DayCompleteness(day, DayCompleteness.Status.MISSING, hours, "no ${unit}s")
            val text = mergedHoles(holes).joinToString(", ") { (f, s) -> holeText(f, s, day) }
            val edge = holes.all { (f, _) -> !f.dataBefore || (!f.dataAfter && !f.storedLater) }
            return DayCompleteness(day, DayCompleteness.Status.INCOMPLETE, hours, "no ${unit}s $text", atEdge = edge)
        }
        // A short session is told on the days holding a stretch of it worth telling, not one starting 5s after midnight.
        val shorts = here.filter { it.first.kind != FindingKind.HOLE && it.second.lengthMs > MAX_GAP_MS }
        if (shorts.isNotEmpty()) {
            val edges =
                shorts.joinToString(", ") { (f, _) ->
                    if (f.kind == FindingKind.EARLY_CLOSE) {
                        "last $unit ${clock(f.span.fromMs, day)}"
                    } else {
                        "first $unit ${clock(f.span.toMs, day, endOfStretch = false)}"
                    }
                }
            return DayCompleteness(day, DayCompleteness.Status.SHORT, detail = "short session ($edges)")
        }
        val note =
            holiday?.let { h ->
                if (h.span.fromMs >
                    span.fromMs
                ) {
                    "holiday from ${clock(h.span.fromMs, day)}"
                } else {
                    "holiday until ${clock(h.span.toMs, day)}"
                }
            }
        return DayCompleteness(day, DayCompleteness.Status.COMPLETE, detail = note ?: "ok")
    }

    /** The stretches of [window] with no run, each with whether data comes before and after it. */
    private fun gaps(
        window: Span,
        runs: List<Span>,
    ): List<Triple<Span, Boolean, Boolean>> {
        val out = mutableListOf<Triple<Span, Boolean, Boolean>>()
        var cursor = window.fromMs
        for ((i, run) in runs.withIndex()) {
            if (run.fromMs > cursor) out += Triple(Span(cursor, run.fromMs), i > 0, true)
            cursor = maxOf(cursor, run.toMs)
        }
        if (cursor < window.toMs) out += Triple(Span(cursor, window.toMs), runs.isNotEmpty(), false)
        return out
    }

    private fun endDay(
        loaded: Map<LocalDate, List<ActivityRun>?>,
        after: LocalDate,
    ): LocalDate = if (loaded[after] != null) after.plusDays(1) else after

    private fun hoursOf(
        s: Span,
        day: Span,
    ): List<Int> = ((s.fromMs - day.fromMs) / HOUR_MS..(s.toMs - 1 - day.fromMs) / HOUR_MS).map { it.toInt() }

    companion object {
        /** The longest stretch of session time with no data that is not a hole: 90 minutes. */
        const val MAX_GAP_MS: Long = DataQualityPolicy.DEFAULT_MAX_GAP_MINUTES * 60_000L

        /** The longest late open or early close accepted as a short session: 6 hours (holiday closes run 3-4). */
        const val MAX_SHORT_MS: Long = 6 * HOUR_MS

        internal fun startMs(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
