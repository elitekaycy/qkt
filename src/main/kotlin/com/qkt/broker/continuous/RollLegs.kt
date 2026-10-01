package com.qkt.broker.continuous

import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import java.math.BigDecimal

/** How one market leg of a roll ended. */
internal sealed interface LegOutcome {
    /** The leg filled whole: [fill] is every slice of it, at their quantity-weighted price. */
    data class Filled(
        val fill: BrokerEvent.OrderFilled,
    ) : LegOutcome

    /** The venue refused or cancelled the leg before anything filled. */
    data class Rejected(
        val reason: String,
    ) : LegOutcome

    /** The venue ended the leg (cancel or refusal) after filling only [fill] of it. */
    data class Partial(
        val fill: BrokerEvent.OrderFilled,
        val reason: String,
    ) : LegOutcome
}

/**
 * The venue orders a roll places or cancels, which the engine must never see: the market legs,
 * captured as they end, and the resting orders being cancelled before their re-placement. A live venue
 * may fill a leg in slices (each an [BrokerEvent.OrderPartiallyFilled], the last an
 * [BrokerEvent.OrderFilled] of that slice only), so a leg's slices are summed into one fill at their
 * quantity-weighted price; a leg filled at once (the backtest's exchange simulator) is its fill as it
 * came. A leg's outcome reaches the roll through [whenEnded]: at once when the venue answered before its
 * submit returned, else when the answer arrives.
 */
internal class RollLegs {
    private class Leg(
        val quantity: BigDecimal,
    ) {
        val slices = ArrayList<BrokerEvent.OrderPartiallyFilled>()
    }

    private val pending = HashMap<String, Leg>()
    private val ended = HashMap<String, LegOutcome>()
    private val waiting = HashMap<String, (LegOutcome) -> Unit>()
    private val cancelling = HashSet<String>()

    /** Expect a leg of [quantity] under [venueId]. */
    fun expect(
        venueId: String,
        quantity: BigDecimal,
    ) {
        pending[venueId] = Leg(quantity)
    }

    /** Expect the cancel of the resting order working under [venueId]. */
    fun cancelling(venueId: String) {
        cancelling += venueId
    }

    /** Whether [venueId] is a roll leg, whose events are not the engine's. */
    fun isLeg(venueId: String): Boolean = venueId in pending

    /** Keep [e] if it is a slice of a leg; returns whether it was. */
    fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled): Boolean {
        val leg = pending[e.clientOrderId] ?: return false
        leg.slices += e
        return true
    }

    /** End the leg [e] completes; returns whether it was a leg. */
    fun onFilled(e: BrokerEvent.OrderFilled): Boolean {
        val leg = pending[e.clientOrderId] ?: return false
        return end(e.clientOrderId, LegOutcome.Filled(filled(leg, e)))
    }

    /** End the leg [e] refuses; returns whether it was a leg. */
    fun onRejected(e: BrokerEvent.OrderRejected): Boolean = stopped(e.clientOrderId, e.reason)

    /** Swallow [e] if it is a roll's cancel of a resting order, or end the leg it cancels; returns whether it was either. */
    fun onCancelled(e: BrokerEvent.OrderCancelled): Boolean =
        cancelling.remove(e.clientOrderId) || stopped(e.clientOrderId, "cancelled by the venue: ${e.reason}")

    /** Hands the outcome of the leg under [venueId] to [then]: now if it has ended, else once it does. */
    fun whenEnded(
        venueId: String,
        then: (LegOutcome) -> Unit,
    ) {
        val outcome = ended.remove(venueId)
        if (outcome == null) {
            waiting[venueId] = then
            return
        }
        pending -= venueId
        then(outcome)
    }

    private fun stopped(
        venueId: String,
        reason: String,
    ): Boolean {
        val leg = pending[venueId] ?: return false
        val outcome =
            if (leg.slices.isEmpty()) {
                LegOutcome.Rejected(
                    reason,
                )
            } else {
                LegOutcome.Partial(filled(leg, null), reason)
            }
        return end(venueId, outcome)
    }

    private fun end(
        venueId: String,
        outcome: LegOutcome,
    ): Boolean {
        val then = waiting.remove(venueId)
        if (then == null) {
            ended[venueId] = outcome
        } else {
            pending -= venueId
            then(outcome)
        }
        return true
    }

    /** The leg's slices and [last] as one fill; a leg filled at once is [last] unchanged. */
    private fun filled(
        leg: Leg,
        last: BrokerEvent.OrderFilled?,
    ): BrokerEvent.OrderFilled {
        if (leg.slices.isEmpty()) return requireNotNull(last)
        val slices = leg.slices.map { it.quantity to it.price } + listOfNotNull(last?.let { it.quantity to it.price })
        val quantity = slices.fold(BigDecimal.ZERO) { sum, (q, _) -> sum + q }
        val notional = slices.fold(BigDecimal.ZERO) { sum, (q, p) -> sum + q * p }
        val base = last ?: leg.slices.last().asFill()
        return base.copy(
            quantity = quantity,
            price = notional.divide(quantity, Money.CONTEXT),
            venueCosts = leg.slices.fold(last?.venueCosts ?: BigDecimal.ZERO) { sum, s -> sum + s.venueCosts },
            typedVenueCosts = leg.slices.flatMap { it.typedVenueCosts } + (last?.typedVenueCosts ?: emptyList()),
        )
    }
}

/** This slice as a fill of its own quantity, for books that take fills. */
internal fun BrokerEvent.OrderPartiallyFilled.asFill() =
    BrokerEvent.OrderFilled(
        clientOrderId,
        brokerOrderId,
        symbol,
        side,
        price,
        quantity,
        strategyId,
        timestamp,
        sequenceId,
        updatesOrderExecution = false,
        venueCosts = venueCosts,
        typedVenueCosts = typedVenueCosts,
        exitReason = exitReason,
    )

/** The fees the venue reported on this fill, all of which must be in [currency]. */
internal fun BrokerEvent.OrderFilled.venueFeesIn(currency: String): BigDecimal =
    typedVenueCosts.fold(BigDecimal.ZERO) { total, cost ->
        require(cost.amount.normalizedCurrency == currency.uppercase()) {
            "fee on $symbol is in ${cost.amount.currency}, not $currency"
        }
        total.add(cost.amount.amount)
    }
