package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.buyExecPrice
import com.qkt.marketdata.sellExecPrice
import java.math.BigDecimal

// The ticks a tick-resolved bar feeds after its real opening tick, one function per IntrabarFill
// mode that does not simply replay the whole real slice. All pure: they read only their arguments.

/** Widest half-spread seen across the bar's opening tick and its remaining real [ticks]. */
internal fun maxHalfSpread(
    opening: Tick?,
    ticks: List<Tick>,
): BigDecimal =
    (listOfNotNull(opening) + ticks)
        .maxOfOrNull { tick ->
            val ask = tick.ask
            val bid = tick.bid
            if (ask == null || bid == null) {
                BigDecimal.ZERO
            } else {
                ask.subtract(bid).abs().divide(BigDecimal(2))
            }
        } ?: BigDecimal.ZERO

/** The bar's low -> high -> close, minus the open (the real opening tick already stood in for it). */
internal fun syntheticRest(bar: Candle): Iterator<Tick> {
    val step = ((bar.endTime - bar.startTime) / 4).coerceAtLeast(1)
    return listOf(
        Tick(bar.symbol, bar.low, bar.startTime + step),
        Tick(bar.symbol, bar.high, bar.startTime + 2 * step),
        Tick(bar.symbol, bar.close, bar.endTime - 1, volume = bar.volume),
    ).iterator()
}

/**
 * Keep only the rest-slice ticks that set a new extreme of price (candle high/low + mark-to-market),
 * ask (buy-side fills) or bid (sell-side fills), seeded from the opening; the close tick is always
 * fed last carrying the bar's residual volume, so the aggregated candle equals the prebuilt bar.
 */
internal fun extremeRest(
    opening: Tick,
    slice: Iterator<Tick>,
    bar: Candle,
): Iterator<Tick> {
    val ticks = slice.asSequence().toList()
    if (ticks.isEmpty()) return ticks.iterator()
    var maxPrice = opening.price
    var minPrice = opening.price
    var maxAsk = opening.buyExecPrice()
    var minAsk = maxAsk
    var maxBid = opening.sellExecPrice()
    var minBid = opening.sellExecPrice()
    var fedVolume = opening.volume ?: BigDecimal.ZERO
    val out = ArrayList<Tick>()
    val lastIndex = ticks.size - 1
    for (i in ticks.indices) {
        val t = ticks[i]
        var keep = false
        if (t.price > maxPrice) {
            maxPrice = t.price
            keep = true
        }
        if (t.price < minPrice) {
            minPrice = t.price
            keep = true
        }
        val a = t.buyExecPrice()
        if (a > maxAsk) {
            maxAsk = a
            keep = true
        }
        if (a < minAsk) {
            minAsk = a
            keep = true
        }
        val b = t.sellExecPrice()
        if (b > maxBid) {
            maxBid = b
            keep = true
        }
        if (b < minBid) {
            minBid = b
            keep = true
        }
        if (i == lastIndex) break // the close is fed below, carrying the residual volume
        if (keep) {
            out.add(t)
            fedVolume = fedVolume.add(t.volume ?: BigDecimal.ZERO)
        }
    }
    val residual = bar.volume.subtract(fedVolume)
    out.add(ticks[lastIndex].copy(volume = if (residual.signum() >= 0) residual else BigDecimal.ZERO))
    return out.iterator()
}
