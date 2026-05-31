package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Per-strategy variant of [MaxDrawdown]: halts only [strategyId] when its own
 * drawdown fraction exceeds [maxFraction]. Other strategies on the account continue.
 */
class MaxStrategyDrawdown(
    private val strategyId: String,
    private val maxFraction: BigDecimal,
) : HaltRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.drawdownTracker.strategyDrawdown(strategyId)
        return if (dd > maxFraction) {
            HaltDecision.Halt(
                reason = "strategy drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
