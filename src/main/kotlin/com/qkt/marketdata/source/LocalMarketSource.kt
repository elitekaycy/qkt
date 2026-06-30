package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.openDayFeed
import com.qkt.marketdata.store.BinaryBarStore
import com.qkt.marketdata.store.DataStore
import com.qkt.marketdata.store.LocalBarStore
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class LocalMarketSource(
    private val store: DataStore,
    private val clock: Clock,
    /**
     * Phase 25A: optional pre-fetched bar store keyed by `(broker, symbol, tf)`. When
     * present and fully populated for the requested range, `bars()` reads bars
     * directly from disk instead of aggregating ticks. Partial coverage falls back
     * to tick aggregation, so the two stores can coexist safely.
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

    // One mmap'd binary feed per (storeKey, day), reused across the many per-bar slices a
    // --tick-fills replay does. A null value marks a day with no usable binary file.
    private val sliceFeeds = mutableMapOf<Pair<String, LocalDate>, com.qkt.marketdata.BinaryTickFeed?>()

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        val storeKey = symbol.substringAfter(':')
        val range = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
        return sequence {
            for (day in daysCovering(range)) {
                val feed =
                    sliceFeeds.getOrPut(storeKey to day) {
                        val path = store.dayFile(storeKey, day)
                        if (path != null &&
                            path.toString().endsWith(".bin")
                        ) {
                            com.qkt.marketdata.BinaryTickFeed(path)
                        } else {
                            null
                        }
                    }
                if (feed != null) {
                    feed.slice(fromMs, toMs) // O(log n) seek; decodes only the window
                    while (true) {
                        val t = feed.next() ?: break
                        yield(if (t.symbol == symbol) t else t.copy(symbol = symbol))
                    }
                } else {
                    // Non-binary day (csv): fall back to a filtered scan; forge data is binary.
                    val path = store.dayFile(storeKey, day) ?: continue
                    openDayFeed(path).use { f ->
                        while (true) {
                            val t = f.next() ?: break
                            if (t.timestamp < fromMs) continue
                            if (t.timestamp >= toMs) break
                            yield(if (t.symbol == symbol) t else t.copy(symbol = symbol))
                        }
                    }
                }
            }
        }
    }

    override fun mustFeedRest(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<Tick>? {
        val storeKey = symbol.substringAfter(':')
        val range = TimeRange(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
        // A bar spanning more than one day-file can't be scanned from a single mmap'd feed; let the
        // caller fall back to decoding the (cross-day) slice. Intraday bars are within one day.
        val days = daysCovering(range).toList()
        if (days.size != 1) return null
        val feed =
            sliceFeeds.getOrPut(storeKey to days[0]) {
                val path = store.dayFile(storeKey, days[0])
                if (path != null && path.toString().endsWith(".bin")) com.qkt.marketdata.BinaryTickFeed(path) else null
            } ?: return null
        val rest = feed.mustFeedRest(fromMs, toMs)
        return rest.map { if (it.symbol == symbol) it else it.copy(symbol = symbol) }
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val bin = binaryBarStore
        if (bin != null) {
            // --bars research tier: read pre-built binary bars per day (gaps tolerated, like ticks);
            // no slow tick-aggregation fallback. The store stamps the prefixed qktSymbol on read.
            val parts = symbol.split(":", limit = 2)
            val broker = if (parts.size == 2) parts[0] else "BACKTEST"
            val sym = parts.last()
            val days = daysCovering(range)
            val rangeFromMs = range.from.toEpochMilli()
            val rangeToMs = range.to.toEpochMilli()
            return sequence {
                for (day in days) {
                    if (!bin.hasDay(broker, sym, window, day)) continue
                    for (candle in bin.readDay(broker, sym, window, day)) {
                        if (candle.startTime < rangeFromMs) continue
                        if (candle.endTime > rangeToMs) continue
                        yield(candle)
                    }
                }
            }
        }
        val bs = barStore
        if (bs != null) {
            val parts = symbol.split(":", limit = 2)
            if (parts.size == 2) {
                val broker = parts[0]
                val sym = parts[1]
                val tf = window.canonicalSpec()
                val days = daysCovering(range)
                if (days.isNotEmpty() && days.all { bs.hasDay(broker, sym, tf, it) }) {
                    val rangeFromMs = range.from.toEpochMilli()
                    val rangeToMs = range.to.toEpochMilli()
                    return sequence {
                        for (day in days) {
                            for (candle in bs.readDay(broker, sym, tf, day)) {
                                if (candle.startTime < rangeFromMs) continue
                                if (candle.endTime > rangeToMs) continue
                                yield(candle)
                            }
                        }
                    }
                }
            }
        }
        return aggregateFromTicks(symbol, window, range)
    }

    private fun aggregateFromTicks(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        sequence {
            var bucketStart: Long = -1
            var bucketEnd: Long = -1
            var open: BigDecimal = Money.ZERO
            var high: BigDecimal = Money.ZERO
            var low: BigDecimal = Money.ZERO
            var close: BigDecimal = Money.ZERO
            var volume: BigDecimal = Money.ZERO
            var hasData = false

            for (tick in ticks(symbol, range)) {
                val ws = window.windowStartFor(tick.timestamp)
                if (!hasData) {
                    bucketStart = ws
                    bucketEnd = ws + window.durationMs
                    open = tick.price
                    high = tick.price
                    low = tick.price
                    close = tick.price
                    volume = tick.volume ?: Money.ZERO
                    hasData = true
                    continue
                }
                if (tick.timestamp >= bucketEnd) {
                    yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
                    bucketStart = ws
                    bucketEnd = ws + window.durationMs
                    open = tick.price
                    high = tick.price
                    low = tick.price
                    close = tick.price
                    volume = tick.volume ?: Money.ZERO
                } else {
                    if (tick.price > high) high = tick.price
                    if (tick.price < low) low = tick.price
                    close = tick.price
                    if (tick.volume != null) volume = volume.add(tick.volume)
                }
            }
            if (hasData) {
                yield(Candle(symbol, open, high, low, close, volume, bucketStart, bucketEnd))
            }
        }

    private fun daysCovering(range: TimeRange): List<LocalDate> {
        val fromDay = range.from.atZone(ZoneOffset.UTC).toLocalDate()
        val toInclusiveDay = Instant.ofEpochMilli(range.to.toEpochMilli() - 1).atZone(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<LocalDate>()
        var d = fromDay
        while (!d.isAfter(toInclusiveDay)) {
            days.add(d)
            d = d.plusDays(1)
        }
        return days
    }
}
