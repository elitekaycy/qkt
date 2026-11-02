package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.TickSteps
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The legs of a roll's carries on [venue]: each named [RollLegIds.base] plus `:<kind>`, so a leg is the same order
 * before and after a restart, and tracked by [legs] so the engine never sees it. With no [priceBand] (a backtest) a
 * leg is a market order; with one (a live lane) it is an immediate-or-cancel limit at the band from the contract's
 * measured roll price ([collarPrice]), so the venue cannot fill it beyond that however the book moved since the roll's
 * check. A leg's outcome is handed on once the venue answered, after its submit returned, never inside it. A leg is
 * expected at once and sent through [gate], which holds it while a restarted session is not ready.
 *
 * An unwind that does not fill whole is not sent again, its book having just refused it: what it was to close stays
 * on the contract, booked and saved in the lane's holdings and logged as an error. The strategy is already stopped,
 * an operator's resume is refused while it is held ([LaneStopRelease]), and the contract's settlement closes it.
 */
internal class CarryLegs(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val legs: RollLegs,
    private val priceBand: BigDecimal?,
    private val gate: (() -> Unit) -> Unit,
) {
    private val log = LoggerFactory.getLogger(CarryLegs::class.java)

    /** The leg closing [strategyId]'s signed [quantity] on the old contract in [run]. */
    fun closing(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
    ) = leg(run, strategyId, "close", run.fromIndex, quantity.negate(), run.measured.prices.fromPrice)

    /** The leg opening [strategyId]'s signed [quantity] on the new contract in [run]. */
    fun opening(
        run: RollRun,
        strategyId: String,
        quantity: BigDecimal,
    ) = leg(run, strategyId, "open", run.toIndex, quantity, run.measured.prices.toPrice)

    private fun leg(
        run: RollRun,
        strategyId: String,
        kind: String,
        index: Int,
        signed: BigDecimal,
        reference: BigDecimal,
    ): OrderRequest =
        order(
            "${base(run, strategyId)}:$kind",
            chainOf().contractSymbol(index),
            if (signed.signum() > 0) Side.BUY else Side.SELL,
            signed.abs(),
            strategyId,
            reference,
        )

    private fun order(
        id: String,
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        strategyId: String,
        reference: BigDecimal,
    ): OrderRequest {
        val now = clock.now()
        val band = priceBand ?: return OrderRequest.Market(id, symbol, side, quantity, TimeInForce.GTC, now, strategyId)
        val limit = collarPrice(side, reference, band, chainOf().root.tickSize)
        return OrderRequest.Limit(id, symbol, side, quantity, limit, TimeInForce.IOC, now, strategyId)
    }

    /** The prefix of [strategyId]'s legs in [run]. */
    fun base(
        run: RollRun,
        strategyId: String,
    ) = RollLegIds.base(chainOf().symbol, run.measured.atMs, strategyId)

    /** Sends [leg] and hands its outcome to [then] once the venue answered. */
    fun send(
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) {
        legs.expect(leg)
        gate {
            venue.broker.submit(leg)
            legs.whenEnded(leg.id, then)
        }
    }

    /**
     * Waits for the closing [leg] after a restart, as [await]. A closing leg that filled moves its carry on in the
     * same call, so one saved as out but not restored ended refused, under a qkt that failed the session on that
     * instead of stopping the strategy on the stream: [then] hears the refusal now.
     */
    fun awaitClosing(
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) = if (legs.isExpected(
            leg.id,
        )
    ) {
        await(leg, then)
    } else {
        then(LegOutcome.Rejected("refused before the restart (${leg.id})"))
    }

    /** Waits for [leg], restored after a restart with what had filled of it, as [send] does once it is sent. */
    fun await(
        leg: OrderRequest,
        then: (LegOutcome) -> Unit,
    ) {
        check(legs.isExpected(leg.id)) { "roll leg ${leg.id} was not restored" }
        legs.whenEnded(leg.id, then)
    }

    /** Waits for the unwind [leg], restored after a restart, as [unwind] does once it is sent. */
    fun awaitUnwind(leg: OrderRequest) = await(leg, unwound(leg))

    /** Closes the [quantity] a part-filled opening [leg] of [run] left on its contract; a failure leaves it there. */
    fun unwind(
        run: RollRun,
        leg: OrderRequest,
        quantity: BigDecimal,
    ) {
        val side = if (leg.side == Side.BUY) Side.SELL else Side.BUY
        val id = leg.id.replaceAfterLast(':', "unwind")
        val unwinding = order(id, leg.symbol, side, quantity, leg.strategyId, run.measured.prices.toPrice)
        send(unwinding, unwound(unwinding))
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

/**
 * The furthest a roll leg on [side] may fill: [band] (a fraction) from [reference], above it to buy and below it to
 * sell, on the [tick] grid and never outside the band. E.g. at 0.25 around 78,000, a BUY's limit is 97,500 and a
 * SELL's 58,500. A reference so small that its limit would round to nothing is held at one tick.
 */
internal fun collarPrice(
    side: Side,
    reference: BigDecimal,
    band: BigDecimal,
    tick: BigDecimal,
): BigDecimal {
    val grid = TickSteps(tick)
    val limit =
        if (side == Side.BUY) {
            grid.floor(reference.multiply(BigDecimal.ONE.add(band)))
        } else {
            grid.ceil(reference.multiply(BigDecimal.ONE.subtract(band)))
        }
    return limit.takeIf { it.signum() > 0 } ?: tick
}
