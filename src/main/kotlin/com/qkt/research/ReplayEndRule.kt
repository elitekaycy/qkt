package com.qkt.research

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/**
 * Refuses orders that would execute at once after a replay's last price: those placed by the rules the final
 * candle flush runs at the end of the data. Signals fill at the next price everywhere else in a backtest, so
 * filling a market order (or an already-marketable limit) at the last bar's own close books a price no order
 * could get, and makes the result depend on where `--to` falls (one more day of data fills the same signal at
 * the next open). Such orders are recorded as rejections; resting orders are accepted as before, since without
 * a later price they simply never fill. [price] is the last price of a symbol.
 */
internal class ReplayEndRule(
    private val price: (String) -> BigDecimal?,
) : RiskRule {
    /** Set when the replay's last price has passed. */
    var ended: Boolean = false

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision = if (ended && executesNow(request)) Decision.Reject(REASON) else Decision.Approve

    private fun executesNow(request: OrderRequest): Boolean =
        when (request) {
            is OrderRequest.Market -> true
            is OrderRequest.Limit -> price(request.symbol)?.let { marketable(request, it) } ?: false
            is OrderRequest.Bracket -> executesNow(request.entry)
            is OrderRequest.OTO -> executesNow(request.parent)
            is OrderRequest.TimeExit -> executesNow(request.target)
            else -> false
        }

    private fun marketable(
        limit: OrderRequest.Limit,
        last: BigDecimal,
    ): Boolean = if (limit.side == Side.BUY) last <= limit.limitPrice else last >= limit.limitPrice

    private companion object {
        const val REASON = "the replay ended before a later price: a signal on the last bar is not filled"
    }
}
