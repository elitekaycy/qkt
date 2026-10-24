package com.qkt.cli

import com.qkt.common.PerSymbolCalendar
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.isOptionFeed
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.futuresRootCalendar

/**
 * The calendar a strategy's pipeline runs on, in backtest and live alike: a [PerSymbolCalendar] answering each
 * symbol's questions from that symbol's own calendar, a futures contract's root `calendar:` first, else
 * [calendarOf]. The symbol-free anchor questions answer for the first symbol that is not a read-only option feed,
 * or crypto when the strategy reads only option feeds (their venue trades around the clock).
 */
internal fun strategyCalendar(
    symbols: List<String>,
    instruments: InstrumentRegistry?,
    calendarOf: (String) -> TradingCalendar,
): TradingCalendar {
    val resolved = java.util.concurrent.ConcurrentHashMap<String, TradingCalendar>()
    val ofSymbol: (String) -> TradingCalendar = { symbol ->
        resolved.computeIfAbsent(symbol) { instruments?.futuresRootCalendar(it) ?: calendarOf(it) }
    }
    val traded = symbols.filterNot(::isOptionFeed)
    val primary = traded.firstOrNull()?.let(ofSymbol) ?: TradingCalendar.crypto()
    return PerSymbolCalendar(primary, traded, ofSymbol)
}

/**
 * A backtest's [strategyCalendar]: each symbol on the calendar [calendars] chooses for it, the rule live follows
 * ([CalendarResolver]), so session questions and annualization agree with live.
 */
internal fun backtestCalendar(
    symbols: List<String>,
    instruments: InstrumentRegistry,
    calendars: CalendarResolver,
): TradingCalendar = strategyCalendar(symbols, instruments, calendars::calendarFor)
