package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * A roll leg's attempts on [venue]. With a [priceBand] (live), a leg an immediate-or-cancel end left unfilled, or the
 * venue never received, goes again under a new id, only what is left of it, up to [RollLegIds.MAX_ATTEMPTS] attempts
 * [RollLegIds.RETRY_SPACING_MS] apart and clear of the old contract's expiry. So does one a venue's price limit refused; any other refusal never does. While the spacing
 * runs the leg stays saved as out and its step names it, so a restart asks the venue again and goes on. A new id goes only
 * once the venue ended the last or never had it, so no two attempts can work at once.
 */
internal class LegAttempts(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val legs: RollLegs,
    private val priceBand: BigDecimal?,
    private val gate: (() -> Unit) -> Unit,
    private val orders: RollLegOrders,
) {
    private val log = LoggerFactory.getLogger(LegAttempts::class.java)

    /** Sends [leg] of [run] and hands its outcome to [then] once the venue answered and no attempt is left. */
    fun send(
        run: RollRun?,
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) {
        legs.expect(leg)
        submit(run, leg, then)
    }

    private fun submit(
        run: RollRun?,
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) = gate {
        venue.broker.submit(leg)
        watch(run, leg, then)
    }

    /** Hands the end of the out [leg] of [run] on, or sends its next attempt. */
    fun watch(
        run: RollRun?,
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) = legs.whenEnded(leg.id) { outcome ->
        if (retries(run, leg, outcome)) retry(run, leg, outcome, then) else then(outcome)
    }

    /** Whether [outcome] of [leg] goes again: live, not a refusal, attempts left, clear of the old contract's expiry. */
    private fun retries(
        run: RollRun?,
        leg: OrderRequest,
        outcome: LegOutcome,
    ): Boolean {
        val retryable = (outcome as? LegOutcome.Rejected)?.retryable ?: (outcome as? LegOutcome.Partial)?.retryable
        if (priceBand == null || retryable != true) return false
        val clear = run == null || chainOf().clearForRetry(run.fromIndex, clock.now())
        if (RollLegIds.attempt(leg.id) < RollLegIds.MAX_ATTEMPTS && clear) return true
        log.error("roll leg {} ended with no attempt left: {}", leg.id, outcome)
        return false
    }

    /** Keeps [leg] out while the spacing runs, so a restart asks the venue again, then sends what it left to fill. */
    private fun retry(
        run: RollRun?,
        leg: OrderRequest,
        outcome: LegOutcome,
        then: (LegOutcome) -> Unit,
    ) {
        val parked = legs.park()
        log.warn("roll leg {} ended unfilled: {}; going again", leg.id, outcome)
        legs.retries.after(clock.now() + RollLegIds.RETRY_SPACING_MS) {
            val done = (outcome as? LegOutcome.Partial)?.fill
            val before = parked.earlier.fold(BigDecimal.ZERO) { sum, fill -> sum + fill.quantity }
            val remainder = leg.quantity + before - (done?.quantity ?: BigDecimal.ZERO)
            val next = nextAttempt(run, leg, remainder)
            legs.drop(leg.id)
            (run?.steps?.get(leg.strategyId) as? CarryStep.Waiting)
                ?.takeIf { it.leg.id == leg.id }
                ?.let { run.steps[leg.strategyId] = it.at(next) }
            legs.expect(next, earlier = listOfNotNull(done))
            submit(run, next, then)
        }
    }

    /** The attempt after [leg]: collared around its contract's price, or at its own limit with no [run] to say that. */
    private fun nextAttempt(
        run: RollRun?,
        leg: OrderRequest,
        quantity: BigDecimal,
    ): OrderRequest {
        val now = clock.now()
        val id = RollLegIds.next(leg.id)
        val limit = (leg as? OrderRequest.Limit)?.limitPrice
        run?.refreshQuotes()
        val reference =
            run?.let { if (RollLegIds.kind(leg.id) == "close") it.fromReference(leg.side) else it.toReference }
        if (reference != null) return orders.order(id, leg.symbol, leg.side, quantity, leg.strategyId, reference)
        return if (limit != null) {
            OrderRequest.Limit(id, leg.symbol, leg.side, quantity, limit, TimeInForce.IOC, now, leg.strategyId)
        } else {
            OrderRequest.Market(id, leg.symbol, leg.side, quantity, TimeInForce.GTC, now, leg.strategyId)
        }
    }
}
