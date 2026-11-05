package com.qkt.risk.rules

import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState

/**
 * A book's [MaxDailyLoss], which flattens and halts the book, when `max_daily_loss` is the book's own: not on a day
 * the daemon mixes standalones and portfolios, when the limit is the daemon's total and flattens nothing.
 */
class BookOwnDailyLoss(
    private val rule: HaltRule,
    private val scope: DaemonRiskScope,
) : HaltRule {
    override fun evaluate(riskState: RiskState): HaltDecision =
        if (scope.composition.mixed()) HaltDecision.Continue else rule.evaluate(riskState)
}
