package com.qkt.research

import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule

/**
 * Refuses orders placed once a replay has no later price: the rules run by the final candle flush at the
 * end of the data. Signals fill at the next price everywhere else in a backtest, so filling these at the
 * last bar's own close would book a price no order could get, and would make the result depend on where
 * `--to` falls (one more day of data fills the same signal at the next open). They are recorded as
 * rejections instead.
 */
internal class ReplayEndRule : RiskRule {
    /** Set when the replay's last price has passed; every later order is refused. */
    var ended: Boolean = false

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision = if (ended) Decision.Reject(REASON) else Decision.Approve

    private companion object {
        const val REASON = "the replay ended before a later price: a signal on the last bar is not filled"
    }
}
