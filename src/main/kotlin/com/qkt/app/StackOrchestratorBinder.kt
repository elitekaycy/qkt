package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.StackOrchestrator
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.persistence.StatePersistor
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import org.slf4j.LoggerFactory

/**
 * Phase 27: per-DSL-strategy stack lifecycle. The orchestrator owns one [com.qkt.dsl.compile.StackEngine]
 * per active PRIMARY leg with `STACK_AT` clauses. On parent-fill it consumes the
 * matching [com.qkt.dsl.compile.PendingStack] populated by the action compiler.
 * Stack-emitted signals go through the same `emit` path as user-emitted signals so
 * risk / ordering / id allocation behave uniformly.
 *
 * Parent close detection: when an [BrokerEvent.OrderFilled] for the strategy is NOT
 * a known primary entry (no pending entry to consume), it's treated as a possible
 * close — engines watching that id terminate. The action compiler predicts a
 * Bracket parent's TP/SL ids using OrderManager's deterministic naming. Native
 * broker brackets and manual closes are not yet covered.
 */
internal class StackOrchestratorBinder(
    private val clock: Clock,
    private val bus: EventBus,
    private val persistor: StatePersistor,
    private val strategyPositions: StrategyPositionTracker,
) {
    // Logged under the pipeline's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    /** Build [strategy]'s orchestrator, restore persisted tiers, and subscribe it to ticks and fills. */
    fun bind(
        strategy: DslCompiledStrategy,
        strategyId: String,
        emit: (Signal) -> Unit,
    ) {
        val orch =
            StackOrchestrator(
                clock = clock,
                emit = emit,
                strategyId = strategyId,
                persistor = persistor,
            )
        // Restart path: rebuild engines for parents that were open when the process
        // died — the restored leg supplies identity, the persisted tier state supplies
        // thresholds, windows, progress, and the original open-time anchor (#390).
        runCatching {
            for ((parentLegId, state) in persistor.loadPendingStacks(strategyId)) {
                val leg = strategyPositions.legById(strategyId, parentLegId) ?: continue
                orch.restoreEngine(
                    parentLegId = parentLegId,
                    parentSymbol = leg.symbol,
                    parentSide = leg.side,
                    parentEntryPrice = leg.entryPrice,
                    persisted = state,
                )
            }
        }.onFailure { e -> log.warn("stack tier restore failed for {}: {}", strategyId, e.message) }
        bus.subscribe<TickEvent> { e -> orch.onTick(e.tick.symbol, e.tick.price) }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            if (e.strategyId != strategyId) return@subscribe
            val pending = strategy.pendingStacks.consume(e.clientOrderId)
            if (pending != null) {
                orch.onPrimaryFilled(
                    parentLegId = pending.parentClientOrderId,
                    parentSymbol = pending.symbol,
                    parentSide = pending.side,
                    parentEntryPrice = e.price,
                    parentQty = e.quantity,
                    tiers = pending.tiers,
                    closeWatchIds = pending.closeWatchIds,
                    exitAfterMs = pending.exitAfterMs,
                )
            } else {
                orch.onPossibleClose(e.clientOrderId)
            }
        }
    }
}
