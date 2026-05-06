package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider

class RiskEngine(
    private val rules: List<RiskRule>,
    private val positions: PositionProvider,
) {
    fun approve(request: OrderRequest): Decision {
        for (rule in rules) {
            val decision = rule.evaluate(request, positions)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }
}
