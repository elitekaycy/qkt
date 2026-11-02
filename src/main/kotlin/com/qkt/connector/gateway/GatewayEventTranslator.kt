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
 * order's quantity, and the fill that reaches it completes the order. A venue's terminal `filled` below the order's
 * quantity moves that bar to its `filled_quantity`, and ends the order at once when its fills were all heard
 * (as a cancel of the rest: nothing more will come). A `fill_id` already booked (a
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
    private val received = HashMap<String, BigDecimal>()
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
        if (alreadyFilled.signum() > 0) {
            filled[clientOrderId] = alreadyFilled
            received[clientOrderId] = alreadyFilled
        }
    }

    /** Fill [fillId] was booked before a restart: it is never booked again. */
    fun markBooked(fillId: String) {
        booked.add(fillId)
    }

    /** What [fill] moved the holding by ([GatewayInKindFees]): what the engine booked for it. */
    fun received(fill: WireFill): BigDecimal =
        GatewayInKindFees.received(fill, symbols.baseAsset(symbols.qkt(fill.symbol)))

    /**
     * [fill] was booked before a restart: it is never booked again, and a restored order counts it at the venue's
     * quantity, not the [received] one it was restored with, so its last fill still completes it.
     */
    fun replayed(fill: WireFill) {
        markBooked(fill.fillId)
        val id = fill.clientOrderId
        val taken = WireValues.decimal(fill.quantity, "quantity").subtract(received(fill))
        if (taken.signum() != 0) filled[id] = (filled[id] ?: BigDecimal.ZERO).add(taken)
    }

    /** The engine event an `order` update means, or null when it repeats what was already reported. */
    fun order(order: WireOrder): BrokerEvent.OrderEvent? {
        val id = order.clientOrderId
        if (done.contains(id)) return null
        quantities[id] = WireValues.decimal(order.quantity, "quantity")
        val strategy = strategyOf(id)
        return when (order.status) {
            "working" -> acceptance(order, strategy)
            "filled" -> {
                val reported = WireValues.decimal(order.filledQuantity, "filled_quantity")
                if (reported.signum() > 0) quantities[id] = reported
                val heard = filled[id]
                if (heard != null && reported.signum() > 0 && heard >= reported) {
                    end(id) { filledShort(order, strategy) }
                } else {
                    acceptance(order, strategy)
                }
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
        // The order completes on the venue's quantity; the engine books what the holding received ([GatewayInKindFees]).
        val cumulative = (filled[id] ?: BigDecimal.ZERO).add(quantity)
        val got = GatewayInKindFees.received(side, quantity, costs, symbols.baseAsset(symbol))
        val gotSoFar = (received[id] ?: BigDecimal.ZERO).add(got)
        val total = quantities[id]
        val strategy = strategyOf(id)
        if (total != null && cumulative < total) {
            filled[id] = cumulative
            received[id] = gotSoFar
            return BrokerEvent.OrderPartiallyFilled(
                id,
                fill.venueOrderId,
                symbol,
                side,
                price,
                got,
                gotSoFar,
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
            got,
            strategy,
            timestamp = fill.time,
            typedVenueCosts = costs,
        )
    }

    /** The engine event a `settlement` means: the contract settled at its price, for every holder. */
    fun settlement(settlement: WireSettlement): ContractSettled = settledEvent(settlement, symbols)

    private fun acceptance(
        order: WireOrder,
        strategy: String,
    ): BrokerEvent.OrderEvent? =
        if (accepted.add(order.clientOrderId)) {
            BrokerEvent.OrderAccepted(order.clientOrderId, order.venueOrderId, strategy)
        } else {
            null
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
        received.remove(id)
        accepted.remove(id)
    }

    private companion object {
        /** Far more executions and orders than any reconnect replays, so a replay is always recognized. */
        const val RECENT = 10_000
    }
}
