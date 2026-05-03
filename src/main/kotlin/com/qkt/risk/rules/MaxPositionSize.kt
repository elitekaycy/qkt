package com.qkt.risk.rules

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

class MaxPositionSize(
    private val symbol: String,
    private val maxQty: BigDecimal,
) : RiskRule {
    init {
        require(maxQty.signum() > 0) { "maxQty must be > 0: $maxQty" }
    }

    override fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision {
        if (order.symbol != symbol) return Decision.Approve
        val current = positions.positionFor(symbol)?.quantity ?: Money.ZERO
        val projected =
            if (order.side == Side.BUY) current.add(order.quantity) else current.subtract(order.quantity)
        return if (projected.abs().compareTo(maxQty) <= 0) {
            Decision.Approve
        } else {
            Decision.Reject("MaxPositionSize: |$projected| > $maxQty for $symbol")
        }
    }
}
