package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.source.SymbolPattern
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

    init {
        bus?.let { b ->
            b.subscribe<BrokerEvent.OrderFilled> { e -> orderIdToBroker.remove(e.clientOrderId) }
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

    private fun brokerFor(symbol: String): Broker? =
        routes.firstOrNull { (pattern, _) -> pattern.matches(symbol) }?.second ?: fallback

    override fun shutdown() {
        for ((_, target) in routes) runCatching { target.shutdown() }
        fallback?.let { runCatching { it.shutdown() } }
    }
}
