package com.qkt.research

import com.qkt.app.IntrabarFill
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.candleToTicks
import java.math.BigDecimal

/**
 * One symbol's bar-driven, fill-on-ticks stream inside a [BarResolvedFeed]. [peek] is the next tick
 * it will emit (its frontier); [pop] emits it; [settle] resolves the pending bar once the opening
 * tick has been ingested.
 */
internal class SymbolFeed(
    private val symbol: String,
    bars: Sequence<Candle>,
    private val slice: (String, Long, Long) -> Sequence<Tick>,
    private val intrabarFill: (String, BigDecimal, BigDecimal, BigDecimal) -> IntrabarFill,
    private val replayEndMs: Long?,
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
    private var lastBarEndMs: Long? = null
    private var tailLoaded = false

    init {
        openNextBar()
    }

    private fun openNextBar() {
        while (barIter.hasNext()) {
            val bar = barIter.next()
            lastBarEndMs = bar.endTime
            val it = slice(symbol, bar.startTime, bar.endTime).iterator()
            if (it.hasNext()) {
                head = it.next()
                headIsOpening = true
                nextBar = bar
                nextSlice = it
                return
            }
            // No real ticks in this bar (a data gap): emit full synthetic O->L->H->C, no decision.
            val need = intrabarFill(symbol, bar.low, bar.high, BigDecimal.ZERO)
            if (need != IntrabarFill.SYNTHETIC) {
                log.warn(
                    "tick-fills falling back to synthetic path for fill-possible bar symbol={} start={} mode={}",
                    symbol,
                    bar.startTime,
                    need,
                )
            }
            rest = candleToTicks(bar).iterator()
            if (rest.hasNext()) {
                head = rest.next()
                headIsOpening = false
                return
            }
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
        val slice = awaitSlice!!
        val ticks = slice.asSequence().toList()
        val maxHalfSpread = maxHalfSpread(awaitOpening, ticks)
        rest =
            when (intrabarFill(symbol, bar.low, bar.high, maxHalfSpread)) {
                IntrabarFill.SYNTHETIC -> syntheticRest(bar)
                IntrabarFill.ALL_TICKS -> ticks.iterator()
                IntrabarFill.EXTREMES -> extremeRest(awaitOpening!!, ticks.iterator(), bar)
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

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(SymbolFeed::class.java)
    }
}
