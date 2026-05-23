package com.qkt.broker

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * Single interface every venue connector implements.
 *
 * Brokers receive [OrderRequest]s and publish results back through
 * [com.qkt.events.BrokerEvent]s on the bus. The interface is intentionally narrow —
 * submit, cancel, and (optionally) modify — because the engine handles everything
 * around it: order management, P&L attribution, position tracking, risk.
 *
 * Implementations: [PaperBroker] (in-process simulator), [com.qkt.broker.mt5.MT5Broker]
 * (MetaTrader 5 via gateway), [com.qkt.broker.bybit.spot.BybitSpotBroker] /
 * [com.qkt.broker.bybit.linear.BybitLinearBroker] (Bybit REST/WS),
 * [com.qkt.broker.composite.CompositeBroker] (multi-venue router).
 */
interface Broker {
    /** Human-readable broker identifier — appears in logs and status output. */
    val name: String

    /** Order types this broker accepts directly without engine-side decomposition. */
    val capabilities: Set<OrderTypeCapability>

    /**
     * Returns the capabilities for a specific [symbol].
     *
     * Defaults to the broker-wide [capabilities]; override when a venue supports
     * different shapes for different instruments.
     */
    fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> = capabilities

    /**
     * Returns `true` if this broker can route orders for [symbol].
     *
     * Used by [com.qkt.broker.composite.CompositeBroker] to pick the right leaf.
     */
    fun supports(symbol: String): Boolean = true

    /**
     * Submits [request] to the venue.
     *
     * Returns [SubmitAck] immediately; fill or rejection arrives asynchronously as a
     * [com.qkt.events.BrokerEvent] on the bus.
     */
    fun submit(request: OrderRequest): SubmitAck

    /** Cancels the working order with client-assigned [orderId]. No-op if already terminal. */
    fun cancel(orderId: String)

    /**
     * Modifies an existing working order.
     *
     * Optional capability — brokers that don't support modification throw
     * [UnsupportedOperationException].
     */
    fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck = throw UnsupportedOperationException("$name does not support modify")

    /**
     * Snapshot of currently-open positions on the venue, keyed by qkt-side symbol.
     *
     * Returns a **list per symbol** because hedge-mode-capable brokers (MT5, Bybit linear)
     * can hold a long and a short on the same symbol as two distinct tickets — the
     * reconciler needs to see each one separately to match against persisted legs.
     * One-way-mode brokers and PaperBroker return at most one entry per symbol.
     *
     * Used at strategy deploy time by [com.qkt.persistence.LegBookReconciler] to merge
     * broker reality with persisted state. Default returns an empty map — only brokers
     * with venue-side state override.
     */
    fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> = emptyMap()

    /**
     * Re-establish venue-side tracking for OCO legs recovered from the persistor on
     * restart. Brokers join each [com.qkt.execution.ManagedOrder] to live venue state by
     * ticket and, for a leg that filled while the daemon was down, republish its
     * [com.qkt.events.BrokerEvent.OrderFilled]. Default no-op — only stateful venue
     * connectors override.
     */
    fun recoverPendingOrders(orders: List<com.qkt.execution.ManagedOrder>) {}
}

/**
 * Fields a strategy or operator can change on a working order.
 *
 * Only non-null fields are applied; brokers ignore unset ones.
 */
data class OrderModification(
    val newQuantity: BigDecimal? = null,
    val newLimitPrice: BigDecimal? = null,
    val newStopPrice: BigDecimal? = null,
)
