package com.qkt.risk.book

import java.math.BigDecimal

/**
 * Maps current book drawdown (a fraction, e.g. 0.05 = 5%) to a de-risk factor in [0, 1] via an
 * ordered ladder. The factor is the deepest rung whose drawdown threshold is breached, or 1.0 above
 * the shallowest. A breached factor-0 rung with `cooldownBars` holds the factor at 0 for that many
 * subsequent samples after drawdown recovers, so a kill rung does not immediately re-arm new risk on
 * a brief bounce. [factorFor] is stateful (advances the cooldown) — call it once per sample.
 */
class DeRiskLadder(
    rungs: List<Rung>,
) {
    private val sorted = rungs.sortedBy { it.drawdown }
    private var cooldownRemaining = 0

    fun factorFor(drawdown: BigDecimal): BigDecimal {
        var factor = BigDecimal.ONE
        var cooldownBars = 0
        for (r in sorted) {
            if (drawdown >= r.drawdown) {
                factor = r.factor
                cooldownBars = r.cooldownBars ?: 0
            }
        }
        if (factor.signum() == 0) {
            cooldownRemaining = cooldownBars
            return BigDecimal.ZERO
        }
        if (cooldownRemaining > 0) {
            cooldownRemaining -= 1
            return BigDecimal.ZERO
        }
        return factor
    }
}
