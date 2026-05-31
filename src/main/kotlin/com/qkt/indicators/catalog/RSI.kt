package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.Indicator
import java.math.BigDecimal

/**
 * Relative Strength Index — a momentum gauge from 0 to 100. Compares the size of
 * recent up-moves to recent down-moves: 50 is neutral, above 50 means up-moves
 * are dominating, below 50 means down-moves are.
 *
 * Common reads:
 *  - **above 70** — "overbought," many traders look to fade longs here
 *  - **below 30** — "oversold," many look to fade shorts
 *  - **crossing 50** — momentum shift
 *
 * Uses Wilder's smoothing (the original 1978 definition every charting platform
 * uses): the first reading averages the first [period] up/down moves; later
 * readings smooth with weight `1/period` on the newest move.
 */
class RSI(
    private val period: Int,
) : Indicator<BigDecimal> {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private var prevClose: BigDecimal? = null
    private val seedGains: MutableList<BigDecimal> = ArrayList(period)
    private val seedLosses: MutableList<BigDecimal> = ArrayList(period)
    private var avgGain: BigDecimal? = null
    private var avgLoss: BigDecimal? = null

    private val periodBd: BigDecimal = BigDecimal(period)
    private val periodMinusOne: BigDecimal = BigDecimal(period - 1)
    private val hundred: BigDecimal = BigDecimal(100)

    override val warmupBars: Int = period + 1

    override val isReady: Boolean
        get() = avgGain != null && avgLoss != null

    override fun update(input: BigDecimal) {
        val prev = prevClose
        prevClose = input
        if (prev == null) return

        val diff = input.subtract(prev, Money.CONTEXT)
        val gain = if (diff.signum() > 0) diff else BigDecimal.ZERO
        val loss = if (diff.signum() < 0) diff.negate() else BigDecimal.ZERO

        val seededGain = avgGain
        val seededLoss = avgLoss
        if (seededGain == null || seededLoss == null) {
            seedGains.add(gain)
            seedLosses.add(loss)
            if (seedGains.size == period) {
                var gSum = BigDecimal.ZERO
                var lSum = BigDecimal.ZERO
                for (g in seedGains) gSum = gSum.add(g, Money.CONTEXT)
                for (l in seedLosses) lSum = lSum.add(l, Money.CONTEXT)
                avgGain = gSum.divide(periodBd, Money.CONTEXT)
                avgLoss = lSum.divide(periodBd, Money.CONTEXT)
                seedGains.clear()
                seedLosses.clear()
            }
        } else {
            avgGain =
                seededGain
                    .multiply(periodMinusOne, Money.CONTEXT)
                    .add(gain, Money.CONTEXT)
                    .divide(periodBd, Money.CONTEXT)
            avgLoss =
                seededLoss
                    .multiply(periodMinusOne, Money.CONTEXT)
                    .add(loss, Money.CONTEXT)
                    .divide(periodBd, Money.CONTEXT)
        }
    }

    override fun value(): BigDecimal? {
        val g = avgGain ?: return null
        val l = avgLoss ?: return null
        if (l.signum() == 0) {
            return if (g.signum() == 0) Money.of("50") else Money.of("100")
        }
        val rs = g.divide(l, Money.CONTEXT)
        val rsi =
            hundred.subtract(
                hundred.divide(BigDecimal.ONE.add(rs, Money.CONTEXT), Money.CONTEXT),
                Money.CONTEXT,
            )
        return rsi.setScale(Money.SCALE, Money.ROUNDING)
    }
}
