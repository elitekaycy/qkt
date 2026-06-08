package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

/**
 * Computes drawdown — `peak − current`, expressed as a positive [BigDecimal] —
 * from the [EquityTracker]'s peak and current readings. Stateless; every call
 * derives drawdown from the live tracker state.
 *
 * Returns [Money.ZERO] when peak is non-positive (no equity history yet) so
 * downstream halt rules don't trip on a startup transient.
 */
class DrawdownTracker(
    private val equityTracker: EquityTracker,
) {
    fun globalDrawdown(): BigDecimal {
        val peak = equityTracker.peakEquity()
        if (peak.signum() <= 0) return Money.ZERO
        val current = equityTracker.currentEquity()
        if (current >= peak) return Money.ZERO
        return peak.subtract(current).divide(peak, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun strategyDrawdown(strategyId: String): BigDecimal {
        val peak = equityTracker.peakEquityFor(strategyId)
        if (peak.signum() <= 0) return Money.ZERO
        val current = equityTracker.currentEquityFor(strategyId)
        if (current >= peak) return Money.ZERO
        return peak.subtract(current).divide(peak, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    companion object {
        fun fromCurve(samples: List<BigDecimal>): BigDecimal {
            val acc = MaxDrawdownAccumulator()
            samples.forEach(acc::accept)
            return acc.value()
        }
    }
}

/**
 * Online maximum-drawdown accumulator: feed equity readings one at a time, read the worst peak-to-
 * trough drop seen so far at any point. Drawdown is `(peak − equity) / peak`, the largest such ratio
 * over the stream.
 *
 * Holds only the running peak and worst drop, so memory is constant no matter how many readings pass
 * through — matching what a one-pass [DrawdownTracker.fromCurve] over the whole curve would return.
 *
 * e.g. feed 100, 120, 90 → peak 120, trough 90 → max drawdown 0.25.
 */
class MaxDrawdownAccumulator {
    private var peak: BigDecimal = Money.ZERO
    private var maxDd: BigDecimal = Money.ZERO

    fun accept(equity: BigDecimal) {
        if (equity > peak) peak = equity
        if (peak.signum() > 0 && equity < peak) {
            val dd = peak.subtract(equity).divide(peak, Money.CONTEXT)
            if (dd > maxDd) maxDd = dd
        }
    }

    /** Worst drawdown seen so far, scaled to money precision. Non-destructive. */
    fun value(): BigDecimal = maxDd.setScale(Money.SCALE, Money.ROUNDING)
}
