package com.qkt.connector.gateway

import com.qkt.accounting.VenueCost
import com.qkt.common.Side
import java.math.BigDecimal

/**
 * A fee a spot venue takes in the coin bought (Bybit spot, Alpaca coin pairs): the venue's fill names the order's full
 * quantity, yet the holding moves by less. qkt books what was received, so its position stays the venue's: a buy's
 * quantity less the coin fee, a sale's plus it (the fee leaves the holding too). The fee's value stays one of the
 * fill's costs, converted at the fill price, so the cash is exact: the basis of the coin given up is the fee booked.
 * A rebate paid in the coin (a negative cost) adds to the holding the same way.
 * The order still completes on the venue's own quantity.
 */
internal object GatewayInKindFees {
    /** What [fill] moved the holding by, given the pair's [baseAsset]. */
    fun received(
        fill: WireFill,
        baseAsset: String?,
    ): BigDecimal =
        received(
            WireValues.sideOf(fill.side),
            WireValues.decimal(fill.quantity, "quantity"),
            WireValues.costsOf(fill.costs, fill.time),
            baseAsset,
        )

    /** What a fill of [quantity] on [side] moved the holding by, given its [costs] and the pair's [baseAsset]. */
    fun received(
        side: Side,
        quantity: BigDecimal,
        costs: List<VenueCost>,
        baseAsset: String?,
    ): BigDecimal {
        baseAsset ?: return quantity
        val fee =
            costs
                .filter { it.amount.currency.equals(baseAsset, ignoreCase = true) }
                .fold(BigDecimal.ZERO) { sum, cost -> sum.add(cost.amount.amount) }
        if (fee.signum() == 0) return quantity
        val received = if (side == Side.BUY) quantity.subtract(fee) else quantity.add(fee)
        if (received.signum() <= 0) {
            throw GatewayProtocolException("a fill of $quantity charged $fee $baseAsset, the coin itself, as its fee")
        }
        return received
    }
}
