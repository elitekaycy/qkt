package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

/**
 * Phase 25D: rejects new-symbol entries that would push the strategy's concurrent
 * position count past [maxCount]. Add-to-existing-position orders are always
 * approved by this rule — only "opening on a new symbol" counts toward the cap.
 */
class MaxStrategyOpenPositions(
    private val strategyId: String,
    private val maxCount: Int,
    private val strategyPositions: StrategyPositionTracker,
) : RiskRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxCount > 0) { "maxCount must be > 0: $maxCount" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (request.strategyId != strategyId) return Decision.Approve
        val openingNew = strategyPositions.positionFor(strategyId, request.symbol) == null
        if (!openingNew) return Decision.Approve
        val current = strategyPositions.positionsFor(strategyId).size
        return if (current < maxCount) {
            Decision.Approve
        } else {
            Decision.Reject(
                "MaxStrategyOpenPositions[$strategyId]: $current already open, max $maxCount",
            )
        }
    }
}
