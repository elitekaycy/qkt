package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import org.slf4j.Logger

/**
 * Single-flight, TTL-bounded cache of historical bar reads for [CachedHistoricalMarketSource]:
 * the first caller of a [BarRequest] loads it from [delegate] while concurrent callers of the
 * same request wait on its result, and the loaded bars serve later callers for [ttlMs]. Holds
 * at most [maxEntries] requests (least recently used evicted). Logs under [logger].
 */
internal class SharedBarRequests(
    private val delegate: MarketSource,
    private val ttlMs: Long,
    maxEntries: Int,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val lock = Any()

    private val cache =
        object : LinkedHashMap<BarRequest, CachedBars>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BarRequest, CachedBars>?): Boolean =
                size > maxEntries
        }

    private val inFlight = mutableMapOf<BarRequest, CompletableFuture<List<Candle>>>()

    fun loadOrJoin(
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
                    delegate.name,
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
                delegate.name,
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
                delegate.name,
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

    /** Forget every cached read of [symbol] at [windowMs]; reads already in flight complete as they are. */
    fun forget(
        symbol: String,
        windowMs: Long,
    ) {
        synchronized(lock) { cache.keys.removeIf { it.symbol == symbol && it.windowMs == windowMs } }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            inFlight.clear()
        }
    }

    private data class CachedBars(
        val candles: List<Candle>,
        val expiresAtMs: Long,
    )
}

/** Identity of one historical bar read: symbol, bar width and the `[fromMs, toMs)` range. */
internal data class BarRequest(
    val symbol: String,
    val windowMs: Long,
    val fromMs: Long,
    val toMs: Long,
)
