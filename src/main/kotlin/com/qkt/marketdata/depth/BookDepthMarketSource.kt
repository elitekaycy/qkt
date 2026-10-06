package com.qkt.marketdata.depth

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
 * The depth streams (`DEPTH:<SIDE>:<VENUE>:<NAME>`, [BookDepthSymbol]) over [source]: the stored series in a
 * backtest, the account's gateway live. Each snapshot is one tick of each stream read, stamped at the instant
 * the venue stamped the book, so a strategy sees it then and never earlier, the same in both modes. Live,
 * each contract is read once every [pollMs] ([BookDepthPoll]) however many of its streams are read, so a
 * strategy's bid and ask depth and imbalance always come from one snapshot. The first read happens when the
 * feed starts, so a contract whose venue serves no depth (a gateway that does not declare `depth`) fails the
 * deploy instead of reading as missing.
 */
class BookDepthMarketSource(
    private val source: BookDepthSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = BookDepthPoll.DEFAULT_POLL_MS,
) : MarketSource {
    override val name: String = "BookDepth"

    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS, MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String): Boolean = BookDepthSymbol.contract(symbol) != null

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = snapshots(symbol, range).map { tick(symbol, it) }

    /** Warmup history: each snapshot's value as a flat candle opening at the instant the venue stamped it. */
    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        snapshots(symbol, range).map {
            val value = tick(symbol, it).price
            Candle(symbol, value, value, value, value, Money.ZERO, it.timeMs, it.timeMs + window.durationMs)
        }

    override fun liveTicks(symbols: List<String>): TickFeed {
        val nowMs = clock()
        val streams = symbols.groupBy { contract(it) }
        val first = streams.keys.associateWith { source.snapshots(it, nowMs - pollMs, nowMs + pollMs) }
        return LiveTickFeed(
            BookDepthPoll(source, streams, first, clock, pollMs),
            reconnectBudgetMs = Long.MAX_VALUE / 4,
        )
    }

    private fun snapshots(
        symbol: String,
        range: TimeRange,
    ): Sequence<BookDepth> {
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        return source.sequence(contract(symbol), fromMs, toMs - 1).filter { it.timeMs in fromMs until toMs }
    }

    private fun contract(symbol: String): String =
        requireNotNull(BookDepthSymbol.contract(symbol)) {
            "$symbol is not a depth stream (DEPTH:<BID|ASK|IMBALANCE>:<VENUE>:<NAME>)"
        }

    internal companion object {
        /** [symbol]'s value of [depth] as a tick at the instant the venue stamped the book. */
        fun tick(
            symbol: String,
            depth: BookDepth,
        ) = Tick(symbol, BookDepthSymbol.value(symbol, depth).setScale(Money.SCALE, Money.ROUNDING), depth.timeMs)
    }
}
