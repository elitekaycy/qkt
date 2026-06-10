package com.qkt.marketdata.source

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed

/**
 * Synthesizes four sub-bar ticks from one OHLC [candle], in the order Open, Low, High, Close.
 *
 * The Low-before-High order is pessimistic for LONG positions (the adverse extreme
 * arrives first) and, unavoidably, OPTIMISTIC for shorts — the high a short fears comes
 * after the low it profits from. One fixed ordering cannot be worst-case for both
 * sides; read short-side bar-backtest results conservatively (see the divergence
 * catalog, row A6). The four ticks fall at strictly increasing timestamps inside the candle's
 * `[startTime, endTime)` window and re-aggregate (via `CandleAggregator`) to exactly this candle —
 * first=open, max=high, min=low, last=close — with the volume on the close tick so the aggregated
 * volume matches. The true intra-bar High/Low order is unknowable from OHLC alone; this is a
 * documented approximation.
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

/** A [TickFeed] over OHLC bars: each candle becomes the four [candleToTicks] in chronological order. */
class BarTickFeed(
    bars: Sequence<Candle>,
) : TickFeed {
    private val iter = bars.flatMap { candleToTicks(it).asSequence() }.iterator()

    override fun next(): Tick? = if (iter.hasNext()) iter.next() else null
}
