package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

/**
 * Per-request cap on the total number of open positions across all symbols. Only
 * orders that would OPEN a new position (no existing position on the symbol) count
 * against the cap; adds/closes pass through. Useful for "max N concurrent bets".
 */
class MaxOpenPositions(
    private val maxCount: Int,
) : RiskRule {
    init {
        require(maxCount > 0) { "maxCount must be > 0: $maxCount" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val openingNew = positions.positionFor(request.symbol) == null
        if (!openingNew) return Decision.Approve
        val currentCount = positions.allPositions().size
        return if (currentCount < maxCount) {
            Decision.Approve
        } else {
            Decision.Reject("MaxOpenPositions: $currentCount already open, max $maxCount")
        }
    }
}
