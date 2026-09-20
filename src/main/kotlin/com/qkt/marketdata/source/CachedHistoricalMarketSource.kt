package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import org.slf4j.LoggerFactory

/**
 * De-duplicates short-lived historical bar reads for shared live market sources.
 *
 * Live portfolio children can start close together and request the same warmup bars through
 * different broker namespaces. Prefix remapping translates those requests to the canonical MT5
 * symbol before they reach this wrapper, so a single upstream history read can safely serve every
 * sibling namespace while each caller still receives its own restamped candles from
 * [PrefixRemapMarketSource].
 */
class CachedHistoricalMarketSource(
    private val delegate: MarketSource,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: Clock = SystemClock(),
) : MarketSource,
    AutoCloseable {
    init {
        require(ttlMs >= 0L) { "bar cache ttl must be non-negative" }
        require(maxEntries > 0) { "max cached bar requests must be positive" }
    }

    override val name: String
        get() = delegate.name

    override val capabilities: Set<MarketSourceCapability>
        get() = delegate.capabilities

    private val requests = SharedBarRequests(delegate, ttlMs, maxEntries, clock, logger)

    override fun supports(symbol: String): Boolean = delegate.supports(symbol)

    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> = delegate.capabilitiesFor(symbol)

    override fun liveTicks(symbols: List<String>): TickFeed = delegate.liveTicks(symbols)

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val key =
            BarRequest(
                symbol = symbol,
                windowMs = window.durationMs,
                fromMs = range.from.toEpochMilli(),
                toMs = range.to.toEpochMilli(),
            )
        return requests.loadOrJoin(key, symbol, window, range).asSequence()
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> = delegate.ticks(symbol, range)

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> = delegate.tickSlice(symbol, fromMs, toMs)

    override fun close() {
        requests.clear()
        (delegate as? AutoCloseable)?.close()
    }

    private companion object {
        private const val DEFAULT_TTL_MS = 30_000L
        private const val DEFAULT_MAX_ENTRIES = 256
        private val logger = LoggerFactory.getLogger(CachedHistoricalMarketSource::class.java)
    }
}
