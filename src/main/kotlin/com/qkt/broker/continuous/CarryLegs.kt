package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The legs of a roll's carries on [venue]: each named [RollLegIds.base] plus `:<kind>`, so a leg is the same order
 * before and after a restart, and tracked by [legs] so the engine never sees it. With no [priceBand] (a backtest) a
 * leg is a market order; with one (a live lane) it is an immediate-or-cancel limit a narrow collar from the contract's
 * measured roll price ([collarPrice]), so the venue cannot fill it beyond that however the book moved since the roll's
 * check. A leg's outcome is handed on once the venue answered, after its submit returned, never inside it. A leg is
 * expected at once and sent through [gate], which holds it while a restarted session is not ready.
 *
 * A leg ended unfilled goes again ([LegAttempts]).
 *
 * An unwind still not filled when its attempts run out is not sent again: what it was to close stays on the contract,
 * booked and saved in the lane's holdings and logged as an error. The strategy is already stopped, an operator's resume is refused while it is held ([LaneStopRelease]), and the contract's settlement closes it.
 */
internal class CarryLegs(
    clock: Clock,
    private val chainOf: () -> ContinuousChain,
    venue: ContractVenue,
    private val legs: RollLegs,
    priceBand: BigDecimal?,
    gate: (() -> Unit) -> Unit,
) {
    private val log = LoggerFactory.getLogger(CarryLegs::class.java)
    private val orders = RollLegOrders(clock, chainOf, priceBand)
    private val attempts = LegAttempts(clock, chainOf, venue, legs, priceBand, gate, orders)

    /** Sends [leg] of [run] and hands its outcome to [then] once the venue answered and no attempt is left. */
    fun send(
        run: RollRun?,
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) = attempts.send(run, leg, then)

    /** The leg closing [strategyId]'s signed [quantity] on the old contract in [run]. */
    fun closing(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
    ) = leg(run, strategyId, "close", run.fromIndex, quantity.negate(), run.fromPrice)

    /** The leg opening [strategyId]'s signed [quantity] on the new contract in [run]. */
    fun opening(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
    ) = leg(run, strategyId, "open", run.toIndex, quantity, run.toPrice)

    private fun leg(
        run: RollRun,
        strategyId: String,
        kind: String,
        index: Int,
        signed: BigDecimal,
        reference: BigDecimal,
    ): OrderRequest =
        orders.order(
            "${base(run, strategyId)}:$kind",
            chainOf().contractSymbol(index),
            if (signed.signum() > 0) Side.BUY else Side.SELL,
            signed.abs(),
            strategyId,
            reference,
        )

    /** The prefix of [strategyId]'s legs in [run]. */
    fun base(
        run: RollRun,
        strategyId: String,
    ) = RollLegIds.base(chainOf().symbol, run.measured.atMs, strategyId)

    /**
     * Waits for the closing [leg] after a restart, as [await]. A closing leg that filled moves its carry on in the
     * same call, so one saved as out but not restored ended refused, under a qkt that failed the session on that
     * instead of stopping the strategy on the stream: [then] hears the refusal now.
     */
    fun awaitClosing(
        run: RollRun,
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) = if (legs.isExpected(leg.id)) {
        await(run, leg, then)
    } else {
        then(LegOutcome.Rejected("refused before the restart (${leg.id})"))
    }

    /** Waits for [leg], restored after a restart with what had filled of it, as [send] does once it is sent. */
    fun await(
        run: RollRun?,
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) {
        check(legs.isExpected(leg.id)) { "roll leg ${leg.id} was not restored" }
        attempts.watch(run, leg, then)
    }

    /** Waits for the unwind [leg], restored after a restart, as [unwind] does once it is sent. */
    fun awaitUnwind(leg: OrderRequest) = await(null, leg, unwound(leg))

    /** The one line a carried roll logs: its contracts, legs, prices and attempts. */
    fun carried(
        strategyId: String,
        close: BrokerEvent.OrderFilled,
        open: BrokerEvent.OrderFilled,
    ) = log.info(
        "{} rolled for {}: {} {} {} at {} (attempt {}), {} {} {} at {} (attempt {})",
        chainOf().symbol,
        strategyId,
        close.side,
        close.quantity.toPlainString(),
        close.symbol,
        close.price.toPlainString(),
        RollLegIds.attempt(close.clientOrderId),
        open.side,
        open.quantity.toPlainString(),
        open.symbol,
        open.price.toPlainString(),
        RollLegIds.attempt(open.clientOrderId),
    )

    /** Closes the [quantity] a part-filled opening [leg] of [run] left on its contract; a failure leaves it there. */
    fun unwind(
        run: RollRun,
        leg: OrderRequest,
        quantity: BigDecimal,
    ) {
        val side = if (leg.side == Side.BUY) Side.SELL else Side.BUY
        val id = leg.id.replaceAfterLast(':', "unwind")
        val unwinding = orders.order(id, leg.symbol, side, quantity, leg.strategyId, run.toPrice)
        send(run, unwinding, unwound(unwinding))
    }

    /** Logs an [unwinding] leg the venue did not fill whole: what it did not close is still held. */
    private fun unwound(unwinding: OrderRequest): (LegOutcome) -> Unit =
        { outcome ->
            val closed = (outcome as? LegOutcome.Partial)?.fill?.quantity ?: BigDecimal.ZERO
            if (outcome !is LegOutcome.Filled) {
                log.error(
                    "{} on {} could not be unwound: {}; it is still held there until settled",
                    unwinding.quantity.subtract(closed),
                    unwinding.symbol,
                    outcome,
                )
            }
        }
}
