package com.qkt.cli.daemon.portfolio

import com.qkt.cli.strategyCalendar
import com.qkt.common.SessionOverlap
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * The live book controller's samples a year: it samples once per candle boundary on any of the portfolio's
 * [bookFeed] symbols' bars (every child's traded symbols at the first child's timeframe), so the count is the union
 * of those symbols' sessions at that window, each on its own calendar: what the backtest's
 * [com.qkt.research.ReplayRisk] counts for the same book (#1490), crypto's count when the children read only option
 * feeds, and 252 when no child declares a stream.
 */
internal fun bookAnnualization(
    compiled: PortfolioCompiled,
    instruments: InstrumentRegistry?,
    calendarOf: (String) -> TradingCalendar,
): BigDecimal {
    val feed = compiled.bookFeed() ?: return BigDecimal("252")
    val book = strategyCalendar(feed.symbols, instruments, calendarOf)
    return book.periodsPerYear(book.runSymbols, feed.window, SessionOverlap.ANY)
}
