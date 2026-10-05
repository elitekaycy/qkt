package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing

/**
 * Refuses every order that does not reduce a position while [belowMaintenance] holds: a backtest's
 * account whose equity is still below the maintenance margin of its positions after a liquidation
 * ([com.qkt.broker.liquidation.MarginLiquidator]) takes no new risk until it is restored.
 */
class MaintenanceMarginGate(
    private val belowMaintenance: () -> Boolean,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision =
        if (!belowMaintenance() || isRiskReducing(request, positions)) {
            Decision.Approve
        } else {
            Decision.Reject(
                "account equity is below maintenance margin: only orders that reduce a position are accepted",
            )
        }
}
