package com.qkt.connector.gateway

import java.math.BigDecimal

/**
 * The venue behind [FakeGateway]: orders that fill and end, executions, settlements, and net
 * positions from the executions. Every change is reported to [emit] as a stream event.
 */
internal class FakeVenue(
    private val emit: (type: String, data: Any) -> Unit,
) {
    val orders = LinkedHashMap<String, WireOrder>()
    val deals = ArrayList<WireFill>()
    val settlements = ArrayList<WireSettlement>()

    /** Places [body] as a working order. */
    fun place(body: WireSubmit): WireOrder {
        val order =
            WireOrder(
                body.clientOrderId,
                "v-${orders.size + 1}",
                body.symbol,
                body.side,
                body.type,
                body.quantity,
                body.limitPrice,
                body.stopPrice,
                body.timeInForce,
                body.reduceOnly,
                "working",
                "0",
                null,
                null,
                FakeGateway.TIME,
                FakeGateway.TIME,
            )
        orders[order.clientOrderId] = order
        emit("order", order)
        return order
    }

    /** Fills [quantity] of [clientOrderId] at [price] at [time]; the order is filled once whole. */
    fun fill(
        clientOrderId: String,
        fillId: String,
        quantity: String,
        price: String,
        time: Long,
    ) {
        val order = orders.getValue(clientOrderId)
        val fill = WireFill(clientOrderId, order.venueOrderId, fillId, order.symbol, order.side, quantity, price, time)
        deals += fill
        val filled = BigDecimal(order.filledQuantity).add(BigDecimal(quantity))
        val status = if (filled >= BigDecimal(order.quantity)) "filled" else "working"
        orders[clientOrderId] = order.copy(filledQuantity = filled.toPlainString(), status = status, updatedAt = time)
        emit("fill", fill)
        emit("order", orders.getValue(clientOrderId))
        emit("position", positions().firstOrNull { it.symbol == order.symbol } ?: WirePosition(order.symbol, "0", "0"))
    }

    /** Ends [clientOrderId] at the venue, when it is still working. */
    fun cancel(clientOrderId: String): WireOrder {
        val order = orders.getValue(clientOrderId)
        if (order.status == "working") {
            orders[clientOrderId] = order.copy(status = "cancelled")
            emit("order", orders.getValue(clientOrderId))
        }
        return orders.getValue(clientOrderId)
    }

    /** Settles [code] at [price]. */
    fun settle(settlement: WireSettlement) {
        settlements += settlement
        emit("settlement", settlement)
    }

    /** The account's net positions, from the executions. */
    fun positions(): List<WirePosition> =
        deals
            .groupBy { it.symbol }
            .mapValues { (_, fills) -> fills.fold(BigDecimal.ZERO) { q, f -> q.add(signed(f)) } }
            .filterValues { it.signum() != 0 }
            .map { (symbol, quantity) -> WirePosition(symbol, quantity.toPlainString(), "1") }

    private fun signed(fill: WireFill): BigDecimal =
        BigDecimal(fill.quantity).let {
            if (fill.side ==
                "buy"
            ) {
                it
            } else {
                it.negate()
            }
        }
}
