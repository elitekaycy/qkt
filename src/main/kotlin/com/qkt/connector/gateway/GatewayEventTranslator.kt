package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.common.Side
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
        quantities[id] = decimal(order.quantity, "quantity")
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
            "cancelled" ->
                end(
                    id,
                ) { BrokerEvent.OrderCancelled(id, order.venueOrderId, "cancelled at the venue", strategy) }
            "rejected" ->
                end(id) {
                    BrokerEvent.OrderRejected(
                        id,
                        order.venueOrderId,
                        order.rejectReason ?: "rejected by the venue",
                        strategy,
                    )
                }
            else -> throw GatewayProtocolException("order $id has status '${order.status}'")
        }
    }

    /** The engine event a `fill` means, or null for a fill already booked. */
    fun fill(fill: WireFill): BrokerEvent.OrderEvent? {
        val symbol = symbols.qkt(fill.symbol)
        val side = sideOf(fill.side)
        val quantity = decimal(fill.quantity, "quantity")
        val price = decimal(fill.price, "price")
        val costs = costsOf(fill.costs, fill.time)
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
            decimal(settlement.price, "price"),
            costsOf(settlement.costs, settlement.time),
            settlement.time,
        )

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
        quantities.remove(id)
        filled.remove(id)
        accepted.remove(id)
    }

    private fun costsOf(
        costs: List<WireCost>,
        at: Long,
    ): List<VenueCost> =
        costs.map { VenueCost(kindOf(it.kind), MoneyAmount(decimal(it.amount, "cost"), it.currency), at) }

    private fun sideOf(side: String): Side =
        when (side) {
            "buy" -> Side.BUY
            "sell" -> Side.SELL
            else -> throw GatewayProtocolException("side '$side'")
        }

    private fun kindOf(kind: String): CostKind =
        when (kind) {
            "commission" -> CostKind.COMMISSION
            // A delivery fee is an exchange fee, as the backtest option venue reports it.
            "exchange_fee", "delivery_fee" -> CostKind.EXCHANGE_FEE
            "funding" -> CostKind.FUNDING
            "swap" -> CostKind.SWAP
            else -> throw GatewayProtocolException("cost kind '$kind'")
        }

    private fun decimal(
        text: String,
        field: String,
    ): BigDecimal = text.toBigDecimalOrNull() ?: throw GatewayProtocolException("$field '$text' is not a decimal")

    private companion object {
        /** Far more executions and orders than any reconnect replays, so a replay is always recognized. */
        const val RECENT = 10_000
    }
}

/** The last [capacity] ids added, oldest dropped first. */
internal class RecentIds(
    private val capacity: Int,
) {
    private val ids =
        object : LinkedHashMap<String, Unit>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > capacity
        }

    /** Adds [id]; false when it was already among the recent ones. */
    fun add(id: String): Boolean = ids.put(id, Unit) == null

    /** Whether [id] is among the recent ones. */
    fun contains(id: String): Boolean = ids.containsKey(id)
}
