package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Global halt rule: trips when account-aggregate drawdown for the current UTC day exceeds
 * [maxFraction] (in `(0, 1]` — `0.04` halts at a 4% daily drawdown). The day-start reference and
 * its basis (balance vs equity) live in [RiskState.dailyDrawdownTracker].
 */
class MaxDailyDrawdown(
    private val maxFraction: BigDecimal,
) : HaltRule {
    init {
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.dailyDrawdownTracker.globalDrawdownToday()
        return if (dd > maxFraction) {
            HaltDecision.Halt("daily drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction")
        } else {
            HaltDecision.Continue
        }
    }
}
