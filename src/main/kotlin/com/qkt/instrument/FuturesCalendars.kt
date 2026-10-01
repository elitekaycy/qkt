package com.qkt.instrument

import com.qkt.common.TradingCalendar

/**
 * The calendar a run of [symbols] trades on when every one of them is a futures stream or contract
 * whose root names the same `calendar:`; null otherwise (any other symbol, a root without a
 * calendar, or roots that disagree), leaving the run to its usual resolution.
 */
fun InstrumentRegistry.futuresCalendar(symbols: Collection<String>): TradingCalendar? {
    if (symbols.isEmpty()) return null
    val directory = futures() ?: return null
    val names =
        symbols.map { symbol ->
            val terms = lookup(symbol)?.derivative as? FutureTerms ?: return null
            directory.root(terms.root)?.calendar ?: return null
        }
    return names.distinct().singleOrNull()?.let(TradingCalendar::named)
}
