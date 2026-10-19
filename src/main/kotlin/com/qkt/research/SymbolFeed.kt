package com.qkt.research

import com.qkt.app.IntrabarFill
import com.qkt.backtest.NoTickBars
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * One symbol's bar-driven, fill-on-ticks stream inside a [BarResolvedFeed]. [peek] is the next tick
 * it will emit (its frontier); [pop] emits it; [settle] resolves the pending bar once the opening
 * tick has been ingested. A bar without real ticks opens on its synthetic open and settles to its
 * synthetic extremes, adverse-first, which fill at their levels ([NoTickBars]).
 */
internal class SymbolFeed(
    private val symbol: String,
    bars: Sequence<Candle>,
    private val slice: (String, Long, Long) -> Sequence<Tick>,
    private val intrabarFill: (String, BigDecimal, BigDecimal, BigDecimal) -> IntrabarFill,
    private val replayEndMs: Long?,
    private val noTickBars: NoTickBars,
) {
    private val barIter = bars.iterator()
    private val tally = noTickBars.track(symbol)
    private var head: Tick? = null
    private var headIsOpening = false
    private var headAtLevel = false
    private var nextBar: Candle? = null
    private var nextSlice: Iterator<Tick>? = null
    private var awaitBar: Candle? = null
    private var awaitSlice: Iterator<Tick>? = null
    private var awaitOpening: Tick? = null
    private var rest: Iterator<Tick> = emptyList<Tick>().iterator()
    private var lastBarEndMs: Long? = null
    private var tailLoaded = false

    init {
        openNextBar()
    }

    private fun openNextBar() {
        headAtLevel = false
        if (barIter.hasNext()) {
            val bar = barIter.next()
            lastBarEndMs = bar.endTime
            val it = slice(symbol, bar.startTime, bar.endTime).iterator()
            val hasTicks = it.hasNext()
            // No real ticks in this bar (a data gap): open on its synthetic open; a null slice marks it.
            head = if (hasTicks) it.next() else Tick(bar.symbol, bar.open, bar.startTime)
            headIsOpening = true
            nextBar = bar
            nextSlice = if (hasTicks) it else null
            return
        }
        val tailFrom = lastBarEndMs
        val tailTo = replayEndMs
        if (!tailLoaded && tailFrom != null && tailTo != null && tailFrom < tailTo) {
            tailLoaded = true
            rest = slice(symbol, tailFrom, tailTo).iterator()
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
        val slice = awaitSlice
        if (slice == null) {
            // No ticks: the synthetic extremes, adverse-first, then the close, all filling at their levels.
            val need = intrabarFill(symbol, bar.low, bar.high, BigDecimal.ZERO)
            tally.bar(hasTicks = false, fillPossible = need != IntrabarFill.SYNTHETIC, startMs = bar.startTime)
            rest = syntheticRest(bar, highFirst = noTickBars.positionSign(symbol) < 0)
        } else {
            val ticks = slice.asSequence().toList()
            val need = intrabarFill(symbol, bar.low, bar.high, maxHalfSpread(awaitOpening, ticks))
            tally.bar(hasTicks = true, fillPossible = need != IntrabarFill.SYNTHETIC, startMs = bar.startTime)
            rest =
                when (need) {
                    IntrabarFill.SYNTHETIC -> syntheticRest(bar)
                    IntrabarFill.ALL_TICKS -> ticks.iterator()
                    IntrabarFill.EXTREMES -> extremeRest(awaitOpening!!, ticks.iterator(), bar)
                }
        }
        awaitBar = null
        awaitSlice = null
        awaitOpening = null
        if (rest.hasNext()) {
            head = rest.next()
            headIsOpening = false
            headAtLevel = slice == null
        } else {
            openNextBar()
        }
    }

    fun peek(): Tick? = head

    fun pop(): Tick {
        val t = head!!
        tally.atLevel = headAtLevel
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
}
