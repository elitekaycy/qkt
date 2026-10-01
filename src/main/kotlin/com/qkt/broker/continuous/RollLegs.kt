package com.qkt.broker.continuous

import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
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
    private val pending = LinkedHashMap<String, LegInFlight>()
    private val ended = HashMap<String, LegOutcome>()
    private val waiting = HashMap<String, (LegOutcome) -> Unit>()
    private val cancelling = LinkedHashMap<String, ContinuousOrder>()

    /** Expect [leg], of which [slices] already filled (a leg recovered after a restart). */
    fun expect(
        leg: OrderRequest.Market,
        slices: List<BrokerEvent.OrderFilled> = emptyList(),
    ) {
        pending[leg.id] = LegInFlight(leg, slices)
    }

    /** Expect the cancel of the resting [order], under the venue id it works under. */
    fun cancelling(order: ContinuousOrder) {
        cancelling[order.venueId] = order
    }

    /** Whether a leg is expected under [venueId]. */
    fun isExpected(venueId: String): Boolean = venueId in pending

    /** Stop expecting the cancel of [venueId]: the order never reached the venue. */
    fun forgetCancel(venueId: String) {
        cancelling.remove(venueId)
    }

    /** The legs still out at the venue, in the order they were sent. */
    val inFlight: List<LegInFlight> get() = pending.values.toList()

    /** The resting orders whose cancel is still awaited, as they worked when it was sent. */
    val awaitedCancels: List<ContinuousOrder> get() = cancelling.values.toList()

    /** Keep [e] if it is a slice of a leg; returns whether it was. */
    fun onPartiallyFilled(e: BrokerEvent.OrderPartiallyFilled): Boolean {
        val leg = pending[e.clientOrderId] ?: return false
        pending[e.clientOrderId] = leg.copy(slices = leg.slices + e.asFill())
        return true
    }

    /** End the leg [e] completes; returns whether it was a leg. */
    fun onFilled(e: BrokerEvent.OrderFilled): Boolean {
        val leg = pending[e.clientOrderId] ?: return false
        return end(e.clientOrderId, LegOutcome.Filled(filled(leg.slices, e)))
    }

    /** End the leg [e] refuses; returns whether it was a leg. */
    fun onRejected(e: BrokerEvent.OrderRejected): Boolean = stopped(e.clientOrderId, e.reason)

    /** Swallow [e] if it is a roll's cancel of a resting order, or end the leg it cancels; returns whether it was either. */
    fun onCancelled(e: BrokerEvent.OrderCancelled): Boolean =
        cancelling.remove(e.clientOrderId) != null || stopped(e.clientOrderId, "cancelled by the venue: ${e.reason}")

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
                LegOutcome.Partial(filled(leg.slices, null), reason)
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

    /** [slices] and [last] as one fill; a leg filled at once is [last] unchanged. */
    private fun filled(
        slices: List<BrokerEvent.OrderFilled>,
        last: BrokerEvent.OrderFilled?,
    ): BrokerEvent.OrderFilled {
        if (slices.isEmpty()) return requireNotNull(last)
        val all = slices + listOfNotNull(last)
        val quantity = all.fold(BigDecimal.ZERO) { sum, s -> sum + s.quantity }
        val notional = all.fold(BigDecimal.ZERO) { sum, s -> sum + s.quantity * s.price }
        return (last ?: slices.last()).copy(
            quantity = quantity,
            price = notional.divide(quantity, Money.CONTEXT),
            venueCosts = all.fold(BigDecimal.ZERO) { sum, s -> sum + s.venueCosts },
            typedVenueCosts = all.flatMap { it.typedVenueCosts },
        )
    }
}

/** A roll's market [leg] out at the venue, with the [slices] of it filled so far, oldest first. */
internal data class LegInFlight(
    val leg: OrderRequest.Market,
    val slices: List<BrokerEvent.OrderFilled>,
)

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
