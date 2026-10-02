package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.derivatives.options.chain.ChainView
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * Chain analytics streams (`CHAIN:<VENUE>.<ROOT>.<metric>.<tenor>`): one observation per stored
 * snapshot of the root's declared chain series, valued by [ChainAnalytics] where the metric is
 * defined (an instant where it is not has no tick), and one flat candle per observation for warmup.
 * Live, the store is read every [pollMs] and each snapshot newer than the last seen gives one tick
 * (a live session's chain recorder appends them).
 */
class ChainAnalyticsMarketSource(
    private val instruments: InstrumentRegistry,
    private val clock: Clock = SystemClock(),
    private val pollMs: Long = 5_000,
) : MarketSource {
    override val name: String = "chain-analytics"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS, MarketSourceCapability.LIVE_TICKS)

    override fun supports(symbol: String): Boolean = symbol.startsWith(ChainAnalyticsSymbol.PREFIX)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = observations(symbol, range).map { (at, value) -> Tick(symbol, value, at) }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        observations(symbol, range).map { (at, v) ->
            Candle(symbol, v, v, v, v, Money.ZERO, at, at + window.durationMs)
        }

    override fun liveTicks(symbols: List<String>): TickFeed {
        val streams = symbols.associateWith { declared(it) }
        return LiveTickFeed(ChainAnalyticsLiveSource(streams, ChainView(instruments), clock, pollMs))
    }

    private fun observations(
        symbol: String,
        range: TimeRange,
    ): Sequence<Pair<Long, BigDecimal>> {
        val stream = declared(symbol)
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        val store = ChainSnapshotStore(stream.dataRoot, stream.series)
        return generateSequence(range.from.atZone(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(range.to.atZone(ZoneOffset.UTC).toLocalDate()) }
            .flatMap { day -> store.readDay(stream.root.root, day).asSequence() }
            .filter { it.atMs in fromMs until toMs }
            .mapNotNull { snapshot -> stream.value(snapshot)?.let { snapshot.atMs to it } }
    }

    /** [symbol]'s stream on its declared root; fails naming what `instruments.yaml` lacks. */
    private fun declared(symbol: String): DeclaredChainStream {
        val stream = ChainAnalyticsSymbol.parse(symbol).getOrThrow()
        val options = instruments.options()
        val root =
            options?.root(stream.root)
                ?: error("${stream.root} of $symbol is not declared under options: in instruments.yaml")
        val series = root.chains ?: error("${stream.root} of $symbol declares no chain series (chains: trade | book)")
        val dataRoot = requireNotNull(options.dataRoot) { "${stream.root} of $symbol has no chain data root" }
        return DeclaredChainStream(stream, root, series, dataRoot, options.listings(root.root))
    }
}
