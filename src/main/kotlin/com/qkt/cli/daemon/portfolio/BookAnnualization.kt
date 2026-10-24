package com.qkt.cli.daemon.portfolio

import com.qkt.candles.TimeWindow
import com.qkt.cli.strategyCalendar
import com.qkt.common.SessionOverlap
import com.qkt.common.TradingCalendar
import com.qkt.derivatives.options.chain.isOptionFeed
import com.qkt.dsl.portfolio.PortfolioCompiled
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * The live book controller's samples a year: the supervisor samples the book once per candle boundary on any of the
 * portfolio's own streams (its aggregator runs at the first stream's timeframe), so the count is the union of those
 * symbols' sessions at that window, each on its own calendar. 252 when the portfolio declares no streams of its own,
 * whose book samples on the risk heartbeat instead (parity row A65), or reads only option feeds.
 */
internal fun bookAnnualization(
    compiled: PortfolioCompiled,
    instruments: InstrumentRegistry?,
    calendarOf: (String) -> TradingCalendar,
): BigDecimal {
    val stream = compiled.ast.streams.find { !isOptionFeed(it.qktSymbol) } ?: return BigDecimal("252")
    val book = strategyCalendar(compiled.ast.streams.map { it.qktSymbol }, instruments, calendarOf)
    return book.periodsPerYear(book.runSymbols, TimeWindow.parse(stream.timeframe), SessionOverlap.ANY)
}
