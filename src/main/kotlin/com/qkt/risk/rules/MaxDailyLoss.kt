package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal

/**
 * Global halt rule: trips when account-wide realized loss for the current UTC day
 * exceeds [maxLoss]. [maxLoss] is the magnitude (positive number); the rule compares
 * `-realized` against it. STRICTLY greater: a loss of exactly [maxLoss] continues; the
 * halt fires on the first loss past it. Daily scope — auto-resumes at the next UTC midnight.
 *
 * REALIZED-ONLY: this rule is fill-driven and never sees open positions bleeding
 * intraday — a position down $5k all day with a $1k limit halts nothing until it
 * closes. To halt on open losses too, configure `max_daily_drawdown_pct` with
 * `daily_dd_basis: equity` ([MaxDailyDrawdown] via the equity-based tracker), which
 * marks open PnL on every tick.
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
