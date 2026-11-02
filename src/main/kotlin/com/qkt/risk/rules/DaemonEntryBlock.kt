package com.qkt.risk.rules

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.DaemonRiskScope
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing
import java.math.BigDecimal

/**
 * Refuses a new entry while the daemon's hard limit stands or its mixed total is crossed, at the order. A session's
 * halt rules run after its strategy on each tick, so a session just resumed or just deployed would otherwise send one
 * entry on its first tick; on a day with neither limit in play this is two volatile reads. Exits always pass.
 */
class DaemonEntryBlock(
    private val maxLoss: BigDecimal,
    private val scope: DaemonRiskScope,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val reason = scope.entryBlockReason(maxLoss) ?: return Decision.Approve
        return if (isRiskReducing(request, positions)) Decision.Approve else Decision.Reject("halted: $reason")
    }
}
