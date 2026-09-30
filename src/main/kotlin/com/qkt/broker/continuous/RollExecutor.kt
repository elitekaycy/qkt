package com.qkt.broker.continuous

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.MeasuredRoll
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal

/**
 * Carries one stream across a roll. Both contracts are marked at the roll's reference prices (their
 * prices at the roll instant, as measured by the history); each strategy's position is closed on the
 * old contract and reopened on the new one with market orders; resting orders are cancelled and
 * re-placed on the new contract at the same series level; every carried position is recorded in the
 * [ledger] and its cost published as a [CostIncurred]. None of the roll's venue orders reach the
 * engine: its continuous position did not change.
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
    /** Roll from contract [fromIndex] to [toIndex], carrying [positions] (strategy to signed quantity). */
    fun roll(
        fromIndex: Int,
        toIndex: Int,
        positions: Map<String, BigDecimal>,
    ) {
        val measured = chain.rollOutOf(fromIndex)
        val from = chain.contractSymbol(fromIndex)
        val to = chain.contractSymbol(toIndex)
        val resting = orders.on(fromIndex)
        for (order in resting) {
            legs.cancelling(order.venueId)
            venue.broker.cancel(order.venueId)
        }
        contractPrices.update(from, measured.prices.fromPrice)
        contractPrices.update(to, measured.prices.toPrice)
        val carried =
            positions.filterValues { it.signum() != 0 }.map { (strategy, qty) ->
                carry(strategy, qty, from, to, measured)
            }
        val space = chain.spaceFor(toIndex)
        for (order in resting) {
            val n = order.replacements + 1
            val venueId = "${order.request.id}~r$n"
            orders.add(order.copy(venueId = venueId, contractIndex = toIndex, replacements = n))
            venue.broker.submit(requireNotNull(toContract(order.request, venueId, to, space)))
        }
        val referencePrice = space.toContinuous(measured.prices.toPrice)
        for (entry in carried) {
            ledger.record(entry)
            bus.publish(CostIncurred(entry.strategyId, chain.symbol, entry.cost, "roll $from->$to", referencePrice))
        }
    }

    private fun carry(
        strategyId: String,
        quantity: BigDecimal,
        from: String,
        to: String,
        measured: MeasuredRoll,
    ): RollEntry {
        val side = if (quantity.signum() > 0) Side.BUY else Side.SELL
        val base = "roll:${chain.symbol}:${measured.atMs}:$strategyId"
        val close = leg("$base:close", from, if (side == Side.BUY) Side.SELL else Side.BUY, quantity.abs(), strategyId)
        val open = leg("$base:open", to, side, quantity.abs(), strategyId)
        return RollEntry(
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
            fees = feesOf(close).add(feesOf(open)),
        )
    }

    private fun leg(
        venueId: String,
        contract: String,
        side: Side,
        quantity: BigDecimal,
        strategyId: String,
    ): BrokerEvent.OrderFilled {
        legs.expect(venueId)
        venue.broker.submit(
            OrderRequest.Market(venueId, contract, side, quantity, TimeInForce.GTC, clock.now(), strategyId),
        )
        return when (val outcome = legs.outcome(venueId)) {
            is LegOutcome.Filled -> outcome.fill
            is LegOutcome.Rejected -> error("roll leg $venueId was rejected: ${outcome.reason}")
        }
    }

    /** The leg's venue-reported fees, all in the root's currency. */
    private fun feesOf(fill: BrokerEvent.OrderFilled): BigDecimal =
        fill.typedVenueCosts.fold(BigDecimal.ZERO) { total, cost ->
            require(cost.amount.normalizedCurrency == chain.root.currency.uppercase()) {
                "roll fee on ${fill.symbol} is in ${cost.amount.currency}, not ${chain.root.currency}"
            }
            total.add(cost.amount.amount)
        }
}
