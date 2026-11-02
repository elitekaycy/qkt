package com.qkt.pnl

import com.qkt.common.Side
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * Charges a share's [com.qkt.instrument.EquityCosts] per order: each fill is charged what the order's cost grew by
 * with it, so a per-order minimum is paid once however many partial fills the order took, and a sale's fees are
 * rounded once per order. In the instrument's currency (USD shares in a USD account). Symbols without equity costs
 * are [fallback]'s, so every other instrument costs exactly what it did before.
 */
class EquityCostCommission(
    private val instruments: InstrumentRegistry,
    private val fallback: CommissionModel,
) : CommissionModel {
    private class Order(
        var shares: BigDecimal = BigDecimal.ZERO,
        var notional: BigDecimal = BigDecimal.ZERO,
        var charged: BigDecimal = BigDecimal.ZERO,
    )

    /** Recent orders, oldest dropped first: a partial fill arrives while its order is among the latest. */
    private val orders =
        object : LinkedHashMap<String, Order>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Order>?) = size > MAX_ORDERS
        }

    override fun cost(
        symbol: String,
        quantity: BigDecimal,
    ): BigDecimal {
        requireNoEquityCosts(symbol)
        return fallback.cost(symbol, quantity)
    }

    override fun cost(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
    ): BigDecimal {
        requireNoEquityCosts(symbol)
        return fallback.cost(symbol, quantity, price)
    }

    override fun cost(
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        price: BigDecimal,
        orderId: String,
    ): BigDecimal {
        val meta = instruments.lookup(symbol)
        val costs = meta?.equityCosts ?: return fallback.cost(symbol, side, quantity, price, orderId)
        val shares = quantity.abs().multiply(meta.contractSize)
        synchronized(orders) {
            val order = orders.getOrPut("$symbol|$orderId") { Order() }
            order.shares = order.shares.add(shares)
            order.notional = order.notional.add(shares.multiply(price))
            val total = costs.orderCost(side, order.shares, order.notional)
            val charge = total.subtract(order.charged)
            order.charged = total
            return charge
        }
    }

    private fun requireNoEquityCosts(symbol: String) =
        require(
            instruments.lookup(symbol)?.equityCosts == null,
        ) { "equity costs for $symbol need the fill's side and order" }

    private companion object {
        const val MAX_ORDERS = 4096
    }
}
