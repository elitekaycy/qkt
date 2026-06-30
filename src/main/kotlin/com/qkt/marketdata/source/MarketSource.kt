package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

interface MarketSource {
    val name: String
    val capabilities: Set<MarketSourceCapability>

    fun supports(symbol: String): Boolean

    /**
     * Capabilities for one [symbol]. Defaults to [capabilities]; a routing source
     * ([CompositeMarketSource]) overrides this to return the leaf that actually serves the symbol,
     * so a per-stream capability check (e.g. "does this feed supply volume?") is honored across a
     * basket where different symbols route to different feeds.
     */
    fun capabilitiesFor(symbol: String): Set<MarketSourceCapability> = capabilities

    fun liveTicks(symbols: List<String>): TickFeed =
        throw UnsupportedDataException(MarketSourceCapability.LIVE_TICKS, this::class.java.simpleName ?: "MarketSource")

    fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> =
        throw UnsupportedDataException(MarketSourceCapability.BARS, this::class.java.simpleName ?: "MarketSource")

    fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> =
        throw UnsupportedDataException(MarketSourceCapability.TICKS, this::class.java.simpleName ?: "MarketSource")

    /**
     * Real ticks in the half-open window `[fromMs, toMs)` for [symbol] — the per-bar slicing the
     * `--tick-fills` replay does on every bar. The default re-reads via [ticks] (correct, but
     * re-decodes the whole day on every call); [LocalMarketSource] overrides it to mmap each day
     * once and binary-search the window, so slicing every bar is cheap.
     */
    fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> =
        ticks(symbol, TimeRange(java.time.Instant.ofEpochMilli(fromMs), java.time.Instant.ofEpochMilli(toMs)))
            .filter { it.timestamp in fromMs until toMs }

    /**
     * The must-feed ticks of the fill bar `[fromMs, toMs)` for a tick-resolved replay: the ticks after
     * the opening that set a new price/ask/bid extreme, plus the close (see [com.qkt.marketdata.BinaryTickFeed.mustFeedRest]).
     * Returns null when this source can't scan them cheaply (the caller then decodes the whole slice and
     * selects the same set itself). The default returns null; [LocalMarketSource] serves it from the
     * mmap'd binary day-file by scanning raw columns, decoding only the kept ticks.
     */
    fun mustFeedRest(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<Tick>? = null
}
