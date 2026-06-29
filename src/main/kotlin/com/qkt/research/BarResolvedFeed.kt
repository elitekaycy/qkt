package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.candleToTicks
import java.math.BigDecimal

/**
 * A [TickFeed] that drives a `--bars` replay but resolves fills on real ticks where one is possible.
 *
 * For each time-ordered bar it asks [fillPossible]; if true it streams the bar's real tick slice
 * (via [sliceProvider]) so stop/limit/SL/TP fills resolve exactly, then `CandleHub` rolls those
 * ticks up to the same candle a full-tick run would close. If false it streams the cheap synthetic
 * `open -> low -> high -> close` ticks — and since no fill was possible, that path is
 * indistinguishable from the real one for the outcome, while the candle it builds equals the
 * prebuilt bar. [fillPossible] is queried once per bar, when that bar's first tick is pulled, so it
 * reflects orders placed at the prior bar's close.
 *
 * e.g. a bar with no live order in range -> 4 synthetic ticks; a bar with a resting stop in range
 * -> that bar's real ticks.
 */
class BarResolvedFeed(
    bars: Sequence<Candle>,
    private val sliceProvider: (symbol: String, fromMs: Long, toMs: Long) -> List<Tick>,
    private val fillPossible: (symbol: String, low: BigDecimal, high: BigDecimal) -> Boolean,
) : TickFeed {
    private val barIter = bars.iterator()
    private var buffer: Iterator<Tick> = emptyList<Tick>().iterator()

    override fun next(): Tick? {
        while (!buffer.hasNext()) {
            if (!barIter.hasNext()) return null
            val bar = barIter.next()
            buffer =
                if (fillPossible(bar.symbol, bar.low, bar.high)) {
                    sliceProvider(bar.symbol, bar.startTime, bar.endTime).iterator()
                } else {
                    candleToTicks(bar).iterator()
                }
        }
        return buffer.next()
    }
}
