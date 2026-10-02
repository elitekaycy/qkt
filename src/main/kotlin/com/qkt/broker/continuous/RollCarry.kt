package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Carries one strategy's position across a roll with two market legs on [venue] ([CarryLegs]): close on
 * the old contract, then open on the new one, each sent once the venue answered the one before, through
 * the explicit [CarryStep]s of the run (each leg held by [gate] while a restarted session is not ready).
 * An opening leg refused or ended leaves the position closed on the venue (any part of it that filled is
 * unwound): its close at the old leg's fill is recorded as the stream's [ExitReason.ROLL_FAILED] exit. A
 * closing leg ended part-filled closes only that part. A refused closing leg is a configuration fault and
 * fails loudly.
 */
internal class RollCarry(
    clock: Clock,
    private val chainOf: () -> ContinuousChain,
    venue: ContractVenue,
    legs: RollLegs,
    private val fills: ContractFillLog,
    gate: (() -> Unit) -> Unit,
) {
    private val log = LoggerFactory.getLogger(RollCarry::class.java)
    private val legOrders = CarryLegs(clock, chainOf, venue, legs, gate)

    /** The chain as it stands now: a live session extends it with each roll it measures. */
    private val chain: ContinuousChain get() = chainOf()

    /**
     * Starts carrying [strategyId]'s signed [quantity] to the new contract, and calls [then] once its
     * carry has ended (carried, or stopped: the new contract refused or ended the opening leg, any part
     * of it that filled is unwound and the position closes on the stream at the old leg's fill; or the
     * closing leg ended part-filled, which closes only that part).
     */
    fun start(
        strategyId: String,
        quantity: BigDecimal,
        run: RollRun,
        then: () -> Unit,
    ) {
        val closing =
            CarryStep.Closing(
                strategyId,
                quantity,
                legOrders.market(run, strategyId, "close", run.fromIndex, quantity.negate()),
            )
        step(run, closing)
        legOrders.send(closing.leg) { closed(run, closing, it, then) }
    }

    /** Waits again for the leg [step] waits for (after a restart, the leg restored); calls [then] once its carry ends. */
    fun resume(
        step: CarryStep.Waiting,
        run: RollRun,
        then: () -> Unit,
    ) = when (step) {
        is CarryStep.Closing -> legOrders.await(step.leg) { closed(run, step, it, then) }
        is CarryStep.Opening -> legOrders.await(step.leg) { opened(run, step, it, then) }
    }

    /** Waits again for the unwind [leg] (after a restart, the leg restored), as when it was sent. */
    fun resumeUnwind(leg: OrderRequest.Market) = legOrders.awaitUnwind(leg)

    private fun closed(
        run: RollRun,
        step: CarryStep.Closing,
        outcome: LegOutcome,
        then: () -> Unit,
    ) {
        when (outcome) {
            is LegOutcome.Rejected ->
                error(
                    "${step.leg.symbol} refused the closing leg ${step.leg.id}: ${outcome.reason}",
                )
            is LegOutcome.Partial -> {
                log.error(
                    "{} closed {} of {} for {}: {}",
                    step.leg.symbol,
                    outcome.fill.quantity,
                    step.leg.quantity,
                    step.strategyId,
                    outcome.reason,
                )
                stop(run, step, outcome.reason, outcome.fill, then)
            }
            is LegOutcome.Filled -> {
                val opening =
                    CarryStep.Opening(
                        step.strategyId,
                        step.quantity,
                        outcome.fill,
                        legOrders.market(run, step.strategyId, "open", run.toIndex, step.quantity),
                    )
                step(run, opening)
                legOrders.send(opening.leg) { opened(run, opening, it, then) }
            }
        }
    }

    private fun opened(
        run: RollRun,
        step: CarryStep.Opening,
        outcome: LegOutcome,
        then: () -> Unit,
    ) {
        when (outcome) {
            is LegOutcome.Filled -> {
                check(outcome.fill.quantity.compareTo(step.leg.quantity) == 0) {
                    "roll leg ${step.leg.id} filled ${outcome.fill.quantity} of ${step.leg.quantity}"
                }
                step(run, CarryStep.Carried(step.strategyId, step.quantity, step.close, outcome.fill))
                then()
            }
            is LegOutcome.Rejected -> {
                log.error(
                    "{} refused the roll of {} for {}: {}",
                    step.leg.symbol,
                    chain.symbol,
                    step.strategyId,
                    outcome.reason,
                )
                stop(run, step, outcome.reason, step.close, then)
            }
            is LegOutcome.Partial -> {
                log.error(
                    "{} opened {} of {} for {}: {}; unwinding it",
                    step.leg.symbol,
                    outcome.fill.quantity,
                    step.leg.quantity,
                    step.strategyId,
                    outcome.reason,
                )
                legOrders.unwind(step.leg, outcome.fill.quantity)
                stop(run, step, outcome.reason, step.close, then)
            }
        }
    }

    /** Ends [step]'s carry for [reason], closing the position on the stream at the venue's [close]. */
    private fun stop(
        run: RollRun,
        step: CarryStep,
        reason: String,
        close: BrokerEvent.OrderFilled,
        then: () -> Unit,
    ) {
        val onStream = closeOnStream(close, "${legOrders.base(run, step.strategyId)}:failed", run.fromIndex)
        fills.record(contractFill(close, onStream))
        step(run, CarryStep.Stopped(step.strategyId, step.quantity, reason, onStream))
        then()
    }

    private fun step(
        run: RollRun,
        step: CarryStep,
    ) {
        run.steps[step.strategyId] = step
    }

    /** The ledger's record of [carried] in [run]. */
    fun entry(
        run: RollRun,
        carried: CarryStep.Carried,
    ) = RollEntry(
        atMs = run.measured.atMs,
        stream = chain.symbol,
        strategyId = carried.strategyId,
        from = carried.close.symbol,
        to = carried.open.symbol,
        quantity = carried.quantity,
        multiplier = chain.root.multiplier,
        fromFill = carried.close.price,
        toFill = carried.open.price,
        fromReference = run.measured.prices.fromPrice,
        toReference = run.measured.prices.toPrice,
        fees = carried.close.venueFeesIn(chain.root.currency).add(carried.open.venueFeesIn(chain.root.currency)),
    )

    /** The old leg's [close] as the venue closing the position on the stream. */
    private fun closeOnStream(
        close: BrokerEvent.OrderFilled,
        id: String,
        fromIndex: Int,
    ): BrokerEvent.OrderFilled =
        close.copy(
            clientOrderId = id,
            brokerOrderId = id,
            symbol = chain.symbol,
            price = chain.spaceFor(fromIndex).toContinuous(close.price),
            updatesOrderExecution = false,
            exitReason = ExitReason.ROLL_FAILED,
        )
}
