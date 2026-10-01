package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.MeasuredRoll
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Carries one strategy's position across a roll with two market legs on [venue]: close on the old
 * contract, then open on the new one, each sent once the venue answered the one before. An opening leg
 * refused or ended leaves the position closed on the venue (any part of it that filled is unwound): its
 * close at the old leg's fill is recorded as the stream's [ExitReason.ROLL_FAILED] exit. A closing leg
 * ended part-filled closes only that part. A refused closing leg is a configuration fault and fails
 * loudly.
 */
internal class RollCarry(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val legs: RollLegs,
    private val fills: ContractFillLog,
) {
    private val log = LoggerFactory.getLogger(RollCarry::class.java)

    /** The chain as it stands now: a live session extends it with each roll it measures. */
    private val chain: ContinuousChain get() = chainOf()

    /**
     * Carry one strategy's [quantity] to the new contract, adding its entry to the run, and hand [then]
     * null once carried, or the venue's reason when it was not: the new contract refused or ended the
     * opening leg (any part of it that filled is unwound, and the position's close at the old leg's fill
     * is added to the run's closes), or ended the closing leg part-filled (that part is the close; the
     * rest stays on the old contract).
     */
    fun carry(
        strategyId: String,
        quantity: BigDecimal,
        run: RollRun,
        then: (String?) -> Unit,
    ) {
        val from = chain.contractSymbol(run.fromIndex)
        val to = chain.contractSymbol(run.toIndex)
        val side = if (quantity.signum() > 0) Side.BUY else Side.SELL
        val opposite = if (side == Side.BUY) Side.SELL else Side.BUY
        val size = quantity.abs()
        val base = "roll:${chain.symbol}:${run.measured.atMs}:$strategyId"

        fun stopOnStream(
            close: BrokerEvent.OrderFilled,
            reason: String,
        ) {
            val onStream = closeOnStream(close, "$base:failed", run.fromIndex)
            fills.record(contractFill(close, onStream))
            run.closes += onStream
            then(reason)
        }
        leg("$base:close", from, opposite, size, strategyId) { closing ->
            when (closing) {
                is LegOutcome.Rejected -> error("$from refused the closing leg $base:close: ${closing.reason}")
                is LegOutcome.Partial -> {
                    log.error(
                        "{} closed {} of {} for {}: {}",
                        from,
                        closing.fill.quantity,
                        size,
                        strategyId,
                        closing.reason,
                    )
                    stopOnStream(closing.fill, closing.reason)
                }
                is LegOutcome.Filled ->
                    leg("$base:open", to, side, size, strategyId) { opening ->
                        when (opening) {
                            is LegOutcome.Filled -> {
                                check(
                                    opening.fill.quantity.compareTo(size) == 0,
                                ) { "roll leg $base:open filled ${opening.fill.quantity} of $size" }
                                run.carried +=
                                    entry(strategyId, quantity, from, to, run.measured, closing.fill, opening.fill)
                                then(null)
                            }
                            is LegOutcome.Rejected -> {
                                log.error(
                                    "{} refused the roll of {} for {}: {}",
                                    to,
                                    chain.symbol,
                                    strategyId,
                                    opening.reason,
                                )
                                stopOnStream(closing.fill, opening.reason)
                            }
                            is LegOutcome.Partial -> {
                                log.error(
                                    "{} opened {} of {} for {}: {}; unwinding it",
                                    to,
                                    opening.fill.quantity,
                                    size,
                                    strategyId,
                                    opening.reason,
                                )
                                unwind("$base:unwind", to, opposite, opening.fill.quantity, strategyId)
                                stopOnStream(closing.fill, opening.reason)
                            }
                        }
                    }
            }
        }
    }

    /** Closes the [quantity] a part-filled opening leg left on [contract]; a failure leaves it there, logged. */
    private fun unwind(
        venueId: String,
        contract: String,
        side: Side,
        quantity: BigDecimal,
        strategyId: String,
    ) = leg(venueId, contract, side, quantity, strategyId) { outcome ->
        if (outcome !is LegOutcome.Filled) {
            log.error(
                "{} on {} could not be unwound: {}; it is still held there",
                quantity,
                contract,
                outcome,
            )
        }
    }

    private fun entry(
        strategyId: String,
        quantity: BigDecimal,
        from: String,
        to: String,
        measured: MeasuredRoll,
        close: BrokerEvent.OrderFilled,
        open: BrokerEvent.OrderFilled,
    ) = RollEntry(
        atMs = measured.atMs,
        stream = chain.symbol,
        strategyId = strategyId,
        from = from,
        to = to,
        quantity = quantity,
        multiplier = chain.root.multiplier,
        fromFill = close.price,
        toFill = open.price,
        fromReference = measured.prices.fromPrice,
        toReference = measured.prices.toPrice,
        fees = close.venueFeesIn(chain.root.currency).add(open.venueFeesIn(chain.root.currency)),
    )

    /** Sends one market leg and hands its outcome to [then] once the venue answered (after submit returns, never inside it). */
    private fun leg(
        venueId: String,
        contract: String,
        side: Side,
        quantity: BigDecimal,
        strategyId: String,
        then: (LegOutcome) -> Unit,
    ) {
        legs.expect(venueId, quantity)
        venue.broker.submit(
            OrderRequest.Market(venueId, contract, side, quantity, TimeInForce.GTC, clock.now(), strategyId),
        )
        legs.whenEnded(venueId, then)
    }

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
