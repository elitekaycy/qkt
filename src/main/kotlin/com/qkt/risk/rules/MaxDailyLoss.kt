package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal

/**
 * Global halt rule: trips when account-wide realized loss for the current UTC day
 * exceeds [maxLoss]. [maxLoss] is the magnitude (positive number); the rule compares
 * `-realized` against it. Daily scope — the halt auto-resumes at the next UTC midnight.
 */
class MaxDailyLoss(
    private val maxLoss: BigDecimal,
) : HaltRule {
    init {
        require(maxLoss.signum() > 0) { "maxLoss must be > 0: $maxLoss" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val realized = riskState.dailyPnLTracker.globalRealizedToday()
        return if (realized.negate() > maxLoss) {
            HaltDecision.Halt("daily loss ${realized.negate()} exceeds max $maxLoss", scope = HaltScope.DAILY)
        } else {
            HaltDecision.Continue
        }
    }
}
