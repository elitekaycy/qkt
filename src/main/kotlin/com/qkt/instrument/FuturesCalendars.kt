package com.qkt.instrument

import com.qkt.common.TradingCalendar

/**
 * The calendar a run of [symbols] trades on when every one of them is a futures stream or contract
 * whose root names the same `calendar:`; null otherwise (any other symbol, a root without a
 * calendar, or roots that disagree), leaving the run to its usual resolution.
 */
fun InstrumentRegistry.futuresCalendar(symbols: Collection<String>): TradingCalendar? {
    if (symbols.isEmpty()) return null
    val calendars = symbols.map { futuresRootCalendar(it) ?: return null }
    return calendars.distinct().singleOrNull()
}

/** The `calendar:` of [symbol]'s futures root when [symbol] is a futures stream or contract whose root names one. */
fun InstrumentRegistry.futuresRootCalendar(symbol: String): TradingCalendar? {
    val directory = futures() ?: return null
    val terms = lookup(symbol)?.derivative as? FutureTerms ?: return null
    return directory.root(terms.root)?.calendar?.let(TradingCalendar::named)
}
