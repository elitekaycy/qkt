package com.qkt.pnl

import com.qkt.common.Side
import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.ProbabilityFee
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Charges an event contract's [ProbabilityFee] per order: `roundUp(rate × contracts × p × (1 − p), rounding)`, where
 * `p` is the fill price as a fraction of the contract's payout. Each fill is charged what the order's fee grew by
 * with it, so the rounding is paid once per order however many partial fills the order took (the
 * [EquityCostCommission] pattern). Fills at different prices add their own `contracts × p × (1 − p)`. In the
 * instrument's currency, times the payout (a contract paying 1 is charged exactly the formula).
 *
 * The taker rate is always used: a backtest fill does not say whether it rested, so a maker rate is never applied
 * and a resting order's fee is overstated where the venue charges makers less.
 *
 * Symbols without [EventTerms], and event contracts whose fee is unknown, are [fallback]'s, so every other
 * instrument costs exactly what it did before.
 */
class ProbabilityFeeCommission(
    private val instruments: InstrumentRegistry,
    private val fallback: CommissionModel,
) : CommissionModel {
    private class Order(
        var exposure: BigDecimal = BigDecimal.ZERO,
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
        requireNoFee(symbol)
        return fallback.cost(symbol, quantity)
    }

    override fun cost(
        symbol: String,
        quantity: BigDecimal,
        price: BigDecimal,
    ): BigDecimal {
        requireNoFee(symbol)
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
        val terms = meta?.derivative as? EventTerms
        val fee = terms?.fee
        if (meta == null || terms == null || fee == null) {
            return fallback.cost(symbol, side, quantity, price, orderId)
        }
        val contracts = quantity.abs().multiply(meta.contractSize)
        val spread = price.multiply(terms.payout.subtract(price)).max(BigDecimal.ZERO)
        val exposure = contracts.multiply(spread).divide(terms.payout, MathContext.DECIMAL128)
        synchronized(orders) {
            val order = orders.getOrPut("$symbol|$orderId") { Order() }
            order.exposure = order.exposure.add(exposure)
            val total = roundUp(fee.takerRate.multiply(order.exposure), fee.rounding)
            val charge = total.subtract(order.charged)
            order.charged = total
            return charge
        }
    }

    private fun roundUp(
        amount: BigDecimal,
        unit: BigDecimal,
    ): BigDecimal = amount.divide(unit, 0, RoundingMode.CEILING).multiply(unit)

    private fun requireNoFee(symbol: String) =
        require((instruments.lookup(symbol)?.derivative as? EventTerms)?.fee == null) {
            "the probability fee for $symbol needs the fill's side and order"
        }

    private companion object {
        const val MAX_ORDERS = 4096
    }
}
