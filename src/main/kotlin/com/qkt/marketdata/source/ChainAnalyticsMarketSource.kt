package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.ChainAnalytics
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.ChainSnapshotStore
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * Chain analytics streams (`CHAIN:<VENUE>.<ROOT>.<metric>.<tenor>`): one observation per stored
 * snapshot of the root's declared chain series, valued by [ChainAnalytics] where the metric is
 * defined (an instant where it is not has no tick), and one flat candle per observation for warmup.
 */
class ChainAnalyticsMarketSource(
    private val instruments: InstrumentRegistry,
) : MarketSource {
    override val name: String = "chain-analytics"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

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

    private fun observations(
        symbol: String,
        range: TimeRange,
    ): Sequence<Pair<Long, BigDecimal>> {
        val stream = ChainAnalyticsSymbol.parse(symbol).getOrThrow()
        val options = instruments.options()
        val root =
            options?.root(stream.root)
                ?: error("${stream.root} of $symbol is not declared under options: in instruments.yaml")
        val series = root.chains ?: error("${stream.root} of $symbol declares no chain series (chains: trade | book)")
        val dataRoot = requireNotNull(options.dataRoot) { "${stream.root} of $symbol has no chain data root" }
        val listings = options.listings(root.root)
        val store = ChainSnapshotStore(dataRoot, series)
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        val maxAgeMs = root.maxQuoteAgeMinutes * MS_PER_MINUTE
        return generateSequence(range.from.atZone(ZoneOffset.UTC).toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(range.to.atZone(ZoneOffset.UTC).toLocalDate()) }
            .flatMap { day -> store.readDay(root.root, day).asSequence() }
            .filter { it.atMs in fromMs until toMs }
            .mapNotNull { snapshot ->
                ChainAnalytics.value(stream.metric, stream.tenorDays, snapshot, listings, maxAgeMs)?.let {
                    snapshot.atMs to BigDecimal.valueOf(it).setScale(Money.SCALE, Money.ROUNDING)
                }
            }
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
