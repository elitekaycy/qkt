package com.qkt.cli

import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.futuresCalendar

/**
 * The one calendar a backtest's pipeline runs on: the futures roots' shared `calendar:` when every
 * symbol is futures, else the first symbol's live default calendar (crypto when there are none, and
 * for an option contract, whose venue trades around the clock; chain analytics streams are skipped), so
 * session and range indicators agree with live. Mixed-class baskets keep the first-symbol limitation
 * (divergence catalog row A9).
 */
internal fun backtestCalendar(
    symbols: List<String>,
    instruments: InstrumentRegistry,
): TradingCalendar =
    instruments.futuresCalendar(symbols)
        ?: symbols.firstOrNull { !it.startsWith(ChainAnalyticsSymbol.PREFIX) }?.let { first ->
            if (instruments.options()?.optionRoot(first) != null) {
                TradingCalendar.crypto()
            } else {
                BacktestContext.defaultCalendars().calendarFor(first.substringAfter(':'))
            }
        }
        ?: TradingCalendar.crypto()
