package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Phase 25D: rejects orders that would push a strategy's net position on a symbol
 * past [maxQty]. Consults [StrategyPositionTracker] for filled strategy positions and the
 * supplied [PositionProvider] for that strategy's pending entry exposure.
 */
class MaxStrategyPositionSize(
    private val strategyId: String,
    private val maxQty: BigDecimal,
    private val strategyPositions: StrategyPositionTracker,
) : RiskRule {
    init {
        require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
        require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (request.strategyId != strategyId) return Decision.Approve
        val current =
            strategyPositions.positionFor(strategyId, request.symbol)?.quantity ?: Money.ZERO
        val pending = positions.pendingOrderQuantity(request.symbol, request.side, strategyId)
        val projected =
            if (request.side == Side.BUY) {
                current.add(pending).add(request.quantity)
            } else {
                current.subtract(pending).subtract(request.quantity)
            }
        return if (projected.abs().compareTo(maxQty) <= 0) {
            Decision.Approve
        } else {
            Decision.Reject(
                "MaxStrategyPositionSize[$strategyId]: |$projected| > $maxQty for ${request.symbol} " +
                    "(pending=$pending)",
            )
        }
    }
}
