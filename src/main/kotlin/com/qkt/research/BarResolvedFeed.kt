package com.qkt.research

import com.qkt.app.IntrabarFill
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.buyExecPrice
import com.qkt.marketdata.sellExecPrice
import com.qkt.marketdata.source.candleToTicks
import java.math.BigDecimal

/**
 * A [TickFeed] that drives a `--bars` replay but resolves fills on real ticks where one is possible,
 * staying byte-identical to a full-tick replay — including across symbols.
 *
 * Each symbol is driven by its own [SymbolFeed] (bars + lazy real slices). The merge emits the
 * earliest pending tick across symbols, so the global tick order matches a full-tick `MergingTickFeed`.
 *
 * Per symbol, per bar: emit the bar's **opening tick** (real), then — once the engine has ingested it
 * — decide the remainder via [IntrabarFill]:
 *  - `SYNTHETIC` -> the synthetic `low -> high -> close` (no fill can occur);
 *  - `EXTREMES`  -> only the bar's new-extreme ticks plus the close (the first crossing of any static
 *    level is necessarily a new-extreme tick, and the price extremes carry the candle high/low and the
 *    mark-to-market excursion — so this is byte-identical while feeding far fewer ticks);
 *  - `ALL_TICKS` -> the full real slice (a trailing/composite order or a time-based exit could fire on
 *    a tick the extreme filter would skip).
 */
class BarResolvedFeed(
    perSymbolBars: Map<String, Sequence<Candle>>,
    sliceProvider: (symbol: String, fromMs: Long, toMs: Long) -> Sequence<Tick>,
    intrabarFill: (symbol: String, low: BigDecimal, high: BigDecimal) -> IntrabarFill,
    mustFeedSlicer: ((symbol: String, fromMs: Long, toMs: Long) -> List<Tick>?)? = null,
) : TickFeed {
    private val subs =
        perSymbolBars.entries.map { (sym, bars) ->
            SymbolFeed(sym, bars, sliceProvider, intrabarFill, mustFeedSlicer)
        }

    override fun next(): Tick? {
        // The tick emitted last cycle is now ingested; let whichever symbol just emitted its opening
        // tick resolve its bar before we compare frontiers.
        for (s in subs) s.settle()
        val pick = subs.filter { it.peek() != null }.minByOrNull { it.peek()!!.timestamp } ?: return null
        return pick.pop()
    }
}

/**
 * One symbol's bar-driven, fill-on-ticks stream. [peek] is the next tick it will emit (its frontier);
 * [pop] emits it; [settle] resolves the pending bar once the opening tick has been ingested.
 */
private class SymbolFeed(
    private val symbol: String,
    bars: Sequence<Candle>,
    private val slice: (String, Long, Long) -> Sequence<Tick>,
    private val intrabarFill: (String, BigDecimal, BigDecimal) -> IntrabarFill,
    private val mustFeedSlicer: ((String, Long, Long) -> List<Tick>?)?,
) {
    private val barIter = bars.iterator()
    private var head: Tick? = null
    private var headIsOpening = false
    private var nextBar: Candle? = null
    private var nextSlice: Iterator<Tick>? = null
    private var awaitBar: Candle? = null
    private var awaitSlice: Iterator<Tick>? = null
    private var awaitOpening: Tick? = null
    private var rest: Iterator<Tick> = emptyList<Tick>().iterator()

    init {
        openNextBar()
    }

    private fun openNextBar() {
        while (barIter.hasNext()) {
            val bar = barIter.next()
            val it = slice(symbol, bar.startTime, bar.endTime).iterator()
            if (it.hasNext()) {
                head = it.next()
                headIsOpening = true
                nextBar = bar
                nextSlice = it
                return
            }
            // No real ticks in this bar (a data gap): emit full synthetic O->L->H->C, no decision.
            rest = candleToTicks(bar).iterator()
            if (rest.hasNext()) {
                head = rest.next()
                headIsOpening = false
                return
            }
        }
        head = null
    }

    fun settle() {
        val bar = awaitBar ?: return
        val slice = awaitSlice!!
        rest =
            when (intrabarFill(symbol, bar.low, bar.high)) {
                IntrabarFill.SYNTHETIC -> syntheticRest(bar)
                IntrabarFill.ALL_TICKS -> slice
                IntrabarFill.EXTREMES -> {
                    // Prefer a raw-columnar must-feed scan (decodes only the kept ticks); fall back to
                    // selecting over the fully-decoded slice when the day isn't binary-backed.
                    val selected =
                        mustFeedSlicer?.invoke(symbol, bar.startTime, bar.endTime)
                            ?: selectRestExtremes(awaitOpening!!, slice.asSequence().toList())
                    finalizeVolumes(awaitOpening!!, selected, bar.volume).iterator()
                }
            }
        awaitBar = null
        awaitSlice = null
        awaitOpening = null
        if (rest.hasNext()) {
            head = rest.next()
            headIsOpening = false
        } else {
            openNextBar()
        }
    }

    fun peek(): Tick? = head

    fun pop(): Tick {
        val t = head!!
        if (headIsOpening) {
            // Opening emitted; defer the decision to the next cycle (after this tick is ingested).
            awaitBar = nextBar
            awaitSlice = nextSlice
            awaitOpening = t
            nextBar = null
            nextSlice = null
            head = null
            headIsOpening = false
        } else if (rest.hasNext()) {
            head = rest.next()
        } else {
            openNextBar()
        }
        return t
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

    // Of the rest ticks (those after the opening), the ones setting a new extreme of price (candle
    // high/low + mark-to-market), ask (buy-side fills) or bid (sell-side fills) — seeded from the
    // opening — plus the close (always last). Mirrors BinaryTickFeed.mustFeedRest in BigDecimal form,
    // and is the fallback when the day isn't binary-backed. Volumes are left as read.
    private fun selectRestExtremes(
        opening: Tick,
        rest: List<Tick>,
    ): List<Tick> {
        if (rest.isEmpty()) return rest
        var maxPrice = opening.price
        var minPrice = opening.price
        var maxAsk = opening.buyExecPrice()
        var minBid = opening.sellExecPrice()
        val out = ArrayList<Tick>()
        val last = rest.size - 1
        for (i in 0 until last) {
            val t = rest[i]
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
            val b = t.sellExecPrice()
            if (b < minBid) {
                minBid = b
                keep = true
            }
            if (keep) out.add(t)
        }
        out.add(rest[last])
        return out
    }

    // The must-feed ticks (`[rest extremes..., close]`) with the close carrying the bar's residual
    // volume (bar total minus the opening's and the fed extremes' volume), so the candle aggregated
    // from the fed ticks has exactly the prebuilt bar's volume.
    private fun finalizeVolumes(
        opening: Tick,
        selected: List<Tick>,
        barVolume: BigDecimal,
    ): List<Tick> {
        if (selected.isEmpty()) return selected
        var fedVolume = opening.volume ?: BigDecimal.ZERO
        val out = ArrayList<Tick>(selected.size)
        val last = selected.size - 1
        for (i in 0 until last) {
            out.add(selected[i])
            fedVolume = fedVolume.add(selected[i].volume ?: BigDecimal.ZERO)
        }
        val residual = barVolume.subtract(fedVolume)
        out.add(selected[last].copy(volume = if (residual.signum() >= 0) residual else BigDecimal.ZERO))
        return out
    }
}
