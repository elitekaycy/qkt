package com.qkt.broker

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * The refusal an MT5 venue gives a limit order on the wrong side of the market: a BUY_LIMIT must rest below
 * the ask and a SELL_LIMIT above the bid. A limit already through the market is not executed as a market order;
 * the venue refuses it (gateway: "BUY_LIMIT price must be below current ask", proven on the Exness demo by the
 * `marketable-limit-entry` attestation). [current] is the side's execution price: the ask for a BUY, the bid
 * for a SELL. Null when the limit may rest.
 */
internal fun mt5LimitSideRefusal(
    request: OrderRequest,
    current: BigDecimal,
): String? {
    val limit = request as? OrderRequest.Limit ?: return null
    return when {
        limit.side == Side.BUY && limit.limitPrice >= current ->
            "invalid price: BUY_LIMIT ${limit.limitPrice.toPlainString()} must be below current ask ${current.toPlainString()}"
        limit.side == Side.SELL && limit.limitPrice <= current ->
            "invalid price: SELL_LIMIT ${limit.limitPrice.toPlainString()} must be above current bid ${current.toPlainString()}"
        else -> null
    }
}
