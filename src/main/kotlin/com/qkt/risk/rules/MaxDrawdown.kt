package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Global halt rule: trips when account-aggregate drawdown (as a fraction of peak
 * equity) exceeds [maxFraction]. Threshold must be in `(0, 1]` — `0.20` means halt
 * at a 20% drawdown.
 */
class MaxDrawdown(
    private val maxFraction: BigDecimal,
) : HaltRule {
    init {
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.drawdownTracker.globalDrawdown()
        return if (dd > maxFraction) {
            HaltDecision.Halt(
                "global drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction",
            )
        } else {
            HaltDecision.Continue
        }
    }
}
