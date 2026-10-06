package com.qkt.cli

/**
 * The trading calendar for [qktSymbol]: its account's trading hours when an account serves the
 * prefix, otherwise the backtest defaults (e.g. `PAPER:SPX` resolves to NYSE hours).
 */
internal fun liveCalendarFor(
    qktSymbol: String,
    accounts: com.qkt.connectivity.AccountDirectory,
): com.qkt.common.TradingCalendar =
    accounts.tradingHoursFor(qktSymbol)
        ?: BacktestContext.defaultCalendars().calendarFor(qktSymbol.substringAfter(':'))
