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
 * contract, then open on the new one, each sent once the venue answered the one before. A refused
 * opening leg leaves the position closed on the venue: its close at the old leg's fill is recorded as
 * the stream's [ExitReason.ROLL_FAILED] exit. A refused closing leg is a configuration fault and fails
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
     * the venue's refusal when the new contract refused the opening leg (the position's venue close at
     * the old leg's fill is then added to the run's closes), or null once carried.
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
        leg("$base:close", from, opposite, size, strategyId) { closing ->
            val close =
                when (closing) {
                    is LegOutcome.Filled -> closing.fill
                    is LegOutcome.Rejected -> error("$from refused the closing leg $base:close: ${closing.reason}")
                }
            leg("$base:open", to, side, size, strategyId) { opening ->
                when (opening) {
                    is LegOutcome.Filled -> {
                        check(
                            opening.fill.quantity.compareTo(size) == 0,
                        ) { "roll leg $base:open filled ${opening.fill.quantity} of $size" }
                        run.carried += entry(strategyId, quantity, from, to, run.measured, close, opening.fill)
                        then(null)
                    }
                    is LegOutcome.Rejected -> {
                        log.error("{} refused the roll of {} for {}: {}", to, chain.symbol, strategyId, opening.reason)
                        val onStream = closeOnStream(close, "$base:failed", run.fromIndex)
                        fills.record(contractFill(close, onStream))
                        run.closes += onStream
                        then(opening.reason)
                    }
                }
            }
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
        legs.expect(venueId)
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
