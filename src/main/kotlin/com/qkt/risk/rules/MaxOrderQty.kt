package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Per-order quantity cap — the first mandatory pre-trade control (FIA §1.1): no
 * single order may exceed [maxQty] units/lots, whatever the strategy asked for.
 * This is the backstop for sizing bugs; a 100x oversize stops HERE, not at the venue.
 */
class MaxOrderQty(
    private val maxQty: BigDecimal,
) : RiskRule {
    init {
        require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision =
        if (request.quantity > maxQty) {
            Decision.Reject(
                "order qty ${request.quantity.toPlainString()} exceeds per-order cap ${maxQty.toPlainString()}",
            )
        } else {
            Decision.Approve
        }
}
