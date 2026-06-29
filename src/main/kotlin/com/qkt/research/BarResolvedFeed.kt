package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.candleToTicks
import java.math.BigDecimal

/**
 * A [TickFeed] that drives a `--bars` replay but resolves fills on real ticks where one is possible,
 * staying byte-identical to a full-tick replay.
 *
 * Per bar it returns the bar's **first real tick**, then — on the next pull, once the engine has
 * ingested that tick — decides the remainder. Returning the first tick first matters: a candle
 * closes (and the strategy places orders, including an entry's stop/target bracket) on the *first
 * tick of the next bar*, so the decision for a bar must be made *after* its opening tick, or an
 * entry-and-exit-in-one-bar would resolve on synthetic prices. Once the opening tick is in:
 *  - fill possible (a live order's level is reachable) -> stream the rest of the real ticks;
 *  - otherwise -> stream the synthetic `low -> high -> close` (cheap; no fill can occur, and the
 *    candle built from `realOpen, low, high, close` equals the prebuilt bar).
 *
 * [sliceProvider] is lazy (a `Sequence`): an inactive bar decodes only its first tick.
 */
class BarResolvedFeed(
    bars: Sequence<Candle>,
    private val sliceProvider: (symbol: String, fromMs: Long, toMs: Long) -> Sequence<Tick>,
    private val fillPossible: (symbol: String, low: BigDecimal, high: BigDecimal) -> Boolean,
) : TickFeed {
    private val barIter = bars.iterator()
    private var rest: Iterator<Tick> = emptyList<Tick>().iterator()
    private var pendingBar: Candle? = null
    private var pendingSlice: Iterator<Tick>? = null

    override fun next(): Tick? {
        while (true) {
            if (rest.hasNext()) return rest.next()

            val pb = pendingBar
            if (pb != null) {
                // The opening tick has been ingested; orders placed at the prior bar's close are
                // now live, so the fill-possible decision sees them.
                rest = if (fillPossible(pb.symbol, pb.low, pb.high)) pendingSlice!! else syntheticRest(pb)
                pendingBar = null
                pendingSlice = null
                continue
            }

            if (!barIter.hasNext()) return null
            val bar = barIter.next()
            val slice = sliceProvider(bar.symbol, bar.startTime, bar.endTime).iterator()
            if (slice.hasNext()) {
                pendingBar = bar
                pendingSlice = slice
                return slice.next() // the bar's opening tick; decide the rest on the next pull
            }
            // No real ticks in this bar (a data gap): fall back to full synthetic O->L->H->C.
            rest = candleToTicks(bar).iterator()
        }
    }

    // The bar's low -> high -> close, minus the open (the real opening tick already stood in for it).
    private fun syntheticRest(bar: Candle): Iterator<Tick> {
        val step = ((bar.endTime - bar.startTime) / 4).coerceAtLeast(1)
        return listOf(
            Tick(bar.symbol, bar.low, bar.startTime + step),
            Tick(bar.symbol, bar.high, bar.startTime + 2 * step),
            Tick(bar.symbol, bar.close, bar.endTime - 1, volume = bar.volume),
        ).iterator()
    }
}
