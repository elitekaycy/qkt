package com.qkt.broker.continuous

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.TickSteps
import com.qkt.risk.rules.PreTradeControls
import java.math.BigDecimal

/**
 * Builds a roll's orders: market GTC with no [priceBand] (a backtest), with one (it judges far quotes before a roll) a
 * limit IOC at [PreTradeControls.ORDER_COLLAR_FRAC] from the reference, which a venue's own price limit will accept.
 */
internal class RollLegOrders(
    private val clock: Clock,
    private val chainOf: () -> ContinuousChain,
    private val priceBand: BigDecimal?,
) {
    /** The order under [id] for [quantity] on [symbol], whose contract's measured roll price is [reference]. */
    fun order(
        id: String,
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        strategyId: String,
        reference: BigDecimal,
    ): OrderRequest {
        val now = clock.now()
        priceBand ?: return OrderRequest.Market(id, symbol, side, quantity, TimeInForce.GTC, now, strategyId)
        val limit = collarPrice(side, reference, PreTradeControls.ORDER_COLLAR_FRAC, chainOf().root.tickSize)
        return OrderRequest.Limit(id, symbol, side, quantity, limit, TimeInForce.IOC, now, strategyId)
    }
}

/**
 * The furthest a roll leg on [side] may fill: [band] (a fraction) from [reference], above it to buy and below it to
 * sell, on the [tick] grid and never outside the band. E.g. at 0.25 around 78,000, a BUY's limit is 97,500 and a
 * SELL's 58,500. A reference so small that its limit would round to nothing is held at one tick.
 */
internal fun collarPrice(
    side: Side,
    reference: BigDecimal,
    band: BigDecimal,
    tick: BigDecimal,
): BigDecimal {
    val grid = TickSteps(tick)
    val limit =
        if (side == Side.BUY) {
            grid.floor(reference.multiply(BigDecimal.ONE.add(band)))
        } else {
            grid.ceil(reference.multiply(BigDecimal.ONE.subtract(band)))
        }
    return limit.takeIf { it.signum() > 0 } ?: tick
}
