package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
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

    private val lock = Any()

    private val cache =
        object : LinkedHashMap<BarRequest, CachedBars>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BarRequest, CachedBars>?): Boolean =
                size > maxEntries
        }

    private val inFlight = mutableMapOf<BarRequest, CompletableFuture<List<Candle>>>()

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
        return loadOrJoin(key, symbol, window, range).asSequence()
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
        synchronized(lock) {
            cache.clear()
            inFlight.clear()
        }
        (delegate as? AutoCloseable)?.close()
    }

    private fun loadOrJoin(
        key: BarRequest,
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle> {
        cached(key)?.let { return it }

        val future: CompletableFuture<List<Candle>>
        val leader: Boolean
        synchronized(lock) {
            cachedLocked(key)?.let { return it }
            val existing = inFlight[key]
            if (existing == null) {
                future = CompletableFuture()
                inFlight[key] = future
                leader = true
            } else {
                future = existing
                leader = false
                logger.info(
                    "historical bar request joined source={} symbol={} windowMs={} fromMs={} toMs={}",
                    name,
                    key.symbol,
                    key.windowMs,
                    key.fromMs,
                    key.toMs,
                )
            }
        }

        return if (leader) {
            load(key, future, symbol, window, range)
        } else {
            await(future)
        }
    }

    private fun cached(key: BarRequest): List<Candle>? =
        synchronized(lock) {
            cachedLocked(key)
        }

    private fun cachedLocked(key: BarRequest): List<Candle>? {
        val cached = cache[key] ?: return null
        if (clock.now() <= cached.expiresAtMs) {
            logger.info(
                "historical bar cache hit source={} symbol={} windowMs={} fromMs={} toMs={} bars={}",
                name,
                key.symbol,
                key.windowMs,
                key.fromMs,
                key.toMs,
                cached.candles.size,
            )
            return cached.candles
        }
        cache.remove(key)
        return null
    }

    private fun load(
        key: BarRequest,
        future: CompletableFuture<List<Candle>>,
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): List<Candle> =
        try {
            val candles = delegate.bars(symbol, window, range).toList()
            synchronized(lock) {
                cache[key] = CachedBars(candles, clock.now() + ttlMs)
                inFlight.remove(key)
            }
            logger.info(
                "historical bar request loaded source={} symbol={} windowMs={} fromMs={} toMs={} bars={}",
                name,
                key.symbol,
                key.windowMs,
                key.fromMs,
                key.toMs,
                candles.size,
            )
            future.complete(candles)
            candles
        } catch (failure: Throwable) {
            synchronized(lock) {
                inFlight.remove(key)
            }
            future.completeExceptionally(failure)
            throw failure
        }

    private fun await(future: CompletableFuture<List<Candle>>): List<Candle> =
        try {
            future.get()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while waiting for shared bar request", failure)
        } catch (failure: ExecutionException) {
            val cause = failure.cause ?: failure
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("shared bar request failed", cause)
            }
        }

    private data class BarRequest(
        val symbol: String,
        val windowMs: Long,
        val fromMs: Long,
        val toMs: Long,
    )

    private data class CachedBars(
        val candles: List<Candle>,
        val expiresAtMs: Long,
    )

    private companion object {
        private const val DEFAULT_TTL_MS = 30_000L
        private const val DEFAULT_MAX_ENTRIES = 256
        private val logger = LoggerFactory.getLogger(CachedHistoricalMarketSource::class.java)
    }
}
