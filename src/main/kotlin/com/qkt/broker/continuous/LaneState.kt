package com.qkt.broker.continuous

import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.execution.OrderRequest
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
 * in flight. A restart restores the lane from that record ([restore]).
 */
internal class LaneState(
    private val chainOf: () -> ContinuousChain,
    private val positions: MutableMap<String, BigDecimal>,
    private val stops: MutableMap<String, String>,
    private val orders: ContinuousOrderMap,
    private val book: StrategyPositionTracker,
    private val legs: RollLegs,
    private val rolls: RollExecutor,
) {
    /** The lane as it stands, on contract [contractIndex] (null before its first). */
    fun snapshot(contractIndex: Int?) =
        PersistedStreamLane(
            stream = chainOf().symbol,
            contract = contractIndex?.let(chainOf()::contractSymbol),
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
            cancelling = legs.awaitedCancels.map(::persisted),
            roll = rolls.run?.let(::persisted),
        )

    /**
     * Puts [saved] back into the lane's parts at [nowMs]; returns the contract the lane is on, the roll in
     * flight and the unwinds still out. Fails when the chain no longer lists a saved contract or measures
     * the saved roll differently: the lane cannot resume on prices it did not trade at.
     */
    fun restore(
        saved: PersistedStreamLane,
        nowMs: Long,
    ): RestoredLane {
        val chain = chainOf()
        require(saved.stream == chain.symbol) { "${chain.symbol} cannot restore the lane of ${saved.stream}" }
        for (strategy in saved.strategies) {
            positions[strategy.strategyId] = strategy.position
            strategy.stopped?.let { stops[strategy.strategyId] = it }
        }
        saved.orders.forEach { orders.add(order(it)) }
        for (h in saved.holdings) {
            book.reconcileNet(
                h.contract,
                h.quantity,
                h.avgPrice,
                h.openedAt ?: nowMs,
                "restart",
                strategyId = h.strategyId,
            )
        }
        saved.legs.forEach { legs.expect(it.leg, it.slices) }
        saved.cancelling.forEach { legs.cancelling(order(it)) }
        val run = saved.roll?.let(::run)
        val awaited =
            run
                ?.steps
                ?.values
                ?.filterIsInstance<CarryStep.Waiting>()
                ?.map { it.leg.id }
                .orEmpty()
        return RestoredLane(saved.contract?.let(::index), run, saved.legs.map { it.leg }.filter { it.id !in awaited })
    }

    private fun index(contract: String): Int =
        requireNotNull(
            chainOf().indexOf(contract),
        ) { "${chainOf().symbol} saved $contract, which its chain no longer lists" }

    private fun order(saved: PersistedStreamOrder) =
        ContinuousOrder(
            saved.request,
            saved.venueId,
            index(saved.contract),
            saved.replacements,
            saved.placed,
            saved.filled,
        )

    private fun run(saved: PersistedStreamRoll): RollRun {
        val from = index(saved.from)
        val measured = chainOf().rollOutOf(from)
        require(
            measured.atMs == saved.atMs &&
                measured.prices.fromPrice.compareTo(saved.fromPrice) == 0 &&
                measured.prices.toPrice.compareTo(saved.toPrice) == 0,
        ) {
            "${chainOf().symbol} saved its roll out of ${saved.from} as ${saved.atMs} ${saved.fromPrice}->${saved.toPrice}, measured now as $measured"
        }
        val run =
            RollRun(
                from,
                index(saved.to),
                measured,
                saved.stopped,
                saved.resting.map(::order),
                saved.holders
                    .associate { it.strategyId to it.quantity }
                    .entries
                    .toList(),
            )
        saved.steps.forEach { run.steps[it.strategyId] = step(it) }
        return run
    }

    private fun step(saved: PersistedCarryStep): CarryStep =
        when (saved) {
            is PersistedCarryStep.Closing -> CarryStep.Closing(saved.strategyId, saved.quantity, saved.leg)
            is PersistedCarryStep.Opening -> CarryStep.Opening(saved.strategyId, saved.quantity, saved.close, saved.leg)
            is PersistedCarryStep.Carried ->
                CarryStep.Carried(
                    saved.strategyId,
                    saved.quantity,
                    saved.close,
                    saved.open,
                )
            is PersistedCarryStep.Stopped ->
                CarryStep.Stopped(
                    saved.strategyId,
                    saved.quantity,
                    saved.reason,
                    saved.close,
                )
        }

    private fun holdings(): List<PersistedContractHolding> =
        book.allByStrategy().flatMap { (strategyId, bySymbol) ->
            bySymbol.values
                .filter { it.quantity.signum() != 0 }
                .map { PersistedContractHolding(strategyId, it.symbol, it.quantity, it.avgEntryPrice, it.openedAt) }
        }

    private fun persisted(order: ContinuousOrder) =
        PersistedStreamOrder(
            order.request,
            order.venueId,
            chainOf().contractSymbol(order.contractIndex),
            order.replacements,
            order.placed,
            order.filled,
        )

    private fun persisted(run: RollRun) =
        PersistedStreamRoll(
            chainOf().contractSymbol(run.fromIndex),
            chainOf().contractSymbol(run.toIndex),
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

/** A lane put back after a restart: the contract it is on (null before its first), its roll in flight and its unwinds still out. */
internal data class RestoredLane(
    val contractIndex: Int?,
    val run: RollRun?,
    val unwinds: List<OrderRequest.Market>,
)
