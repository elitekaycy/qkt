package com.qkt.risk.rules

import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Global halt rule: trips when account-aggregate drawdown exceeds [maxFraction] (in `(0, 1]` —
 * `0.20` halts at 20%). [basis] selects trailing (peak-relative) or static (vs [initialBalance],
 * the prop-firm "max loss from starting balance") measurement.
 */
class MaxDrawdown(
    private val maxFraction: BigDecimal,
    private val basis: DrawdownBasis = DrawdownBasis.STATIC,
    private val initialBalance: BigDecimal = BigDecimal.ZERO,
) : HaltRule {
    init {
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd =
            when (basis) {
                DrawdownBasis.TRAILING -> riskState.drawdownTracker.globalDrawdown()
                DrawdownBasis.STATIC -> riskState.drawdownTracker.globalStaticDrawdown(initialBalance)
            }
        return if (dd > maxFraction) {
            HaltDecision.Halt(
                "global drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction",
            )
        } else {
            HaltDecision.Continue
        }
    }
}
