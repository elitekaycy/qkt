package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.MathContext

/**
 * Volume-Weighted Average Price — like a moving average, except trades on big
 * volume count more than trades on small volume. Answers: "what's the typical
 * price people actually traded at recently?"
 *
 * Common use as a fair-value reference:
 *  - **price above VWAP** — buyers paid a premium today on average
 *  - **price below VWAP** — sellers accepted a discount today on average
 *
 * Big-money desks use VWAP to score execution quality ("did we beat VWAP?").
 *
 * Updates on every raw tick, not just on candle close — so the value reflects
 * intra-bar trades the moment they happen.
 */
class VWAP(
    private val period: Int,
) : Indicator<Tick> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private data class Sample(
        val priceVol: BigDecimal,
        val volume: BigDecimal,
    )

    private val window: ArrayDeque<Sample> = ArrayDeque(period)
    private var numerator: BigDecimal = BigDecimal.ZERO
    private var denominator: BigDecimal = BigDecimal.ZERO

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = window.size >= period

    override fun update(input: Tick) {
        // Quote-driven feeds (FX, metals) report no volume. A volume-less tick carries no weight
        // for a volume-weighted average, so skip it rather than crash the live session. VWAP then
        // averages only the ticks that actually carry volume.
        val volume = input.volume ?: return
        val pv = input.price.multiply(volume, MathContext.DECIMAL128)
        window.addLast(Sample(pv, volume))
        numerator = numerator.add(pv, MathContext.DECIMAL128)
        denominator = denominator.add(volume, MathContext.DECIMAL128)
        if (window.size > period) {
            val out = window.removeFirst()
            numerator = numerator.subtract(out.priceVol, MathContext.DECIMAL128)
            denominator = denominator.subtract(out.volume, MathContext.DECIMAL128)
        }
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        if (denominator.signum() == 0) return null
        return numerator
            .divide(denominator, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}
