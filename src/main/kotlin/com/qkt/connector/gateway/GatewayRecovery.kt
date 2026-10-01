package com.qkt.connector.gateway

import java.math.BigDecimal
import org.slf4j.LoggerFactory

/** An order a restart restored: [strategyId]'s order for [quantity], of which [alreadyFilled] was booked before. */
internal data class RecoveredOrder(
    val clientOrderId: String,
    val strategyId: String,
    val quantity: BigDecimal,
    val alreadyFilled: BigDecimal,
)

/**
 * Resolves restored orders against the gateway by id, never by a time window: each order's current
 * state and its complete fill history (`GET /v1/orders/{id}`, `GET /v1/deals?client_order_id=`). The
 * fills booked before the restart, oldest first up to the order's booked quantity, go to [markBooked];
 * the rest to [onFill]; then the order's state to [onOrder], so an order that ended while qkt was down
 * ends in the engine too.
 */
internal object GatewayRecovery {
    /** Resolves [orders]; returns the ids the gateway knows. */
    fun recover(
        client: GatewayClient,
        orders: List<RecoveredOrder>,
        markBooked: (String) -> Unit,
        onFill: (WireFill) -> Unit,
        onOrder: (WireOrder) -> Unit,
    ): Set<String> {
        val known = LinkedHashSet<String>()
        for (restored in orders) {
            val order = client.order(restored.clientOrderId) ?: continue
            known += restored.clientOrderId
            var booked = BigDecimal.ZERO
            var replaying = true
            for (fill in client.dealsOf(restored.clientOrderId).sortedBy { it.time }) {
                val quantity = BigDecimal(fill.quantity)
                replaying = replaying && booked.add(quantity) <= restored.alreadyFilled
                if (replaying) {
                    booked = booked.add(quantity)
                    markBooked(fill.fillId)
                } else {
                    onFill(fill)
                }
            }
            onOrder(order)
        }
        return known
    }

    /**
     * Settles the held contracts [codes] that expired while qkt was away, through [deliver]: price only,
     * since the costs the venue charged then cannot be shared between holders after the fact (logged).
     */
    fun settleHeld(
        client: GatewayClient,
        codes: List<String>,
        deliver: (WireSettlement) -> Unit,
    ) {
        for (settlement in codes.flatMap(client::settlementsOf)) {
            if (settlement.costs.isNotEmpty()) {
                log.warn(
                    "costs of {} settled while away are not attributed: {}",
                    settlement.symbol,
                    settlement.costs,
                )
            }
            deliver(settlement.copy(costs = emptyList()))
        }
    }

    private val log = LoggerFactory.getLogger(GatewayRecovery::class.java)
}
