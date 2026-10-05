package com.qkt.app

import com.qkt.common.SequentialIdGenerator
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.execution.allIds
import com.qkt.positions.StrategyLegReads
import com.qkt.strategy.Strategy

/**
 * Continues each restarted strategy's order-id sequence, and the session's [sessionIds], past the ids
 * the strategy minted before the restart, so the next submit never collides with one of them.
 */
internal object OrderIdResumption {
    /** Resumes every DSL strategy of [strategies] past the ids of its restored [orders] and [legs]. */
    fun resume(
        strategies: List<Pair<String, Strategy>>,
        orders: OrderManager,
        legs: StrategyLegReads,
        sessionIds: SequentialIdGenerator,
    ) {
        for ((strategyId, strategy) in strategies) {
            val dsl = strategy as? DslCompiledStrategy ?: continue
            val usedIds =
                orders
                    .activeOrders()
                    .filter { it.request.strategyId == strategyId }
                    .flatMap { it.request.allIds() + it.id } +
                    legs.allLegsFor(strategyId).map { it.legId }
            dsl.resumeOrderIds(usedIds)
            sessionIds.resumePast(usedIds)
        }
    }
}
