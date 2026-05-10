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
 * (MetaTrader 5 via gateway), [com.qkt.broker.bybit.BybitSpotBroker] /
 * [com.qkt.broker.bybit.BybitLinearBroker] (Bybit REST/WS),
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
