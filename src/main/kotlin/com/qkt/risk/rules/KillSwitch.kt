package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState

class KillSwitch(
    private val riskState: RiskState,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision =
        if (riskState.isStrategyHalted(request.strategyId)) {
            val reason = riskState.haltReasonFor(request.strategyId) ?: "halted"
            Decision.Reject("kill switch: $reason")
        } else {
            Decision.Approve
        }
}
