package com.qkt.cli.daemon.portfolio

import com.qkt.candles.TimeWindow
import com.qkt.derivatives.options.chain.isOptionFeed
import com.qkt.dsl.portfolio.PortfolioCompiled

/**
 * What a portfolio's book controller samples on: once per candle boundary of [window] on any of [symbols]' bars.
 * The backtest samples a book exactly so (the run's candle window is the first child's first stream, and every
 * traded symbol forms candles at it), so live sampling on the same feed sizes `ALLOCATE ... TARGET VOL` and counts
 * `REBALANCE EVERY` as the backtest does (#1490).
 */
data class BookFeed(
    val symbols: List<String>,
    val window: TimeWindow,
)

/** This portfolio's [BookFeed]: every child's traded symbols at the first child's first timeframe; null with none. */
fun PortfolioCompiled.bookFeed(): BookFeed? {
    val streams = children.flatMap { it.ast.streams }
    val window = streams.firstOrNull()?.timeframe?.let(TimeWindow::parse) ?: return null
    val symbols = streams.map { it.qktSymbol }.filterNot(::isOptionFeed).distinct()
    return if (symbols.isEmpty()) null else BookFeed(symbols, window)
}
