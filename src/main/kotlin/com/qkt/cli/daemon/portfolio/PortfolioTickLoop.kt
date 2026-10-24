package com.qkt.cli.daemon.portfolio

import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.MarketSource

/**
 * The portfolio supervisor's market-data loop. Candles of the portfolio's own streams ([gateSymbols] at [gateWindow])
 * drive the gate through [onGateCandle]. The book is sampled through [sample] once per candle boundary: on the
 * [bookFeed] when the portfolio has one (a book controller sizes or rebalances from it, so it samples as the backtest
 * does), else on the gate candles, as before. Each tick reaches the gate before the book, so a sample sees the
 * current bar's regime. Runs on the supervisor thread until [running] turns false or the feed ends.
 */
internal class PortfolioTickLoop(
    private val source: MarketSource,
    private val gateSymbols: List<String>,
    private val gateWindow: TimeWindow?,
    private val bookFeed: BookFeed?,
    private val onGateCandle: (Candle) -> Unit,
    private val sample: (Long) -> Unit,
) {
    /** Every symbol the loop subscribes to. */
    val symbols: List<String> = (gateSymbols + bookFeed?.symbols.orEmpty()).distinct()

    fun run(running: () -> Boolean) {
        if (symbols.isEmpty()) return
        val gateSet = gateSymbols.toSet()
        val gate =
            gateWindow?.takeIf { gateSet.isNotEmpty() }?.let { window ->
                CandleAggregator.standalone(window, aggregates = { it in gateSet }) { candle ->
                    onGateCandle(candle)
                    if (bookFeed == null) sample(candle.endTime)
                }
            }
        val book =
            bookFeed?.let { feed ->
                val bookSet = feed.symbols.toSet()
                CandleAggregator.standalone(feed.window, aggregates = { it in bookSet }) { sample(it.endTime) }
            }
        val ticks = source.liveTicks(symbols)
        try {
            while (running()) {
                val tick = ticks.next() ?: break
                gate?.onTick(tick)
                book?.onTick(tick)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { ticks.close() }
        }
    }
}
