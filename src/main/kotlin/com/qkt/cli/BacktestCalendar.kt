package com.qkt.cli

import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.isOptionFeed
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.futuresCalendar

/**
 * The one calendar a strategy's pipeline runs on, in backtest and live alike: the futures roots'
 * shared `calendar:` when every symbol is futures, else [calendarOf] the first symbol that is not a
 * read-only option feed, else crypto (a strategy reading only option feeds watches a venue that trades
 * around the clock). Mixed-class baskets keep the first-symbol limitation (divergence catalog row A9).
 */
internal fun strategyCalendar(
    symbols: List<String>,
    instruments: InstrumentRegistry?,
    calendarOf: (String) -> TradingCalendar,
): TradingCalendar =
    instruments?.futuresCalendar(symbols)
        ?: symbols.firstOrNull { !isOptionFeed(it) }?.let(calendarOf)
        ?: TradingCalendar.crypto()

/**
 * A backtest's [strategyCalendar]: a symbol's live default calendar, crypto for an option contract
 * (its venue trades around the clock), so session and range indicators agree with live.
 */
internal fun backtestCalendar(
    symbols: List<String>,
    instruments: InstrumentRegistry,
): TradingCalendar =
    strategyCalendar(symbols, instruments) { first ->
        if (instruments.options()?.optionRoot(first) != null) {
            TradingCalendar.crypto()
        } else {
            BacktestContext.defaultCalendars().calendarFor(first.substringAfter(':'))
        }
    }
