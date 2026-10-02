package com.qkt.risk

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider

/**
 * A risk rule that can judge several orders as one position, such as the legs of an option structure
 * whose margin only makes sense with every leg filled. Rules that are not group-aware judge each leg
 * on its own ([RiskEngine.approveGroup]).
 */
interface GroupAwareRule : RiskRule {
    /** Whether [requests], all filled together, may go to the venue. */
    fun evaluateGroup(
        requests: List<OrderRequest>,
        positions: PositionProvider,
    ): Decision
}
