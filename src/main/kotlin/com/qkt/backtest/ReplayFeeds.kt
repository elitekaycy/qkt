package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MergingTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.BarTickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SequenceTickFeed

/**
 * The market data a source-backed backtest replays: one tick feed per symbol (real ticks when the
 * source has them, otherwise ticks synthesized from bars) merged into a single feed, plus the bar
 * series and tick slicer the tick-resolved fill tier reads.
 */
internal object ReplayFeeds {
    /**
     * One feed per symbol in [symbols], merged when there is more than one, with the symbols it
     * synthesizes from bars ([BarFills]). Tick-resolved fills ([tickFills]) fill on real ticks, so they
     * fill at no level. A symbol the source has no ticks for is synthesized from its bars even without
     * [forceBars]; under [BrokerKind.MT5_SIM] that is refused like `--bars` is, since bar extremes
     * carry neither MT5 trigger prices nor spread. [positionSign] reads net position signs from the
     * engine that ends up pulling this feed (bound by [Backtest.toEngine]), so each bar emits the open
     * position's adverse extreme first; unbound (fan-out shared feeds) it reads 0 and the feed keeps the
     * flat-default Low-first order.
     */
    fun replay(
        source: MarketSource,
        symbols: List<String>,
        range: TimeRange,
        barWindows: Map<String, TimeWindow>,
        candleWindow: TimeWindow?,
        forceBars: Boolean,
        positionSign: (String) -> Int,
        tickFills: Boolean = false,
        brokerKind: BrokerKind = BrokerKind.PAPER,
    ): Pair<TickFeed, BarFills> {
        val synthesized = mutableSetOf<String>()
        val feed = merged(source, symbols, range, barWindows, candleWindow, forceBars, positionSign, synthesized)
        val fallback = if (forceBars) emptySet() else synthesized
        require(brokerKind != BrokerKind.MT5_SIM || tickFills || fallback.isEmpty()) {
            "--broker mt5-sim cannot replay ${fallback.sorted()} from bars: synthetic bar extremes do not " +
                "preserve MT5 trigger prices or market spread. Provide ticks for them, or use --bars --tick-fills"
        }
        return feed to if (tickFills) BarFills.NONE else BarFills(synthesized)
    }

    /** One feed per symbol in [symbols], merged when there is more than one. */
    fun merged(
        source: MarketSource,
        symbols: List<String>,
        range: TimeRange,
        barWindows: Map<String, TimeWindow>,
        candleWindow: TimeWindow?,
        forceBars: Boolean,
        positionSign: (String) -> Int,
        synthesized: MutableSet<String> = mutableSetOf(),
    ): TickFeed {
        // A fed option root (OPTIONS:<VENUE>.<ROOT>) carries every contract of the root; a contract of a
        // fed root is never fed a second time, so each of its quotes arrives once.
        val fedRoots = symbols.mapNotNull { OptionRootSymbol.parse(it).getOrNull() }
        val perSymbolFeeds: List<TickFeed> =
            symbols.filterNot { sym -> fedRoots.any { it.covers(sym) } }.map { sym ->
                replayFeed(source, sym, range, barWindows[sym] ?: candleWindow, forceBars, positionSign)
                    .also { if (it is BarTickFeed) synthesized += sym }
            }
        return if (perSymbolFeeds.size == 1) perSymbolFeeds[0] else MergingTickFeed(perSymbolFeeds)
    }

    /**
     * Tick-resolved fills: the engine drives off these bars but loads real ticks for any bar a
     * fill could land in. Null unless [tickFills].
     */
    fun tickResolvedBars(
        source: MarketSource,
        symbols: List<String>,
        range: TimeRange,
        barWindows: Map<String, TimeWindow>,
        candleWindow: TimeWindow?,
        tickFills: Boolean,
    ): Map<String, Sequence<Candle>>? =
        if (tickFills) {
            symbols.associateWith { sym ->
                source.bars(
                    sym,
                    barWindows[sym] ?: candleWindow ?: error("--tick-fills needs a candle window"),
                    range,
                )
            }
        } else {
            null
        }

    /**
     * The tick loader for tick-resolved fills. The slice is filtered half-open [from, to) so every
     * tick belongs to exactly one bar regardless of TimeRange boundary semantics. Null unless
     * [tickFills].
     */
    fun tickSlicer(
        source: MarketSource,
        tickFills: Boolean,
    ): ((String, Long, Long) -> Sequence<Tick>)? =
        if (tickFills) {
            { sym, fromMs, toMs -> source.tickSlice(sym, fromMs, toMs) }
        } else {
            null
        }

    /**
     * Picks the replay feed for one symbol: real recorded ticks when the source has them,
     * otherwise synthesized O->L->H->C ticks from its OHLC bars (the only path for bars-only
     * venues like crypto). Preferring ticks keeps tick-sourced backtests (e.g. MT5) byte-for-byte
     * unchanged; the bar fallback is what makes a `qkt fetch`ed crypto symbol backtest at all.
     */
    private fun replayFeed(
        source: MarketSource,
        symbol: String,
        range: TimeRange,
        window: TimeWindow?,
        forceBars: Boolean,
        positionSign: (String) -> Int = { 0 },
    ): TickFeed {
        // A chain analytics stream has values only where the chain allows one; an empty stream is a
        // stream whose rules never fire, not missing data (coverage of its chain days is checked at setup).
        if (symbol.startsWith(ChainAnalyticsSymbol.PREFIX) || symbol.startsWith(OptionRootSymbol.PREFIX)) {
            return SequenceTickFeed(source.ticks(symbol, range))
        }
        val caps = source.capabilities
        val ticksAvailable = MarketSourceCapability.TICKS in caps
        // The `--bars` research tier forces synthesis from bars; otherwise prefer real ticks,
        // which keeps tick-sourced backtests byte-for-byte unchanged.
        if (!forceBars && ticksAvailable) {
            val iter = source.ticks(symbol, range).iterator()
            if (iter.hasNext()) {
                val first = iter.next()
                return SequenceTickFeed(sequenceOf(first) + iter.asSequence())
            }
        }
        // Synthesize O->L->H->C ticks from OHLC bars (the forced research tier, or the bars-only
        // fallback for venues like crypto).
        if (MarketSourceCapability.BARS in caps && window != null) {
            val iter = source.bars(symbol, window, range).iterator()
            require(iter.hasNext()) {
                "no market data for $symbol in requested range ${range.from}..${range.to}"
            }
            return BarTickFeed(sequenceOf(iter.next()) + iter.asSequence(), positionSign)
        }
        if (forceBars) {
            error("--bars: cannot replay bars for $symbol (source has no BARS capability or no candle window)")
        }
        require(ticksAvailable) {
            "bar-based backtest for $symbol needs a candle window (timeframe) — pass candleWindow"
        }
        error("no market data for $symbol in requested range ${range.from}..${range.to}")
    }
}
