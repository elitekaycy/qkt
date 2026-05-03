package com.qkt.risk.rules

import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

class MaxPositionSize(
    private val symbol: String,
    private val maxQty: Double,
) : RiskRule {
    init {
        require(maxQty > 0.0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision {
        if (order.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: 0.0
        val projected = if (order.side == Side.BUY) current + order.quantity else current - order.quantity
        return if (kotlin.math.abs(projected) <= maxQty) {
            Decision.Approve
        } else {
            Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
        }
    }
}
