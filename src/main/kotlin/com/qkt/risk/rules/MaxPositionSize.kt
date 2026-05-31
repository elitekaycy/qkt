package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Per-request cap on the absolute size of a single-symbol position. Rejects any
 * order whose projected post-fill |quantity| would exceed [maxQty] on [symbol].
 * Orders for other symbols pass through unchanged.
 */
class MaxPositionSize(
    private val symbol: String,
    private val maxQty: BigDecimal,
) : RiskRule {
    init {
        require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (request.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: Money.ZERO
        val projected =
            if (request.side == Side.BUY) current.add(request.quantity) else current.subtract(request.quantity)
        return if (projected.abs().compareTo(maxQty) <= 0) {
            Decision.Approve
        } else {
            Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
        }
    }
}
