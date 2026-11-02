package com.qkt.risk.rules

import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal

/**
 * A portfolio child's share of the daemon's total: reports its realized P&L for the UTC day, as a standalone's
 * [DaemonDailyLoss] does, and halts this child on a mixed day once the total exceeds [maxLoss]. The halt blocks
 * entries and leaves positions and stops alone. It reports from the child's engine, not the book's risk heartbeat,
 * which stops evaluating once the book is halted; a figure from there would stand after a resume, and its fill would
 * be up to a heartbeat late. With no standalone in the daemon this only reports, and the book's own rule decides.
 */
class BookChildDailyLoss(
    private val session: String,
    private val maxLoss: BigDecimal,
    private val scope: DaemonRiskScope,
) : HaltRule {
    override fun evaluate(riskState: RiskState): HaltDecision {
        val loss = scope.realizedToday(session, riskState.dailyPnLTracker.globalRealizedToday()).negate()
        return if (loss > maxLoss && scope.composition.mixed()) {
            HaltDecision.Halt("daily loss $loss exceeds max $maxLoss across the daemon", scope = HaltScope.DAILY)
        } else {
            HaltDecision.Continue
        }
    }
}
