package com.qkt.risk.rules

import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal

/**
 * [MaxDailyLoss] over all of a daemon's sessions: trips every session once their realized loss
 * for the UTC day, summed in [scope], exceeds [maxLoss]. Realized only and strictly greater, as
 * [MaxDailyLoss]; daily scope, so each session resumes at the next UTC midnight.
 */
class DaemonDailyLoss(
    private val session: String,
    private val maxLoss: BigDecimal,
    private val scope: DaemonRiskScope,
) : HaltRule {
    init {
        require(maxLoss.signum() > 0) { "maxLoss must be > 0: $maxLoss" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val loss = scope.realizedToday(session, riskState.dailyPnLTracker.globalRealizedToday()).negate()
        return if (loss > maxLoss) {
            HaltDecision.Halt("daily loss $loss exceeds max $maxLoss", scope = HaltScope.DAILY)
        } else {
            HaltDecision.Continue
        }
    }
}
