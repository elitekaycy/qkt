package com.qkt.research

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
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
 * (the next merge cycle) — decide the remainder. The opening must go first because a candle closes, and
 * the strategy places orders (including an entry's bracket), on the *next bar's first tick*; deciding
 * before the opening would resolve an entry-and-exit-in-one-bar on synthetic prices. After the opening:
 *  - fill possible -> stream the rest of the real ticks;
 *  - otherwise -> stream the synthetic `low -> high -> close` (no fill can occur, and the candle built
 *    from `realOpen, low, high, close` equals the prebuilt bar).
 */
class BarResolvedFeed(
    perSymbolBars: Map<String, Sequence<Candle>>,
    sliceProvider: (symbol: String, fromMs: Long, toMs: Long) -> Sequence<Tick>,
    fillPossible: (symbol: String, low: BigDecimal, high: BigDecimal) -> Boolean,
) : TickFeed {
    private val subs = perSymbolBars.entries.map { (sym, bars) -> SymbolFeed(sym, bars, sliceProvider, fillPossible) }

    override fun next(): Tick? {
        // The tick emitted last cycle is now ingested; let whichever symbol just emitted its opening
        // tick resolve its bar's fill-possible decision before we compare frontiers.
        for (s in subs) s.settle()
        val pick = subs.filter { it.peek() != null }.minByOrNull { it.peek()!!.timestamp } ?: return null
        return pick.pop()
    }
}

/**
 * One symbol's bar-driven, fill-on-ticks stream. [peek] is the next tick it will emit (its frontier);
 * [pop] emits it; [settle] resolves the pending bar decision once the opening tick has been ingested.
 */
private class SymbolFeed(
    private val symbol: String,
    bars: Sequence<Candle>,
    private val slice: (String, Long, Long) -> Sequence<Tick>,
    private val fillPossible: (String, BigDecimal, BigDecimal) -> Boolean,
) {
    private val barIter = bars.iterator()
    private var head: Tick? = null
    private var headIsOpening = false
    private var nextBar: Candle? = null
    private var nextSlice: Iterator<Tick>? = null
    private var awaitBar: Candle? = null
    private var awaitSlice: Iterator<Tick>? = null
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
        rest = if (fillPossible(symbol, bar.low, bar.high)) awaitSlice!! else syntheticRest(bar)
        awaitBar = null
        awaitSlice = null
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
}
