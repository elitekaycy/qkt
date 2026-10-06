package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.SequentialIdGenerator
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.events.OrderEvent
import com.qkt.execution.allIds
import com.qkt.persistence.OrderIdPersistence
import com.qkt.positions.StrategyLegReads
import com.qkt.strategy.Strategy

/**
 * Keeps a strategy's order ids from ever repeating across restarts of the same state: each restarted
 * strategy's sequence, and the session's [SequentialIdGenerator], continue past the last id each generator
 * issued before (persisted in [OrderIdPersistence] at every submit) and past the ids of the orders and legs
 * the restart restored. Without the persisted marks a session restarted flat restored nothing and minted
 * `dsl-<name>--0` again (#1338); without the restored ids the next submit could collide with a live order.
 */
internal object OrderIdResumption {
    /**
     * Resumes every strategy of [strategies] past its persisted marks and its restored [orders] and [legs],
     * then records the marks on [bus] at every order a strategy submits from here on.
     */
    fun resume(
        strategies: List<Pair<String, Strategy>>,
        orders: OrderManager,
        legs: StrategyLegReads,
        sessionIds: SequentialIdGenerator,
        store: OrderIdPersistence,
        bus: EventBus,
    ) {
        val generators = strategies.associate { (id, strategy) -> id to generatorsOf(strategy, sessionIds) }
        for ((strategyId, strategy) in strategies) {
            val marks = store.loadOrderIdMarks(strategyId)
            generators.getValue(strategyId).forEach { gen -> marks[gen.prefix]?.let(gen::resumeAfter) }
            val usedIds =
                orders
                    .activeOrders()
                    .filter { it.request.strategyId == strategyId }
                    .flatMap { it.request.allIds() + it.id } +
                    legs.allLegsFor(strategyId).map { it.legId }
            (strategy as? DslCompiledStrategy)?.resumeOrderIds(usedIds)
            sessionIds.resumePast(usedIds)
        }
        val saved = HashMap<String, Map<String, Long>>()
        bus.subscribe<OrderEvent> { e ->
            val strategyId = e.request.strategyId
            val marks =
                generators[strategyId]
                    ?.mapNotNull { gen ->
                        gen.lastIssued()?.let { gen.prefix to it }
                    }?.toMap()
            if (marks != null && marks.isNotEmpty() && saved[strategyId] != marks) {
                store.saveOrderIdMarks(strategyId, marks)
                saved[strategyId] = marks
            }
        }
    }

    private fun generatorsOf(
        strategy: Strategy,
        sessionIds: SequentialIdGenerator,
    ): List<SequentialIdGenerator> = listOfNotNull((strategy as? DslCompiledStrategy)?.orderIds, sessionIds)
}
