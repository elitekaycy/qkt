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
            var peak = Money.ZERO
            var maxDd = Money.ZERO
            for (e in samples) {
                if (e > peak) peak = e
                if (peak.signum() > 0 && e < peak) {
                    val dd = peak.subtract(e).divide(peak, Money.CONTEXT)
                    if (dd > maxDd) maxDd = dd
                }
            }
            return maxDd.setScale(Money.SCALE, Money.ROUNDING)
        }
    }
}
