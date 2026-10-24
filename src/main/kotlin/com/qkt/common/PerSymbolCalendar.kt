package com.qkt.common

import com.qkt.candles.TimeWindow
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A run's calendar when its symbols may trade on different calendars: every question that names a symbol is answered
 * by that symbol's own calendar ([calendarOf], resolved once per symbol), so a DAY order on BTCUSD expires at the end
 * of the crypto day while one on EURUSD expires at 17:00 New York, whichever stream the strategy lists first. The
 * symbol-free anchor questions answer for [primary], the run's first symbol.
 *
 * [tradingPeriodsPerYear] counts the samples of a sampler that waits for every one of [runSymbols]
 * ([SessionOverlap.ALL]); a caller that knows exactly which symbols its sampler watches uses [TradingPeriods] with
 * them instead.
 */
class PerSymbolCalendar(
    private val primary: TradingCalendar,
    /** The run's own symbols, resolved up front; other symbols (conversion feeds) resolve on first use. */
    override val runSymbols: List<String>,
    private val calendarOf: (String) -> TradingCalendar,
) : TradingCalendar {
    private val resolved = ConcurrentHashMap<String, TradingCalendar>()

    init {
        runSymbols.forEach(::forSymbol)
    }

    override val name: String get() = primary.name

    override fun forSymbol(symbol: String): TradingCalendar =
        resolved.computeIfAbsent(symbol) {
            calendarOf(it).forSymbol(it)
        }

    override fun isInSession(
        symbol: String,
        t: Instant,
    ): Boolean = forSymbol(symbol).isInSession(symbol, t)

    override fun sessionRange(
        symbol: String,
        t: Instant,
    ): TimeRange = forSymbol(symbol).sessionRange(symbol, t)

    override fun isScheduledBreak(
        symbol: String,
        t: Instant,
    ): Boolean = forSymbol(symbol).isScheduledBreak(symbol, t)

    override fun isClosedSession(
        symbol: String,
        session: TimeRange,
    ): Boolean = forSymbol(symbol).isClosedSession(symbol, session)

    override fun anchorEpochFor(
        anchor: SessionAnchor,
        t: Instant,
    ): Long = primary.anchorEpochFor(anchor, t)

    override fun rangeFor(
        anchor: SessionAnchor,
        anchorEpoch: Long,
    ): TimeRange = primary.rangeFor(anchor, anchorEpoch)

    override fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal =
        if (runSymbols.isEmpty()) {
            primary.tradingPeriodsPerYear(window)
        } else {
            TradingPeriods.perYear(runSymbols.associateWith(::forSymbol), window, SessionOverlap.ALL)
        }

    override val hasShortSessions: Boolean get() = primary.hasShortSessions

    override fun toString(): String =
        "PerSymbolCalendar(primary=${primary.name}, resolved=${resolved.mapValues { it.value.name }})"
}
