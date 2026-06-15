package com.qkt.backtest

import com.qkt.backtest.metrics.StreamingMedian
import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.CandleEvent
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Computes [ConditionalAutocorr] online from the closed-bar stream, one bar at a time.
 *
 * Subscribes to [CandleEvent] and, for each bar, derives the close-to-close return from the
 * previous bar's close **of the same symbol**, then folds the consecutive return pair
 * `(r_i, r_{i-1})` into lag-1 Pearson accumulators bucketed by:
 * - the bar's UTC hour-of-day, and
 * - the bar's volatility regime (`|r_i|` against a running median estimate).
 *
 * Memory is bounded: per (symbol, hour) and per (symbol, regime) it holds only the running sums
 * a lag-1 Pearson needs (count, `Σr_i`, `Σr_{i-1}`, `Σr_i²`, `Σr_{i-1}²`, `Σ r_i·r_{i-1}`), plus
 * a five-marker [StreamingMedian] per symbol. Nothing scales with the number of bars — the same
 * invariant [EquityCurveCollector] holds, so a multi-million-bar run cannot exhaust memory.
 *
 * The regime split is approximate by design (resolved with elitekaycy): each return is classified
 * against the median absolute return *as estimated from the bars seen so far*, not a final
 * exact median. An exact split would require retaining every return (O(bars) memory), defeating
 * the bounded-memory goal. The hour buckets and all autocorrelation values are exact.
 *
 * Returns are taken from [com.qkt.marketdata.Candle.close] (open question C: faithful even on
 * bar-synthesized feeds, since the aggregated close is the real bar close, not a synthetic
 * intra-bar tick). The metric is keyed per symbol (open question B), so a multi-symbol replay
 * reports one [ConditionalAutocorr] per symbol; [snapshot] returns a single-entry map in the
 * common single-symbol case.
 */
class ReturnAutocorrCollector(
    bus: EventBus,
) {
    private val perSymbol = mutableMapOf<String, SymbolState>()

    init {
        bus.subscribe<CandleEvent> { e -> accept(e.candle.symbol, e.candle.close, e.candle.endTime) }
    }

    private fun accept(
        symbol: String,
        close: BigDecimal,
        endTime: Long,
    ) {
        val state = perSymbol.getOrPut(symbol) { SymbolState() }
        val prevClose = state.prevClose
        state.prevClose = close
        if (prevClose == null || prevClose.signum() == 0) return

        val ret = close.subtract(prevClose, Money.CONTEXT).divide(prevClose, Money.CONTEXT)
        val prevReturn = state.prevReturn
        state.prevReturn = ret

        state.median.accept(ret.abs().toDouble())

        // A lag-1 pair needs a predecessor; the first return of a symbol has none.
        if (prevReturn == null) return

        val hour = Instant.ofEpochMilli(endTime).atZone(ZoneOffset.UTC).hour
        state.hourPairs.getOrPut(hour) { LagPairs() }.accept(ret, prevReturn)

        val medianAbs = state.median.estimate()
        if (medianAbs != null) {
            val regime = if (ret.abs().toDouble() >= medianAbs) Regime.HIGH else Regime.LOW
            state.regimePairs.getOrPut(regime) { LagPairs() }.accept(ret, prevReturn)
        }
    }

    /** Finished [ConditionalAutocorr] per symbol. Non-destructive — safe to call mid-replay. */
    fun snapshot(): Map<String, ConditionalAutocorr> =
        perSymbol.mapValues { (_, state) ->
            ConditionalAutocorr(
                perHour = state.hourPairs.mapNotNull { (h, p) -> p.autocorr()?.let { h to it } }.toMap(),
                perRegime = state.regimePairs.mapNotNull { (r, p) -> p.autocorr()?.let { r to it } }.toMap(),
                hourCounts = state.hourPairs.mapValues { it.value.count },
                regimeCounts = state.regimePairs.mapValues { it.value.count },
            )
        }

    private class SymbolState {
        var prevClose: BigDecimal? = null
        var prevReturn: BigDecimal? = null
        val median = StreamingMedian()
        val hourPairs = mutableMapOf<Int, LagPairs>()
        val regimePairs = mutableMapOf<Regime, LagPairs>()
    }
}

/**
 * Running sums for the lag-1 sample autocorrelation of a set of return pairs `(current, prior)`.
 *
 * Uses the lagged-mean form — the Pearson correlation between each return and its predecessor,
 * with separate means for the two legs — so a bucket whose returns sit at a different level than
 * their predecessors (e.g. an hour bucket whose prior bars fall in another hour) is not biased.
 * Holds only the six running sums, so cost is O(1) per pair.
 */
private class LagPairs {
    var count: Int = 0
        private set
    private var sumCur: BigDecimal = Money.ZERO
    private var sumPrev: BigDecimal = Money.ZERO
    private var sumCurSq: BigDecimal = Money.ZERO
    private var sumPrevSq: BigDecimal = Money.ZERO
    private var sumCross: BigDecimal = Money.ZERO

    fun accept(
        current: BigDecimal,
        prior: BigDecimal,
    ) {
        count++
        sumCur = sumCur.add(current, Money.CONTEXT)
        sumPrev = sumPrev.add(prior, Money.CONTEXT)
        sumCurSq = sumCurSq.add(current.multiply(current, Money.CONTEXT), Money.CONTEXT)
        sumPrevSq = sumPrevSq.add(prior.multiply(prior, Money.CONTEXT), Money.CONTEXT)
        sumCross = sumCross.add(current.multiply(prior, Money.CONTEXT), Money.CONTEXT)
    }

    /**
     * Lag-1 autocorrelation, or null when undefined: fewer than three pairs, or zero variance on
     * either leg (matching the `EquityMetrics.sharpe` "zero variance → null" convention).
     *
     * `corr = Σ(c-c̄)(p-p̄) / sqrt(Σ(c-c̄)² · Σ(p-p̄)²)`, recovered from the running sums via the
     * identity `Σ(c-c̄)(p-p̄) = Σcp − n·c̄·p̄`.
     */
    fun autocorr(): BigDecimal? {
        if (count < MIN_PAIRS) return null
        val n = BigDecimal(count)
        val meanCur = sumCur.divide(n, Money.CONTEXT)
        val meanPrev = sumPrev.divide(n, Money.CONTEXT)
        val cov = sumCross.subtract(n.multiply(meanCur, Money.CONTEXT).multiply(meanPrev, Money.CONTEXT), Money.CONTEXT)
        val varCur =
            sumCurSq.subtract(
                n.multiply(meanCur, Money.CONTEXT).multiply(meanCur, Money.CONTEXT),
                Money.CONTEXT,
            )
        val varPrev =
            sumPrevSq.subtract(n.multiply(meanPrev, Money.CONTEXT).multiply(meanPrev, Money.CONTEXT), Money.CONTEXT)
        if (varCur.signum() <= 0 || varPrev.signum() <= 0) return null
        val denom = varCur.multiply(varPrev, Money.CONTEXT).sqrt(Money.CONTEXT)
        if (denom.signum() == 0) return null
        return cov.divide(denom, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    private companion object {
        const val MIN_PAIRS = 3
    }
}
