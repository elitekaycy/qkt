package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
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
    /** One feed per symbol in [symbols], merged when there is more than one. */
    fun merged(
        source: MarketSource,
        symbols: List<String>,
        range: TimeRange,
        barWindows: Map<String, TimeWindow>,
        candleWindow: TimeWindow?,
        forceBars: Boolean,
        positionSign: (String) -> Int,
    ): TickFeed {
        val perSymbolFeeds: List<TickFeed> =
            symbols.map { sym ->
                replayFeed(source, sym, range, barWindows[sym] ?: candleWindow, forceBars, positionSign)
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
