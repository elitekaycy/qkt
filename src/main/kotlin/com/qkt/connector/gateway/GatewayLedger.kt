package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Which strategy owns each order on a gateway account, which orders are still open, and what their
 * wire events mean: the [GatewayEventTranslator] turns them into engine events and [routing] delivers
 * them, all under [lock]. Orders are kept by their gateway id ([GatewayClientIds]); the engine sees its
 * own ids. An order no strategy owns (yet: a restart hands them back) is not reported,
 * and its fills stay unbooked, until one does. A settlement is delivered once, however many times the
 * stream and a resynchronization report it.
 */
internal class GatewayLedger(
    symbols: GatewaySymbols,
    private val routing: GatewayRouting,
    private val lock: Any,
) {
    private val owners = ConcurrentHashMap<String, String>()
    private val byEngineId = ConcurrentHashMap<String, String>()
    private val open: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val settled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val translator = GatewayEventTranslator(symbols) { id -> owners[id] ?: "" }

    /** The orders the engine still holds open. */
    val openOrders: Set<String> get() = open.toSet()

    /** The gateway id of the open engine order [engineId], or null when it is not open here. */
    fun gatewayId(engineId: String): String? = byEngineId[engineId]

    /** Whether a strategy here owns order [clientOrderId]. */
    fun owns(clientOrderId: String): Boolean = owners.containsKey(clientOrderId)

    /** [strategy] sent (or a restart restored) [clientOrderId] for [quantity], [alreadyFilled] of it booked. */
    fun own(
        clientOrderId: String,
        strategy: String,
        quantity: BigDecimal,
        alreadyFilled: BigDecimal = BigDecimal.ZERO,
    ) {
        owners[clientOrderId] = strategy
        byEngineId[GatewayClientIds.engineId(clientOrderId)] = clientOrderId
        open += clientOrderId
        synchronized(lock) { translator.expect(clientOrderId, quantity, alreadyFilled) }
    }

    /** Fill [fillId] was booked before a restart. */
    fun markBooked(fillId: String) = synchronized(lock) { translator.markBooked(fillId) }

    /** An `order` update. */
    fun onOrder(order: WireOrder) {
        if (!owns(order.clientOrderId)) return
        synchronized(lock) { translator.order(order)?.let(::route) }
    }

    /** A `fill`. */
    fun onFill(fill: WireFill) {
        if (!owns(fill.clientOrderId)) return
        synchronized(lock) { translator.fill(fill)?.let(::route) }
    }

    /** A `settlement`, delivered to every broker with its costs shared by holding. */
    fun onSettlement(settlement: WireSettlement) {
        if (!settled.add("${settlement.symbol}@${settlement.time}")) return
        synchronized(lock) { routing.settle(translator.settlement(settlement)) }
    }

    /** A settlement for [broker] alone (a contract it still holds that settled while it was away). */
    fun settleFor(
        broker: GatewayRouting.Attached,
        settlement: WireSettlement,
    ) = synchronized(lock) { broker.publish(translator.settlement(settlement)) }

    private fun route(event: BrokerEvent.OrderEvent) {
        routing.route(GatewayClientIds.toEngine(event))
        // An ended order's ownership goes with it: the translator drops any later update of it.
        if (event is BrokerEvent.OrderFilled ||
            event is BrokerEvent.OrderCancelled ||
            event is BrokerEvent.OrderRejected
        ) {
            open -= event.clientOrderId
            owners -= event.clientOrderId
            byEngineId -= GatewayClientIds.engineId(event.clientOrderId)
        }
    }
}
