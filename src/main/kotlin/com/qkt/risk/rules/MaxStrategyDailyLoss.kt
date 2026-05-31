package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal

/**
 * Per-strategy variant of [MaxDailyLoss]: halts only [strategyId] when its own
 * realized loss for the current UTC day exceeds [maxLoss]. Other strategies on
 * the account continue trading.
 */
class MaxStrategyDailyLoss(
    private val strategyId: String,
    private val maxLoss: BigDecimal,
) : HaltRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxLoss.signum() > 0) { "maxLoss must be > 0: $maxLoss" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val realized = riskState.dailyPnLTracker.realizedToday(strategyId)
        return if (realized.negate() > maxLoss) {
            HaltDecision.Halt(
                reason = "strategy daily loss ${realized.negate()} exceeds max $maxLoss",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
