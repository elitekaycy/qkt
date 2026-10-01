package com.qkt.broker.continuous

import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.persistence.PersistedCarryStep
import com.qkt.persistence.PersistedContractHolding
import com.qkt.persistence.PersistedRollHolder
import com.qkt.persistence.PersistedRollLeg
import com.qkt.persistence.PersistedStreamLane
import com.qkt.persistence.PersistedStreamOrder
import com.qkt.persistence.PersistedStreamRoll
import com.qkt.persistence.PersistedStreamStrategy
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal

/**
 * One lane's state as it is persisted ([PersistedStreamLane]), read from the lane's own parts whenever
 * it is saved, so the record can never drift from what the lane holds: each strategy's position and stop
 * on the stream, the engine orders, the contract [book], the roll's legs and awaited cancels, and the roll
 * in flight.
 */
internal class LaneState(
    private val chainOf: () -> ContinuousChain,
    private val positions: Map<String, BigDecimal>,
    private val stops: Map<String, String>,
    private val orders: ContinuousOrderMap,
    private val book: StrategyPositionTracker,
    private val legs: RollLegs,
    private val rolls: RollExecutor,
) {
    /** The lane as it stands, on contract [contractIndex] (null before its first). */
    fun snapshot(contractIndex: Int?) =
        PersistedStreamLane(
            stream = chainOf().symbol,
            contractIndex = contractIndex,
            strategies =
                (positions.keys + stops.keys).map {
                    PersistedStreamStrategy(
                        it,
                        positions[it] ?: BigDecimal.ZERO,
                        stops[it],
                    )
                },
            orders = orders.all.map(::persisted),
            holdings = holdings(),
            legs = legs.inFlight.map { PersistedRollLeg(it.leg, it.slices) },
            cancelling = legs.awaitedCancels,
            roll = rolls.run?.let(::persisted),
        )

    private fun holdings(): List<PersistedContractHolding> =
        book.allByStrategy().flatMap { (strategyId, bySymbol) ->
            bySymbol.values
                .filter { it.quantity.signum() != 0 }
                .map { PersistedContractHolding(strategyId, it.symbol, it.quantity, it.avgEntryPrice, it.openedAt) }
        }

    private fun persisted(order: ContinuousOrder) =
        PersistedStreamOrder(order.request, order.venueId, order.contractIndex, order.replacements, order.filled)

    private fun persisted(run: RollRun) =
        PersistedStreamRoll(
            run.fromIndex,
            run.toIndex,
            run.measured.atMs,
            run.measured.prices.fromPrice,
            run.measured.prices.toPrice,
            run.stopped,
            run.resting.map(::persisted),
            run.holders.map { PersistedRollHolder(it.key, it.value) },
            run.steps.values.map(::persisted),
        )

    private fun persisted(step: CarryStep): PersistedCarryStep =
        when (step) {
            is CarryStep.Closing -> PersistedCarryStep.Closing(step.strategyId, step.quantity, step.leg)
            is CarryStep.Opening -> PersistedCarryStep.Opening(step.strategyId, step.quantity, step.close, step.leg)
            is CarryStep.Carried -> PersistedCarryStep.Carried(step.strategyId, step.quantity, step.close, step.open)
            is CarryStep.Stopped -> PersistedCarryStep.Stopped(step.strategyId, step.quantity, step.reason, step.close)
        }
}
