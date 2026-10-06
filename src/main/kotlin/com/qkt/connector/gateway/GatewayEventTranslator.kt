package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import java.math.BigDecimal

/** The gateway sent something VGP v1 does not define: reported, never guessed at. */
class GatewayProtocolException(
    message: String,
) : RuntimeException(message)

/**
 * The one place engine events are built from VGP v1 order, fill and settlement objects. An order is
 * accepted once, and cancelled or rejected once; its fills are partial until their quantities reach the
 * order's quantity, and the fill that reaches it completes the order. A `fill_id` already booked (a
 * replay after a reconnect) is dropped, and so is any update of an order that has ended. [strategyOf]
 * attributes a client order id to its strategy. Not thread-safe: its owner calls it under one lock from
 * the stream, placement and engine threads.
 */
class GatewayEventTranslator(
    private val symbols: GatewaySymbols,
    private val strategyOf: (String) -> String,
) {
    private val quantities = HashMap<String, BigDecimal>()
    private val filled = HashMap<String, BigDecimal>()
    private val accepted = HashSet<String>()
    private val booked = RecentIds(RECENT)
    private val done = RecentIds(RECENT)
    private val ending = HashMap<String, WireOrder>()

    /**
     * Order [clientOrderId] was sent for [quantity]: its fills complete it at that quantity. A restored
     * order passes [alreadyFilled], what was booked before a restart.
     */
    fun expect(
        clientOrderId: String,
        quantity: BigDecimal,
        alreadyFilled: BigDecimal = BigDecimal.ZERO,
    ) {
        quantities[clientOrderId] = quantity
        if (alreadyFilled.signum() > 0) filled[clientOrderId] = alreadyFilled
    }

    /** Fill [fillId] was booked before a restart: it is never booked again. */
    fun markBooked(fillId: String) {
        booked.add(fillId)
    }

    /** The engine event an `order` update means, or null when it repeats what was already reported. */
    fun order(order: WireOrder): BrokerEvent.OrderEvent? {
        val id = order.clientOrderId
        if (done.contains(id)) return null
        quantities[id] = WireValues.decimal(order.quantity, "quantity")
        val strategy = strategyOf(id)
        return when (order.status) {
            "working", "filled" ->
                if (accepted.add(
                        id,
                    )
                ) {
                    BrokerEvent.OrderAccepted(id, order.venueOrderId, strategy)
                } else {
                    null
                }
            "cancelled", "rejected" ->
                if (WireValues.decimal(order.filledQuantity, "filled_quantity") > (filled[id] ?: BigDecimal.ZERO)) {
                    // It ended with fills not yet heard: its end waits for them, so they are never dropped.
                    ending[id] = order
                    null
                } else {
                    end(id) { ended(order, strategy) }
                }
            else -> throw GatewayProtocolException("order $id has status '${order.status}'")
        }
    }

    /** Whether order [clientOrderId] ended at the venue but its end waits for fills not yet heard. */
    fun awaitingFills(clientOrderId: String): Boolean = clientOrderId in ending

    /**
     * The engine events a `fill` means: none for a fill already booked; the fill, then the order's end when
     * it ended at the venue before this fill was heard and the fill completes what it reported filled.
     */
    fun fill(fill: WireFill): List<BrokerEvent.OrderEvent> =
        listOfNotNull(slice(fill)).let { events ->
            val pending = ending[fill.clientOrderId]
            val heard = filled[fill.clientOrderId] ?: BigDecimal.ZERO
            if (events.isEmpty() ||
                pending == null ||
                heard < WireValues.decimal(pending.filledQuantity, "filled_quantity")
            ) {
                events
            } else {
                events + end(fill.clientOrderId) { ended(pending, strategyOf(fill.clientOrderId)) }
            }
        }

    private fun slice(fill: WireFill): BrokerEvent.OrderEvent? {
        val symbol = symbols.qkt(fill.symbol)
        val side = WireValues.sideOf(fill.side)
        val quantity = WireValues.decimal(fill.quantity, "quantity")
        val price = WireValues.decimal(fill.price, "price")
        val costs = WireValues.costsOf(fill.costs, fill.time)
        if (!booked.add(fill.fillId)) return null
        val id = fill.clientOrderId
        val cumulative = (filled[id] ?: BigDecimal.ZERO).add(quantity)
        val total = quantities[id]
        val strategy = strategyOf(id)
        if (total != null && cumulative < total) {
            filled[id] = cumulative
            return BrokerEvent.OrderPartiallyFilled(
                id,
                fill.venueOrderId,
                symbol,
                side,
                price,
                quantity,
                cumulative,
                strategy,
                timestamp = fill.time,
                typedVenueCosts = costs,
            )
        }
        forget(id)
        return BrokerEvent.OrderFilled(
            id,
            fill.venueOrderId,
            symbol,
            side,
            price,
            quantity,
            strategy,
            timestamp = fill.time,
            typedVenueCosts = costs,
        )
    }

    /** The engine event a `settlement` means: the contract settled at its price, for every holder. */
    fun settlement(settlement: WireSettlement): ContractSettled =
        ContractSettled(
            symbols.qkt(settlement.symbol),
            WireValues.decimal(settlement.price, "price"),
            WireValues.costsOf(settlement.costs, settlement.time),
            settlement.time,
        )

    private fun ended(
        order: WireOrder,
        strategy: String,
    ): BrokerEvent.OrderEvent =
        if (order.status == "rejected") {
            BrokerEvent.OrderRejected(
                order.clientOrderId,
                order.venueOrderId,
                order.rejectReason ?: "rejected by the venue",
                strategy,
            )
        } else {
            BrokerEvent.OrderCancelled(order.clientOrderId, order.venueOrderId, "cancelled at the venue", strategy)
        }

    private fun end(
        id: String,
        event: () -> BrokerEvent.OrderEvent,
    ): BrokerEvent.OrderEvent {
        forget(id)
        return event()
    }

    /** Order [id] has ended: its state goes, and later updates of it are not reported. */
    private fun forget(id: String) {
        done.add(id)
        ending.remove(id)
        quantities.remove(id)
        filled.remove(id)
        accepted.remove(id)
    }

    private companion object {
        /** Far more executions and orders than any reconnect replays, so a replay is always recognized. */
        const val RECENT = 10_000
    }
}
