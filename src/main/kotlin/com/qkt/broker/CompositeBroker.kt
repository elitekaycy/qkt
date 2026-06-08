package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.source.SymbolPattern
import java.util.Collections
import org.slf4j.LoggerFactory

/**
 * Multi-venue router that dispatches each [OrderRequest] to the leaf broker whose
 * [SymbolPattern] matches the order's symbol.
 *
 * Used to combine multiple brokers (e.g. MT5 for FX + Bybit for crypto) behind a single
 * [Broker] interface. Routes are evaluated in order; the first matching pattern wins.
 * An optional [fallback] catches anything no pattern claims. Composite tracks
 * `orderId → broker` so [cancel] and [modify] reach the right leaf.
 *
 * Capabilities are symbol-dependent — [capabilities] throws, callers must use
 * [capabilitiesFor].
 */
class CompositeBroker(
    private val routes: List<Pair<SymbolPattern, Broker>>,
    private val fallback: Broker? = null,
    bus: EventBus? = null,
) : Broker {
    private val log = LoggerFactory.getLogger(CompositeBroker::class.java)

    override val name: String = "Composite"

    override val capabilities: Set<OrderTypeCapability>
        get() = error("CompositeBroker.capabilities is symbol-dependent; use capabilitiesFor(symbol)")

    override fun capabilitiesFor(symbol: String): Set<OrderTypeCapability> =
        brokerFor(symbol)?.capabilitiesFor(symbol) ?: emptySet()

    override fun supports(symbol: String): Boolean = brokerFor(symbol) != null

    private val orderIdToBroker: MutableMap<String, Broker> = mutableMapOf()

    /**
     * Venue ticket → owning leaf, captured from each fill's `brokerOrderId` (the same value the
     * position tracker stores as the leg's `brokerTicket`). Lets [modifyPosition] — which is keyed
     * by venue ticket, not order id — reach the leaf that holds the position. Bounded so a 24/7
     * session can't accumulate every ticket forever; the cap is far above any realistic count of
     * concurrently-open positions, so it never evicts a live ticket.
     */
    private val ticketToBroker: MutableMap<String, Broker> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Broker>(16, 0.75f, false) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Broker>): Boolean = size > 10_000
            },
        )

    init {
        bus?.let { b ->
            b.subscribe<BrokerEvent.OrderFilled> { e ->
                orderIdToBroker[e.clientOrderId]?.let { broker ->
                    e.brokerOrderId?.let { ticket -> ticketToBroker[ticket] = broker }
                }
                orderIdToBroker.remove(e.clientOrderId)
            }
            b.subscribe<BrokerEvent.OrderCancelled> { e -> orderIdToBroker.remove(e.clientOrderId) }
            b.subscribe<BrokerEvent.OrderRejected> { e -> orderIdToBroker.remove(e.clientOrderId) }
        }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        val target =
            brokerFor(request.symbol)
                ?: return SubmitAck(
                    clientOrderId = request.id,
                    brokerOrderId = null,
                    accepted = false,
                    rejectReason = "no broker for ${request.symbol}",
                )
        orderIdToBroker[request.id] = target
        return target.submit(request)
    }

    override fun cancel(orderId: String) {
        val target = orderIdToBroker[orderId]
        if (target == null) {
            log.warn("CompositeBroker.cancel: unknown orderId={}", orderId)
            return
        }
        target.cancel(orderId)
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val target = orderIdToBroker[orderId]
        return if (target == null) {
            SubmitAck(
                clientOrderId = orderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "unknown orderId $orderId",
            )
        } else {
            target.modify(orderId, changes)
        }
    }

    override fun modifyPosition(
        ticket: String,
        sl: java.math.BigDecimal?,
        tp: java.math.BigDecimal?,
    ): SubmitAck {
        val target = ticketToBroker[ticket]
        return if (target == null) {
            // No leaf has reported a fill for this ticket — don't claim success (the Broker default
            // returns accepted=true), so a venue SL/TP mirror to an unknown ticket is surfaced.
            SubmitAck(
                clientOrderId = ticket,
                brokerOrderId = ticket,
                accepted = false,
                rejectReason = "no leaf owns ticket $ticket",
            )
        } else {
            target.modifyPosition(ticket, sl, tp)
        }
    }

    override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> {
        val merged = LinkedHashMap<String, MutableList<com.qkt.positions.Position>>()
        for (leaf in allLeaves()) {
            // A leaf that can't report (e.g. its venue poll fails) must not break the others or
            // the caller — mirror the no-op default's never-throws contract and skip it.
            val leafPositions =
                runCatching { leaf.getOpenPositions() }.getOrElse {
                    log.warn("CompositeBroker.getOpenPositions: leaf {} failed: {}", leaf.name, it.message)
                    emptyMap()
                }
            for ((symbol, positions) in leafPositions) {
                merged.getOrPut(symbol) { mutableListOf() }.addAll(positions)
            }
        }
        return merged
    }

    override fun recoverPendingOrders(orders: List<com.qkt.execution.ManagedOrder>) {
        val byBroker = LinkedHashMap<Broker, MutableList<com.qkt.execution.ManagedOrder>>()
        for (order in orders) {
            val target = brokerFor(order.request.symbol) ?: continue
            // Restore orderId → broker routing so a later cancel()/modify() of a recovered order
            // reaches the right leaf. Without this, OCO restart recovery is dead on a composite.
            orderIdToBroker[order.id] = target
            byBroker.getOrPut(target) { mutableListOf() }.add(order)
        }
        for ((broker, group) in byBroker) runCatching { broker.recoverPendingOrders(group) }
    }

    private fun allLeaves(): List<Broker> = routes.map { it.second } + listOfNotNull(fallback)

    private fun brokerFor(symbol: String): Broker? =
        routes.firstOrNull { (pattern, _) -> pattern.matches(symbol) }?.second ?: fallback

    override fun shutdown() {
        for ((_, target) in routes) runCatching { target.shutdown() }
        fallback?.let { runCatching { it.shutdown() } }
    }
}
