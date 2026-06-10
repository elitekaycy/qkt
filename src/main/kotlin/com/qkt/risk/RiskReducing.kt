package com.qkt.risk

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider

/**
 * True when [request] can only shrink exposure: a close-by-ticket, or an
 * opposite-side order no larger than the open position. Gates that block NEW risk
 * (halts, stale-data suppression, margin floors) let these through — blocking the
 * way out of a position is never the safe direction.
 */
fun isRiskReducing(
    request: OrderRequest,
    positions: PositionProvider,
): Boolean {
    if (request is OrderRequest.Market && request.closesTicket != null) return true
    val net = positions.positionFor(request.symbol)?.quantity ?: return false
    if (net.signum() == 0) return false
    val opposes =
        (net.signum() > 0 && request.side == Side.SELL) ||
            (net.signum() < 0 && request.side == Side.BUY)
    return opposes && request.quantity <= net.abs()
}
