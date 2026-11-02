package com.qkt.broker.continuous

import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * How one leg of a roll ended. A [retryable] end moved no price the venue refused: an immediate-or-cancel leg the book
 * did not meet, or a leg the venue never received. A refusal (a kill switch, a venue rule) is not retryable.
 */
internal sealed interface LegOutcome {
    /** The leg filled whole: [fill] is every slice of it, at their quantity-weighted price. */
    data class Filled(
        val fill: BrokerEvent.OrderFilled,
    ) : LegOutcome

    /** The venue refused or cancelled the leg before anything filled. */
    data class Rejected(
        val reason: String,
        val retryable: Boolean = false,
    ) : LegOutcome

    /** The venue ended the leg (cancel or refusal) after filling only [fill] of it. */
    data class Partial(
        val fill: BrokerEvent.OrderFilled,
        val reason: String,
        val retryable: Boolean = false,
    ) : LegOutcome
}

/** A roll's [leg] out at the venue, with the [slices] of it filled so far, oldest first, and what [earlier] attempts filled. */
internal data class LegInFlight(
    val leg: OrderRequest,
    val slices: List<BrokerEvent.OrderFilled>,
    val earlier: List<BrokerEvent.OrderFilled> = emptyList(),
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
