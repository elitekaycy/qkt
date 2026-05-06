package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider

interface RiskRule {
    fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision
}

sealed class Decision {
    data object Approve : Decision()

    data class Reject(
        val reason: String,
    ) : Decision()
}
