package com.qkt.marketdata.source

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

/**
 * Synthesizes four sub-bar ticks from one OHLC [candle], in the order Open, Low, High, Close.
 *
 * The Low-before-High order is pessimistic for LONG positions (the adverse extreme arrives first).
 * Callers that know the open position's side should use [BarTickFeed] with a `positionSign`
 * accessor, which emits the adverse extreme first for shorts too. The four ticks fall at strictly
 * increasing timestamps inside the candle's `[startTime, endTime)` window and re-aggregate (via
 * `CandleAggregator`) to exactly this candle — first=open, max=high, min=low, last=close — with the
 * volume on the close tick so the aggregated volume matches. The true intra-bar High/Low order is
 * unknowable from OHLC alone; this is a documented approximation.
 *
 * e.g. a 5m candle O=100 H=110 L=90 C=105 over [0, 300000) yields ticks at
 * (0, 100), (75000, 90), (150000, 110), (299999, 105).
 */
fun candleToTicks(candle: Candle): List<Tick> {
    val step = ((candle.endTime - candle.startTime) / 4).coerceAtLeast(1)
    return listOf(
        Tick(candle.symbol, candle.open, candle.startTime),
        Tick(candle.symbol, candle.low, candle.startTime + step),
        Tick(candle.symbol, candle.high, candle.startTime + 2 * step),
        Tick(candle.symbol, candle.close, candle.endTime - 1, volume = candle.volume),
    )
}

/**
 * A [TickFeed] over OHLC bars: each candle becomes four synthetic ticks — Open, then the two
 * extremes, then Close — with the ADVERSE extreme first for whichever side is open on the symbol.
 *
 * A bar has no intra-bar path, so when both a bracket's stop and target sit inside the bar's range
 * the emission order decides which fills (the first fill cancels the OCO sibling). Emitting the
 * adverse extreme first makes that ambiguity resolve pessimistically for the open position:
 * a net LONG sees Low before High (the stop below is tested first), a net SHORT sees High before
 * Low (the stop above is tested first). Flat symbols keep the Low-first default.
 *
 * [positionSign] is consulted lazily, after the bar's Open tick has been fully processed by the
 * single-threaded replay loop — so an entry filled on the open influences its own bar's ordering.
 * e.g. a short entered at O=100 with SL 103 / TP 97 inside a 90..110 bar now hits 110 (stop) first.
 *
 * Remaining approximation: opposing exposure on one symbol nets to a single sign, and the true
 * intra-bar High/Low order is still unknowable from OHLC alone. Timestamps keep the same four
 * strictly increasing slots regardless of ordering, so bars re-aggregate exactly (first=open,
 * min=low, max=high, last=close, volume on close).
 */
class BarTickFeed(
    bars: Sequence<Candle>,
    private val positionSign: (symbol: String) -> Int = { 0 },
) : TickFeed {
    private val barIter = bars.iterator()
    private var current: Candle? = null
    private var emitted = 0
    private var highFirst = false

    override fun next(): Tick? {
        var bar = current
        if (bar == null || emitted == 4) {
            if (!barIter.hasNext()) return null
            bar = barIter.next()
            current = bar
            emitted = 0
        }
        val step = ((bar.endTime - bar.startTime) / 4).coerceAtLeast(1)
        val tick =
            when (emitted) {
                0 -> Tick(bar.symbol, bar.open, bar.startTime)
                1 -> {
                    // Decided once per bar, here rather than at bar start: the Open tick above has
                    // already been processed, so an entry filled on this bar's open steers its own
                    // extremes toward the adverse-first order. Cached so the first extreme filling the
                    // stop (and flattening the position) cannot flip the order mid-bar and emit one
                    // extreme twice.
                    highFirst = positionSign(bar.symbol) < 0
                    Tick(bar.symbol, if (highFirst) bar.high else bar.low, bar.startTime + step)
                }
                2 -> Tick(bar.symbol, if (highFirst) bar.low else bar.high, bar.startTime + 2 * step)
                else -> Tick(bar.symbol, bar.close, bar.endTime - 1, volume = bar.volume)
            }
        emitted += 1
        return tick
    }
}
