package com.qkt.risk

import com.qkt.common.Money
import java.math.BigDecimal

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
}
