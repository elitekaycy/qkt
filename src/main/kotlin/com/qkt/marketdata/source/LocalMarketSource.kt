package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.openDayFeed
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.LocalBarStore
import java.time.Instant
import java.time.LocalDate

/**
 * Historical [MarketSource] over the local tick [store]: tick reads guarded against look-ahead
 * by [clock], bar reads from pre-built bar stores when they cover the range, otherwise
 * aggregated from ticks. Has no live feed.
 */
class LocalMarketSource(
    private val store: DataStore,
    private val clock: Clock,
    /**
     * Phase 25A: optional pre-fetched bar store keyed by `(broker, symbol, tf)`. When it holds
     * ANY day of the requested range, `bars()` reads those days from disk instead of aggregating
     * ticks; days the store does not have are skipped rather than disqualifying the whole read,
     * because a range longer than a few days always misses the days the venue did not trade.
     * Only a range the store cannot serve at all falls back to tick aggregation.
     */
    private val barStore: LocalBarStore? = null,
    /**
     * The `--bars` research tier's pre-built binary bar store. When injected, [bars] reads from it
     * exclusively (no slow tick-aggregation fallback) — set only for `--bars` runs.
     */
    private val binaryBarStore: BinaryBarStore? = null,
) : MarketSource {
    override val name: String = "Local"
    override val capabilities: Set<MarketSourceCapability> =
        setOf(MarketSourceCapability.BARS, MarketSourceCapability.TICKS)

    override fun supports(symbol: String): Boolean = true

    private val volumeBySymbol: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * Per-symbol capabilities: the base set, plus VOLUME when this symbol's stored ticks actually
     * carry volume. A volume-weighted indicator (VWAP/OBV) bound to a volume-less feed would never
     * become ready, so the deploy-time check in `TradingPipeline` needs the truth per symbol — one
     * symbol's feed may have volume while another's does not. Detected by peeking the first stored
     * tick once and caching; only consulted for strategies that use a volume indicator, so it adds
     * no cost to runs that don't.
     */
    override fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> =
        if (storeSuppliesVolume(symbol)) capabilities + MarketSourceCapability.VOLUME else capabilities

    private fun storeSuppliesVolume(symbol: String): Boolean =
        volumeBySymbol.getOrPut(symbol) { firstStoredTick(symbol.substringAfter(':'))?.volume != null }

    /** First tick of the earliest non-empty day file for [storeKey], or null if the store is empty. */
    private fun firstStoredTick(storeKey: String): Tick? {
        for (range in store.manifest(storeKey).ranges) {
            var day = LocalDate.parse(range.from)
            val end = LocalDate.parse(range.to)
            while (!day.isAfter(end)) {
                val path = store.dayFile(storeKey, day)
                if (path != null) {
                    val tick = openDayFeed(path).use { it.next() }
                    if (tick != null) return tick
                }
                day = day.plusDays(1)
            }
        }
        return null
    }

    override fun liveTicks(symbols: List<String>): TickFeed =
        throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, this::class.java.simpleName!!)

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val now = Instant.ofEpochMilli(clock.now())
        require(range.to <= now) {
            "look-ahead bias: cannot query ticks beyond current time. now=$now, requested to=${range.to}; symbol=$symbol"
        }
        // The tick store is keyed by the bare symbol (`symbols/BTCUSDT/`), but strategies route
        // by the broker-prefixed id (`BACKTEST:BTCUSDT`). Strip the prefix to find the file, then
        // stamp each tick with the requested prefixed id so it matches the strategy's stream.
        // A bare symbol (no `:`) is left unchanged. Mirrors how `bars()` splits the prefix.
        val storeKey = symbol.substringAfter(':')
        return sequence {
            val days = daysCovering(range)
            for (day in days) {
                val path = store.dayFile(storeKey, day) ?: continue
                openDayFeed(path).use { feed ->
                    while (true) {
                        val t = feed.next() ?: break
                        if (t.timestamp < range.from.toEpochMilli()) continue
                        if (t.timestamp >= range.to.toEpochMilli()) return@use
                        yield(if (t.symbol == symbol) t else t.copy(symbol = symbol))
                    }
                }
            }
        }
    }

    private val sliceReader = DayTickSliceReader(store)

    private val prebuiltBars = PrebuiltBarReader(barStore, binaryBarStore)

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> = sliceReader.tickSlice(symbol, fromMs, toMs)

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        prebuiltBars.bars(symbol, window, range)
            ?: aggregateTicksToCandles(symbol, window, Sequence { ticks(symbol, range).iterator() })
}
