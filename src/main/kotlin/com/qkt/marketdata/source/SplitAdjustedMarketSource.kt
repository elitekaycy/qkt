package com.qkt.marketdata.source

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.derivatives.options.chain.OptionMarks
import com.qkt.instrument.SplitAdjustment
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.flow.TradeFlow
import com.qkt.marketdata.marks.MarkPrices
import java.math.BigDecimal

/**
 * [inner]'s history with the splits of [adjustment] applied, for a backtest: every tick and bar of a symbol that split
 * before the run's end is divided by its [SplitAdjustment.factor] (prices) or multiplied by it (volumes), so an
 * indicator, a stop and a fill all read one series. A bar is adjusted by its last instant, so a bar on an ex-date is
 * in the new units. Live data is never adjusted: the venue trades raw prices, and this source refuses to serve them.
 * Every other symbol is [inner]'s, through the same calls.
 */
class SplitAdjustedMarketSource(
    private val inner: MarketSource,
    private val adjustment: SplitAdjustment,
) : MarketSource by inner {
    override fun marksFor(symbol: String): MarkPrices? = inner.marksFor(symbol).also { requireUnadjusted(symbol, it) }

    override fun optionMarksFor(symbol: String): OptionMarks? =
        inner.optionMarksFor(symbol).also { requireUnadjusted(symbol, it) }

    override fun tradeFlowFor(symbol: String): TradeFlow? =
        inner.tradeFlowFor(symbol).also { requireUnadjusted(symbol, it) }

    override fun liveTicks(symbols: List<String>): TickFeed {
        val split = symbols.filter { it in adjustment.symbols }
        require(split.isEmpty()) { "split-adjusted history is for backtests; $split trade raw prices live" }
        return inner.liveTicks(symbols)
    }

    override fun bars(
        symbol: String,
        window: TimeWindow,
        range: TimeRange,
    ): Sequence<Candle> {
        val bars = inner.bars(symbol, window, range)
        return if (symbol in adjustment.symbols) bars.map { adjust(symbol, it) } else bars
    }

    override fun ticks(
        symbol: String,
        range: TimeRange,
    ): Sequence<Tick> {
        val ticks = inner.ticks(symbol, range)
        return if (symbol in adjustment.symbols) ticks.map { adjust(symbol, it) } else ticks
    }

    override fun tickSlice(
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): Sequence<Tick> {
        val ticks = inner.tickSlice(symbol, fromMs, toMs)
        return if (symbol in adjustment.symbols) ticks.map { adjust(symbol, it) } else ticks
    }

    private fun adjust(
        symbol: String,
        tick: Tick,
    ): Tick {
        val f = adjustment.factor(symbol, tick.timestamp)
        if (f.compareTo(BigDecimal.ONE) == 0) return tick
        return tick.copy(
            price = tick.price.over(f),
            volume = tick.volume?.times(f),
            bid = tick.bid?.over(f),
            ask = tick.ask?.over(f),
            bidVolume = tick.bidVolume?.times(f),
            askVolume = tick.askVolume?.times(f),
        )
    }

    private fun adjust(
        symbol: String,
        bar: Candle,
    ): Candle {
        val f = adjustment.factor(symbol, bar.endTime - 1)
        if (f.compareTo(BigDecimal.ONE) == 0) return bar
        return bar.copy(
            open = bar.open.over(f),
            high = bar.high.over(f),
            low = bar.low.over(f),
            close = bar.close.over(f),
            volume = bar.volume.times(f),
            bid = bar.bid?.over(f),
            ask = bar.ask?.over(f),
        )
    }

    /** Marks, option marks and trade flow are per-share series nothing here adjusts: refused rather than mixed. */
    private fun requireUnadjusted(
        symbol: String,
        series: Any?,
    ) = require(series == null || symbol !in adjustment.symbols) {
        "$symbol split, and its ${series?.javaClass?.simpleName} is not split-adjusted"
    }

    private fun BigDecimal.over(f: BigDecimal): BigDecimal =
        divide(f, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
}
