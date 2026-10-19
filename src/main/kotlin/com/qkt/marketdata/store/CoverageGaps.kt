package com.qkt.marketdata.store

import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A half-open stretch of epoch milliseconds. */
internal data class Span(
    val fromMs: Long,
    val toMs: Long,
) {
    val lengthMs: Long get() = toMs - fromMs

    fun overlaps(other: Span): Boolean = fromMs < other.toMs && other.fromMs < toMs

    fun intersect(other: Span): Span? {
        val from = maxOf(fromMs, other.fromMs)
        val to = minOf(toMs, other.toMs)
        return if (from < to) Span(from, to) else null
    }
}

/** One calendar session in the checked window: [trading] when it should hold data, [holiday] when a holiday closes it. */
internal data class CoverageSession(
    val span: Span,
    val trading: Boolean,
    val holiday: Boolean,
)

/** What an empty stretch inside trading time means for coverage. */
internal enum class FindingKind { HOLE, EARLY_CLOSE, LATE_OPEN }

/**
 * One empty stretch of trading time. [dataBefore]/[dataAfter] say whether the checked window holds data on
 * either side of it, [storedLater] whether the store holds any after the window: a stretch with nothing after it
 * is a truncated store, which no short session excuses.
 */
internal data class Finding(
    val span: Span,
    val kind: FindingKind,
    val dataBefore: Boolean,
    val dataAfter: Boolean,
    val storedLater: Boolean,
    /** The UTC days a hole is reported on: those holding more than the gap threshold of it (all it touches when none does). */
    val days: Set<LocalDate> = emptySet(),
)

/**
 * The sessions of [calendar] for [symbol] that overlap [window], in order. A session is trading when the
 * calendar has it in session and does not close it for a holiday ([TradingCalendar.isClosedSession]).
 */
internal fun coverageSessions(
    calendar: TradingCalendar,
    symbol: String,
    window: Span,
): List<CoverageSession> {
    val out = LinkedHashSet<CoverageSession>()
    var t = window.fromMs
    while (t < window.toMs) {
        val range = calendar.sessionRange(symbol, Instant.ofEpochMilli(t))
        val span = Span(range.from.toEpochMilli(), range.to.toEpochMilli())
        if (span.overlaps(window)) {
            val open =
                listOf(span.fromMs, span.fromMs + span.lengthMs / 2, span.toMs - 1).any {
                    calendar.isInSession(symbol, Instant.ofEpochMilli(it))
                }
            val closed = open && calendar.isClosedSession(symbol, TimeRange(range.from, range.to))
            out += CoverageSession(span, trading = open && !closed, holiday = closed)
        }
        t = if (span.toMs > t) span.toMs else t + HOUR_MS
    }
    return out.toList()
}

/**
 * The findings of the empty stretch [gap] between stored runs, one per session part of it. Parts longer than
 * [gapMs] matter; shorter ones are routine (a daily break, a quiet open). A part is a short session, not a
 * hole, when it is an early close (the gap starts inside a session and runs to its close, with data before it
 * and data stored after) or a late open (it starts at a session's open and ends at its first data), at most
 * [maxShortMs] long. A whole session inside the gap is always a hole. On a 24/7 calendar sessions only cut the
 * day, so the gap counts whole.
 */
internal fun classifyGap(
    gap: Span,
    dataBefore: Boolean,
    dataAfter: Boolean,
    storedLater: Boolean,
    sessions: List<CoverageSession>,
    calendar: TradingCalendar,
    gapMs: Long,
    maxShortMs: Long,
): List<Finding> {
    val pieces = sessions.filter { it.trading }.mapNotNull { s -> gap.intersect(s.span)?.let { it to s.span } }
    val big = pieces.filter { (p, _) -> p.lengthMs > gapMs }
    if (!calendar.hasShortSessions) {
        // Sessions of a 24/7 market only cut the day: the gap counts whole, across midnight too.
        if (pieces.sumOf { it.first.lengthMs } <= gapMs) return emptyList()
        return pieces.map { (p, _) -> Finding(p, FindingKind.HOLE, dataBefore, dataAfter, storedLater) }
    }
    if (big.isEmpty()) return emptyList()
    return big.map { (p, s) ->
        val earlyClose =
            p.toMs == s.toMs &&
                p.fromMs > s.fromMs &&
                p.fromMs == gap.fromMs &&
                dataBefore &&
                (dataAfter || storedLater)
        val lateOpen = p.fromMs == s.fromMs && p.toMs < s.toMs && p.toMs == gap.toMs && dataAfter
        val kind =
            when {
                p.lengthMs > maxShortMs -> FindingKind.HOLE
                earlyClose -> FindingKind.EARLY_CLOSE
                lateOpen -> FindingKind.LATE_OPEN
                else -> FindingKind.HOLE
            }
        Finding(p, kind, dataBefore, dataAfter, storedLater)
    }
}

/**
 * [found] with each hole assigned the days it is reported on: a hole that runs from a complete day's last
 * hour into an empty next day belongs to the empty day, not the complete one.
 */
internal fun chargeHoles(
    found: List<Finding>,
    gapMs: Long,
): List<Finding> {
    val holes = found.filter { it.kind == FindingKind.HOLE }
    if (holes.isEmpty()) return found
    val perDay =
        holes
            .flatMap { f -> utcDays(f.span).mapNotNull { d -> f.span.intersect(daySpan(d))?.let { d to it.lengthMs } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, parts) -> parts.sum() }
    val big = perDay.filterValues { it > gapMs }.keys
    val days = big.ifEmpty { perDay.keys }
    return found.map { if (it.kind == FindingKind.HOLE) it.copy(days = days) else it }
}

internal fun daySpan(day: LocalDate): Span {
    val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    return Span(start, start + DAY_MS)
}

private fun utcDays(span: Span): List<LocalDate> {
    val first = Instant.ofEpochMilli(span.fromMs).atZone(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochMilli(span.toMs - 1).atZone(ZoneOffset.UTC).toLocalDate()
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
}

internal const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
