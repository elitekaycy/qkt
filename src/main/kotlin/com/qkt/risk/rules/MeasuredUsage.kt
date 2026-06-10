package com.qkt.risk.rules

import com.qkt.common.Clock
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing
import java.math.BigDecimal

/**
 * Measured-usage window (FIA §5.3.9): for [windowHours] after a deploy, NEW-exposure
 * orders larger than [maxQty] are rejected, so a fresh strategy version (or engine
 * image) meets production at minimum size instead of full exposure — Knight is the
 * canonical case of skipping this. Risk-reducing orders always pass; the window lifts
 * itself at expiry. Every session start counts as a deploy on purpose: a restart
 * re-entering measured mode is conservative, never dangerous.
 */
class MeasuredUsage(
    private val clock: Clock,
    startedAtMs: Long,
    windowHours: Long,
    private val maxQty: BigDecimal = DEFAULT_MEASURED_MAX_QTY,
) : RiskRule {
    private val untilMs: Long = startedAtMs + windowHours * 3_600_000L

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (clock.now() >= untilMs) return Decision.Approve
        if (isRiskReducing(request, positions)) return Decision.Approve
        return if (request.quantity > maxQty) {
            Decision.Reject(
                "measured-usage window active until epoch ${untilMs}ms: order qty " +
                    "${request.quantity.toPlainString()} exceeds the validation cap " +
                    "${maxQty.toPlainString()} (set risk.measured_usage_hours: 0 to opt out)",
            )
        } else {
            Decision.Approve
        }
    }

    companion object {
        /** The venue minimum for most MT5 FX/metal instruments. */
        val DEFAULT_MEASURED_MAX_QTY: BigDecimal = BigDecimal("0.01")
    }
}
