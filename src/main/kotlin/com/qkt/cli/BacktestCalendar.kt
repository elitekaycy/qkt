package com.qkt.cli

import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.futuresCalendar

/**
 * The one calendar a backtest's pipeline runs on: the futures roots' shared `calendar:` when every
 * symbol is futures, else the first symbol's live default calendar (crypto when there are none), so
 * session and range indicators agree with live. Mixed-class baskets keep the first-symbol limitation
 * (divergence catalog row A9).
 */
internal fun backtestCalendar(
    symbols: List<String>,
    instruments: InstrumentRegistry,
): TradingCalendar =
    instruments.futuresCalendar(symbols)
        ?: symbols.firstOrNull()?.let { BacktestContext.defaultCalendars().calendarFor(it.substringAfter(':')) }
        ?: TradingCalendar.crypto()
