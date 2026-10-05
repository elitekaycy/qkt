package com.qkt.marketdata.openinterest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability

/**
 * The open-interest streams (`OI:<VENUE>:<NAME>`, [OpenInterestSymbol]) over [source]: the stored series in a
 * backtest, the account's gateway live. Each figure is one tick stamped at the instant it became known, so a
 * strategy sees it then and never earlier, the same in both modes. Live, figures are polled every [pollMs]
 * ([OpenInterestPoll]); the first read happens when the feed starts, so a contract whose venue serves no open
 * interest (a gateway that does not declare `open_interest`) fails the deploy instead of reading as missing.
 */
class OpenInterestMarketSource(
    private val source: OpenInterestSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = OpenInterestPoll.DEFAULT_POLL_MS,
) : MarketSource {
    override val name: String = "OpenInterest"

    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS, MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String): Boolean = OpenInterestSymbol.contract(symbol) != null

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> =
        figures(symbol, range).map { Tick(symbol, it.openInterest.setScale(Money.SCALE, Money.ROUNDING), it.timeMs) }

    /** Warmup history: each figure as a flat candle opening at the instant it became known. */
    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        figures(symbol, range).map {
            val value = it.openInterest.setScale(Money.SCALE, Money.ROUNDING)
            Candle(symbol, value, value, value, value, Money.ZERO, it.timeMs, it.timeMs + window.durationMs)
        }

    override fun liveTicks(symbols: List<String>): TickFeed {
        val nowMs = clock()
        val first = symbols.associateWith { source.figures(contract(it), nowMs - pollMs, nowMs) }
        return LiveTickFeed(OpenInterestPoll(source, first, clock, pollMs), reconnectBudgetMs = Long.MAX_VALUE / 4)
    }

    private fun figures(
        symbol: String,
        range: TimeRange,
    ): Sequence<OpenInterest> {
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return source.figures(contract(symbol), fromMs, toMs - 1).filter { it.timeMs in fromMs until toMs }.asSequence()
    }

    private fun contract(symbol: String): String =
        requireNotNull(
            OpenInterestSymbol.contract(symbol),
        ) { "$symbol is not an open-interest stream (OI:<VENUE>:<NAME>)" }
}
