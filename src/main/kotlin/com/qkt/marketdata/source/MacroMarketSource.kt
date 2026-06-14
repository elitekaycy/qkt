package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.macro.MacroSeriesFeed
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.time.ZoneOffset

/**
 * A [MarketSource] for daily macro series under the `MACRO:` prefix (e.g. `MACRO:DFII10`). Reads the
 * [MacroSeriesStore] and replays each value point-in-time via [MacroSeriesFeed] — stamped at its
 * publication instant, not its observation date — so a backtest never sees a value before it was
 * knowable. Composed alongside the tick source via [CompositeMarketSource]: `MACRO:` symbols route
 * here, everything else falls through to the tick store.
 *
 * Tick-only: the engine's candle aggregator turns the daily ticks into `EVERY 1d` candles, so a
 * strategy reads the series through the normal candle path (`.close`, indicators).
 */
class MacroMarketSource(
    private val store: MacroSeriesStore,
    private val lagBusinessDays: Int = 1,
    private val releaseUtcHour: Int = 13,
) : MarketSource {
    override val name: String = "Macro"
    override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = symbol.startsWith("MACRO:")

    override fun liveTicks(symbols: List<String>): TickFeed =
        throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, this::class.java.simpleName!!)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val series = symbol.substringAfter(':')
        val fromMs = range.from.toEpochMilli()
        val toMs = range.to.toEpochMilli()
        // The release lag means an observation a few days before `from` can publish inside the
        // window, so read a short lookback before the range start; the feed re-filters by release.
        val fromDate =
            range.from
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .minusDays(10)
        val toDate = range.to.atZone(ZoneOffset.UTC).toLocalDate()
        val points = store.read(series, fromDate, toDate)
        val feed = MacroSeriesFeed(symbol, points, fromMs, toMs, lagBusinessDays, releaseUtcHour)
        return generateSequence { feed.next() }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> = throw UnsupportedDataException(MarketSourceCapability.BARS, this::class.java.simpleName!!)
}
