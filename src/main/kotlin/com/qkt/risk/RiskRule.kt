package com.qkt.risk

import com.qkt.execution.Order
import com.qkt.positions.PositionProvider

interface RiskRule {
    fun evaluate(
        order: Order,
        positions: PositionProvider,
    ): Decision
}

sealed class Decision {
    data object Approve : Decision()

    data class Reject(
        val reason: String,
    ) : Decision()
}
