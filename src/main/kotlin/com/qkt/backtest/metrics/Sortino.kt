package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.MathContext

private val EPSILON = BigDecimal("0.00000001")

/**
 * Online Sortino-ratio accumulator: feed equity readings one at a time, read the ratio at any point.
 *
 * Like [SharpeAccumulator] but the risk term counts only *downside* moves — returns below the target
 * (here 0). Holds running sums (count, Σr, Σ of squared negative returns) so memory is constant
 * regardless of how many samples pass through. The downside deviation is `sqrt(Σ min(r,0)² / count)`
 * — divided by the total number of returns, the standard target semi-deviation, so a curve with the
 * same average return but a smoother downside reads as a higher Sortino than Sharpe.
 *
 * e.g. feed 100, 120, 90, 130 → only the 120→90 step is downside → a higher |ratio| than Sharpe.
 */
class SortinoAccumulator {
    private var prev: BigDecimal? = null
    private var count: Int = 0
    private var sumR: BigDecimal = Money.ZERO
    private var sumDownside2: BigDecimal = Money.ZERO

    /** Ingest the next equity reading, folding its return-vs-previous into the running sums. */
    fun accept(equity: BigDecimal) {
        val p = prev
        if (p != null) {
            val denom = p.abs().max(EPSILON)
            val r = equity.subtract(p).divide(denom, Money.CONTEXT)
            sumR = sumR.add(r)
            if (r.signum() < 0) sumDownside2 = sumDownside2.add(r.multiply(r, Money.CONTEXT))
            count++
        }
        prev = equity
    }

    /**
     * Annualized Sortino from the readings so far, or null when undefined: fewer than two readings,
     * or no downside at all (downside deviation 0 → the ratio is unbounded). Non-destructive.
     */
    fun value(annualizationFactor: BigDecimal): BigDecimal? {
        if (count < 1) return null
        val n = BigDecimal(count)
        val mean = sumR.divide(n, Money.CONTEXT)
        val downsideVar = sumDownside2.divide(n, Money.CONTEXT)
        if (downsideVar.signum() <= 0) return null
        val downsideDev = downsideVar.sqrt(MathContext.DECIMAL64)
        if (downsideDev.signum() == 0) return null
        val annFactor = annualizationFactor.sqrt(MathContext.DECIMAL64)
        return mean
            .divide(downsideDev, Money.CONTEXT)
            .multiply(annFactor, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}

fun sortino(
    equityCurve: List<BigDecimal>,
    annualizationFactor: BigDecimal,
): BigDecimal? {
    if (equityCurve.size < 2) return null
    val acc = SortinoAccumulator()
    equityCurve.forEach(acc::accept)
    return acc.value(annualizationFactor)
}
