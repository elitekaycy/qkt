package com.qkt.events

import com.qkt.common.Side
import java.math.BigDecimal

/**
 * Events emitted by a [com.qkt.broker.Broker] after order submission.
 *
 * Every broker — paper, MT5, Bybit, composite — publishes through this hierarchy so the
 * rest of the engine can treat all venues uniformly. The order-event subset
 * ([OrderEvent]) is keyed by `clientOrderId` so the engine can correlate broker
 * responses back to the originating [com.qkt.execution.OrderRequest].
 */
sealed interface BrokerEvent : Event {
    /**
     * The subset of broker events that report on a specific submitted order.
     *
     * Excludes balance/position events that aren't tied to a single client order.
     */
    sealed interface OrderEvent : BrokerEvent {
        /** The client-assigned order id used to correlate the request with the response. */
        val clientOrderId: String

        /** The broker-assigned id once acknowledged, or `null` before acknowledgement. */
        val brokerOrderId: String?

        /** Which strategy produced the originating signal — empty for engine-internal orders. */
        val strategyId: String
    }

    /** The broker accepted the order. The fill may still arrive later as [OrderFilled]. */
    data class OrderAccepted(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    /** The broker refused the order. [reason] is the venue's rejection message. */
    data class OrderRejected(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    /** A complete fill at [price] for [quantity]. The order is done after this event. */
    data class OrderFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    /**
     * A partial fill — [quantity] filled in this slice, [cumulativeFilled] across the order.
     *
     * The order remains live; expect more partial fills or a final [OrderFilled].
     */
    data class OrderPartiallyFilled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val symbol: String,
        val side: Side,
        val price: BigDecimal,
        val quantity: BigDecimal,
        val cumulativeFilled: BigDecimal,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    /** The order was cancelled by the strategy, the engine, or the venue. */
    data class OrderCancelled(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        val reason: String,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    /**
     * The venue accepted a modification to a working order.
     *
     * Brokers publish this after [com.qkt.broker.Broker.modify] succeeds. The qkt-side
     * order manager updates its tracked SL/TP/trigger from the [OrderModification] the
     * caller supplied — the event itself doesn't carry the new values to keep the
     * payload small.
     */
    data class OrderModified(
        override val clientOrderId: String,
        override val brokerOrderId: String?,
        override val strategyId: String = "",
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : OrderEvent

    /**
     * Account balances refreshed from the venue.
     *
     * The `source` field identifies which broker emitted them — useful when a
     * [com.qkt.broker.composite.CompositeBroker] routes through multiple venues.
     */
    data class BalancesUpdated(
        val balances: Map<String, BigDecimal>,
        val source: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    /**
     * The broker's gateway has failed [consecutiveFailures] state reads in a row.
     *
     * Position/pending reconciliation is suspended until a clean read, so engine
     * state may lag venue truth — the operator should check the gateway. Emitted
     * once per outage (on crossing the failure threshold), not per failed poll.
     */
    data class GatewayUnreachable(
        val broker: String,
        val consecutiveFailures: Int,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent

    /**
     * Reports a position correction from the venue that doesn't match local state.
     *
     * Emitted on startup (state recovery) and on out-of-band changes (manual close on
     * the venue). The engine reconciles its position tracker from this event.
     */
    data class PositionReconciled(
        val symbol: String,
        val oldQty: BigDecimal?,
        val newQty: BigDecimal,
        val oldAvgPx: BigDecimal?,
        val newAvgPx: BigDecimal,
        val source: String,
        val reason: String,
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : BrokerEvent
}
