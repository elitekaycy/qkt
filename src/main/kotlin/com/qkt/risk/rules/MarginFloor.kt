package com.qkt.risk.rules

import com.qkt.broker.Broker
import com.qkt.execution.OrderRequest
import com.qkt.positions.PositionProvider
import com.qkt.risk.Decision
import com.qkt.risk.RiskRule
import com.qkt.risk.isRiskReducing
import java.math.BigDecimal

/**
 * Pre-entry margin floor: reject NEW-exposure orders while the venue's margin level
 * sits below [floorPct] (percent, e.g. 200 = 2x covered). MT5 force-closes the largest
 * losers around 50% — the broker's choice of position, during the exact volatility
 * spike that caused it — so entries must stop long before stop-out can. Risk-reducing
 * orders always pass: shrinking exposure is how margin level recovers. Venues that
 * report no margin level (paper, spot) pass everything.
 */
class MarginFloor(
    private val broker: Broker,
    private val floorPct: BigDecimal,
) : RiskRule {
    init {
        require(floorPct.signum() > 0) { "floorPct must be > 0: $floorPct" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (isRiskReducing(request, positions)) return Decision.Approve
        val level = broker.marginLevel() ?: return Decision.Approve
        if (level.signum() == 0) return Decision.Approve
        return if (level < floorPct) {
            Decision.Reject(
                "margin level ${level.toPlainString()}% below floor ${floorPct.toPlainString()}% — " +
                    "no new exposure until headroom recovers (stop-out is the alternative)",
            )
        } else {
            Decision.Approve
        }
    }
}
