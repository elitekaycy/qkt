package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider

/**
 * One pre-trade check. Implementations are stateless w.r.t. the rule itself —
 * any state they need (drawdown, daily P&L) comes through the [PositionProvider]
 * or the rule's own constructor-injected trackers, never via mutation in [evaluate].
 */
interface RiskRule {
    /** Approve [request] or reject it with a human-readable reason. */
    fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision
}

/** Approve/reject verdict from a [RiskRule] or [RiskEngine]. */
sealed class Decision {
    /** Request passes this check. */
    data object Approve : Decision()

    /** Request blocked; [reason] is surfaced to the operator and trade log. */
    data class Reject(
        val reason: String,
    ) : Decision()
}
