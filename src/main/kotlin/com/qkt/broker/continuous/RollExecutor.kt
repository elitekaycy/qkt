package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.MeasuredRoll
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import java.time.Instant
import org.slf4j.LoggerFactory

/**
 * Carries one stream across a roll. Both contracts are marked at the roll's reference prices (their
 * prices at the roll instant, as measured by the history); each strategy's position is closed on the
 * old contract and reopened on the new one with market orders; resting orders are cancelled and
 * re-placed on the new contract at the same series level; every carried position is recorded in the
 * [ledger] and its cost published as a [CostIncurred]. None of the roll's venue orders reach the
 * engine: its continuous position did not change.
 *
 * When the new contract refuses a strategy's opening leg, its position is gone from the venue: the
 * engine gets a venue close ([ExitReason.ROLL_FAILED]) at the old leg's fill, its resting orders are
 * cancelled rather than carried, and the strategy is stopped on the stream. A roll the stream skipped
 * (no data for a whole contract) stops every holder the same way; the exchange then settles the
 * expired contract. A refused closing leg is a configuration fault and fails loudly.
 */
internal class RollExecutor(
    private val bus: EventBus,
    private val clock: Clock,
    private val chain: ContinuousChain,
    private val venue: ContractVenue,
    private val contractPrices: MarketPriceTracker,
    private val orders: ContinuousOrderMap,
    private val legs: RollLegs,
    private val ledger: RollLedger,
) {
    private val log = LoggerFactory.getLogger(RollExecutor::class.java)

    /** Roll from contract [fromIndex] to [toIndex], carrying [positions] (strategy to signed quantity). */
    fun roll(
        fromIndex: Int,
        toIndex: Int,
        positions: Map<String, BigDecimal>,
    ): RollOutcome {
        val measured = chain.rollOutOf(fromIndex)
        val from = chain.contractSymbol(fromIndex)
        val to = chain.contractSymbol(toIndex)
        val stopped = "${chain.symbol} stopped: roll $from->$to at ${Instant.ofEpochMilli(measured.atMs)} failed"
        val holders = positions.filterValues { it.signum() != 0 }
        val resting = orders.on(fromIndex)
        for (order in resting) {
            legs.cancelling(order.venueId)
            venue.broker.cancel(order.venueId)
        }
        if (toIndex != fromIndex + 1) {
            val reason = "$stopped (no data for ${toIndex - fromIndex - 1} contract(s) in between)"
            log.error(reason)
            resting.forEach { cancelVisibly(it, reason) }
            return RollOutcome(stopped = holders.mapValues { reason }, flattened = emptySet())
        }
        contractPrices.update(from, measured.prices.fromPrice)
        contractPrices.update(to, measured.prices.toPrice)
        val carried = mutableListOf<RollEntry>()
        val failed = LinkedHashMap<String, String>()
        for ((strategyId, quantity) in holders) {
            val refusal = carry(strategyId, quantity, fromIndex, toIndex, measured, carried)
            if (refusal != null) failed[strategyId] = "$stopped ($refusal)"
        }
        val space = chain.spaceFor(toIndex)
        for (order in resting) {
            val reason = failed[order.request.strategyId]
            if (reason != null) cancelVisibly(order, reason) else replace(order, toIndex, to, space)
        }
        val referencePrice = space.toContinuous(measured.prices.toPrice)
        for (entry in carried) {
            ledger.record(entry)
            bus.publish(CostIncurred(entry.strategyId, chain.symbol, entry.cost, "roll $from->$to", referencePrice))
        }
        return RollOutcome(stopped = failed, flattened = failed.keys)
    }

    /**
     * Carry one strategy's [quantity] to the new contract, adding its entry to [carried]. Returns the
     * venue's refusal when the new contract refused the opening leg; the position is then closed on
     * the stream at the old leg's fill.
     */
    private fun carry(
        strategyId: String,
        quantity: BigDecimal,
        fromIndex: Int,
        toIndex: Int,
        measured: MeasuredRoll,
        carried: MutableList<RollEntry>,
    ): String? {
        val from = chain.contractSymbol(fromIndex)
        val to = chain.contractSymbol(toIndex)
        val side = if (quantity.signum() > 0) Side.BUY else Side.SELL
        val opposite = if (side == Side.BUY) Side.SELL else Side.BUY
        val size = quantity.abs()
        val base = "roll:${chain.symbol}:${measured.atMs}:$strategyId"
        val close =
            when (val closing = leg("$base:close", from, opposite, size, strategyId)) {
                is LegOutcome.Filled -> closing.fill
                is LegOutcome.Rejected -> error("$from refused the closing leg $base:close: ${closing.reason}")
            }
        val open =
            when (val opening = leg("$base:open", to, side, size, strategyId)) {
                is LegOutcome.Filled -> opening.fill
                is LegOutcome.Rejected -> {
                    log.error("{} refused the roll of {} for {}: {}", to, chain.symbol, strategyId, opening.reason)
                    closeOnStream(close, "$base:failed", fromIndex)
                    return opening.reason
                }
            }
        check(open.quantity.compareTo(size) == 0) { "roll leg $base:open filled ${open.quantity} of $size" }
        carried +=
            RollEntry(
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
        return null
    }

    private fun leg(
        venueId: String,
        contract: String,
        side: Side,
        quantity: BigDecimal,
        strategyId: String,
    ): LegOutcome {
        legs.expect(venueId)
        venue.broker.submit(
            OrderRequest.Market(venueId, contract, side, quantity, TimeInForce.GTC, clock.now(), strategyId),
        )
        return legs.outcome(venueId)
    }

    /** Report the old leg's [close] to the engine as the venue closing its position on the stream. */
    private fun closeOnStream(
        close: BrokerEvent.OrderFilled,
        id: String,
        fromIndex: Int,
    ) {
        bus.publish(
            close.copy(
                clientOrderId = id,
                brokerOrderId = id,
                symbol = chain.symbol,
                price = chain.spaceFor(fromIndex).toContinuous(close.price),
                updatesOrderExecution = false,
                exitReason = ExitReason.ROLL_FAILED,
            ),
        )
    }

    private fun replace(
        order: ContinuousOrder,
        toIndex: Int,
        to: String,
        space: PriceSpace,
    ) {
        val n = order.replacements + 1
        val venueId = "${order.request.id}~r$n"
        orders.add(order.copy(venueId = venueId, contractIndex = toIndex, replacements = n))
        venue.broker.submit(requireNotNull(toContract(order.request, venueId, to, space)))
    }

    private fun cancelVisibly(
        order: ContinuousOrder,
        reason: String,
    ) {
        orders.removeByVenueId(order.venueId)
        bus.publish(BrokerEvent.OrderCancelled(order.request.id, null, reason, order.request.strategyId, clock.now()))
    }
}
