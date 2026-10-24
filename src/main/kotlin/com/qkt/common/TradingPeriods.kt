package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/** Which instants a sampler that watches several symbols samples: when all of them trade, or when any does. */
enum class SessionOverlap {
    /** A sample needs every symbol in session, as the run's equity curve waits for every symbol's bar to close. */
    ALL,

    /** A sample needs one symbol in session, as the book samplers sample on any symbol's bar. */
    ANY,
}

/**
 * How many candle-window samples a year a sampler watching several symbols takes, which annualizes its per-sample
 * returns. One calendar answers with its own [TradingCalendar.tradingPeriodsPerYear]. Several are combined over a
 * reference span: when the combined session ([SessionOverlap.ALL] intersects, [SessionOverlap.ANY] unions) equals one
 * member's session, that member's own count is used, so FX + crypto overlapping is exactly the FX count and FX + NYSE
 * in union exactly the FX count. Otherwise the count is measured: the candle-grid bars the combined session touches
 * over [REFERENCE_FROM]..[REFERENCE_TO], divided by [REFERENCE_YEARS]. An empty overlap (no instant where all trade)
 * samples nothing, and falls back to the smallest member count.
 *
 * Sessions are read at five-minute resolution, which is exact for calendars whose sessions open and close on
 * five-minute marks, as every built-in one does (pinned by `TradingCalendarCompletenessTest`). A calendar's session is
 * assumed not to depend on the symbol, as holds for every built-in calendar.
 */
object TradingPeriods {
    /** First day of the reference span. */
    val REFERENCE_FROM: LocalDate = LocalDate.of(2021, 1, 1)

    /** Day after the reference span: 1,461 days, exactly four 365.25-day years. */
    val REFERENCE_TO: LocalDate = LocalDate.of(2025, 1, 1)

    /** Years in the reference span. */
    const val REFERENCE_YEARS: Int = 4

    private const val STEP_MS: Long = 5 * 60_000L
    private val fromMs = REFERENCE_FROM.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val steps =
        (
            (
                REFERENCE_TO
                    .atStartOfDay(
                        ZoneOffset.UTC,
                    ).toInstant()
                    .toEpochMilli() - fromMs
            ) / STEP_MS
        ).toInt()
    private val masks = ConcurrentHashMap<TradingCalendar, BitSet>()

    /** Samples a year at [window] for a sampler watching each symbol of [calendarsBySymbol] under its own calendar. */
    fun perYear(
        calendarsBySymbol: Map<String, TradingCalendar>,
        window: TimeWindow,
        overlap: SessionOverlap,
    ): BigDecimal {
        require(calendarsBySymbol.isNotEmpty()) { "trading periods need at least one symbol" }
        val members = calendarsBySymbol.entries.distinctBy { it.value }
        if (members.size == 1) return members.single().value.tradingPeriodsPerYear(window)
        val memberMasks = members.map { (symbol, calendar) -> calendar to maskOf(calendar, symbol) }
        val combined = memberMasks.first().second.clone() as BitSet
        for ((_, mask) in memberMasks.drop(1)) {
            if (overlap == SessionOverlap.ALL) combined.and(mask) else combined.or(mask)
        }
        memberMasks.firstOrNull { it.second == combined }?.let { return it.first.tradingPeriodsPerYear(window) }
        if (combined.isEmpty) return members.minOf { it.value.tradingPeriodsPerYear(window) }
        return BigDecimal(barsTouched(combined, window)).divide(BigDecimal(REFERENCE_YEARS), Money.CONTEXT)
    }

    private fun maskOf(
        calendar: TradingCalendar,
        symbol: String,
    ): BitSet =
        masks.computeIfAbsent(calendar) {
            val mask = BitSet(steps)
            for (i in 0 until steps) {
                if (calendar.isInSession(symbol, java.time.Instant.ofEpochMilli(fromMs + i * STEP_MS))) mask.set(i)
            }
            mask
        }

    private fun barsTouched(
        session: BitSet,
        window: TimeWindow,
    ): Long {
        var bars = 0L
        var lastBar = Long.MIN_VALUE
        var i = session.nextSetBit(0)
        while (i >= 0) {
            val start = fromMs + i * STEP_MS
            val first = maxOf(Math.floorDiv(start, window.durationMs), lastBar + 1)
            val last = Math.floorDiv(start + STEP_MS - 1, window.durationMs)
            if (last >= first) {
                bars += last - first + 1
                lastBar = last
            }
            i = session.nextSetBit(i + 1)
        }
        return bars
    }
}
