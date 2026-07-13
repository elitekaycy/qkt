package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.macro.MacroSeriesFeed
import com.qkt.marketdata.store.macro.MacroSeriesStore
import com.qkt.marketdata.store.macro.PolicyRateSeries
import com.qkt.marketdata.store.macro.PolicyRateSeriesFetcher
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * A [MarketSource] for daily macro series under the `MACRO:` prefix (e.g. `MACRO:DFII10`). Reads the
 * [MacroSeriesStore] and replays each value point-in-time via [MacroSeriesFeed] — stamped at its
 * publication instant, not its observation date — so a backtest never sees a value before it was
 * knowable. Composed alongside the tick source via [CompositeMarketSource]: `MACRO:` symbols route
 * here, everything else falls through to the tick store.
 *
 * Tick-only: [CandleHub][com.qkt.dsl.compile.CandleHub] closes each published macro observation as
 * an event candle immediately, so `.value` changes at its availability timestamp instead of one
 * observation later. The declared timeframe remains the candle duration used by history APIs.
 */
class MacroMarketSource(
    private val store: MacroSeriesStore,
    private val lagBusinessDays: Int = 1,
    private val releaseUtcHour: Int = 13,
    private val clock: Clock = SystemClock(),
    private val policyRateFetcher: PolicyRateSeriesFetcher = PolicyRateSeriesFetcher(store),
    private val livePollIntervalMs: Long = 15 * 60 * 1_000L,
) : MarketSource {
    override val name: String = "Macro"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.TICKS, MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)

    override fun supports(symbol: String): Boolean = symbol.startsWith("MACRO:")

    override fun liveTicks(symbols: List<String>): TickFeed {
        val resolved =
            symbols.associateWith { symbol ->
                require(symbol.startsWith("MACRO:")) { "MacroMarketSource cannot serve $symbol" }
                PolicyRateSeries.fromId(symbol.substringAfter(':'))
                    ?: error("live macro series $symbol is unsupported; use a cataloged policy-rate series")
            }
        return PolicyRateLiveFeed(
            symbols = resolved,
            store = store,
            clock = clock,
            pollIntervalMs = livePollIntervalMs,
            refresh = { series, from, to -> policyRateFetcher.fetch(series, from, to) },
        )
    }

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
    ): Sequence<Candle> {
        val seriesId = symbol.substringAfter(':')
        val policySeries =
            PolicyRateSeries.fromId(seriesId)
                ?: throw UnsupportedDataException(MarketSourceCapability.BARS, "uncataloged macro series $symbol")
        val fromDate = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val toDate = range.to.atZone(ZoneOffset.UTC).toLocalDate()
        val requiredThrough = maxOf(fromDate, toDate.minusDays(4))
        if (!store.hasRange(seriesId, fromDate, requiredThrough)) {
            policyRateFetcher.fetch(policySeries, fromDate, toDate)
        }
        return store
            .read(seriesId, fromDate, toDate)
            .asSequence()
            .mapNotNull { point ->
                val timestamp = point.availableAtMs ?: return@mapNotNull null
                if (timestamp !in range.from.toEpochMilli() until range.to.toEpochMilli()) return@mapNotNull null
                Candle(
                    symbol = symbol,
                    open = point.value,
                    high = point.value,
                    low = point.value,
                    close = point.value,
                    volume = BigDecimal.ZERO,
                    startTime = timestamp,
                    endTime = timestamp + window.durationMs,
                )
            }
    }
}
