package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.MathContext

private val EPSILON = BigDecimal("0.00000001")

/**
 * Online Sharpe-ratio accumulator: feed equity readings one at a time, read the ratio at any point.
 *
 * Holds only running sums (count, Σr, Σr²) rather than the full return series, so memory is constant
 * regardless of how many samples pass through — the same number a one-pass [sharpe] over the whole
 * curve would produce. Uses the algebraic identity `Σ(r−mean)² = Σr² − mean·Σr` to recover the
 * sample variance from those sums.
 *
 * e.g. feed 100, 110, 120 → returns 0.10 and ~0.0909 → a positive annualized Sharpe.
 */
class SharpeAccumulator {
    private var prev: BigDecimal? = null
    private var count: Int = 0
    private var sumR: BigDecimal = Money.ZERO
    private var sumR2: BigDecimal = Money.ZERO

    /** Ingest the next equity reading, folding its return-vs-previous into the running sums. */
    fun accept(equity: BigDecimal) {
        val p = prev
        if (p != null) {
            val denom = p.abs().max(EPSILON)
            val r = equity.subtract(p).divide(denom, Money.CONTEXT)
            sumR = sumR.add(r)
            sumR2 = sumR2.add(r.multiply(r, Money.CONTEXT))
            count++
        }
        prev = equity
    }

    /**
     * Annualized Sharpe from the readings seen so far, or null when it is undefined: fewer than two
     * readings, or zero return-variance (flat equity). Non-destructive — safe to call mid-stream.
     */
    fun value(annualizationFactor: BigDecimal): BigDecimal? {
        if (count < 1) return null
        val n = BigDecimal(count)
        val mean = sumR.divide(n, Money.CONTEXT)
        val ssd = sumR2.subtract(mean.multiply(sumR, Money.CONTEXT))
        val variance = ssd.divide(BigDecimal(count - 1).max(BigDecimal.ONE), Money.CONTEXT)
        if (variance.signum() <= 0) return null

        val stddev = variance.sqrt(MathContext.DECIMAL64)
        if (stddev.signum() == 0) return null

        val annFactor = annualizationFactor.sqrt(MathContext.DECIMAL64)
        return mean
            .divide(stddev, Money.CONTEXT)
            .multiply(annFactor, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}

fun sharpe(
    equityCurve: List<BigDecimal>,
    annualizationFactor: BigDecimal,
): BigDecimal? {
    if (equityCurve.size < 2) return null
    val acc = SharpeAccumulator()
    equityCurve.forEach(acc::accept)
    return acc.value(annualizationFactor)
}
