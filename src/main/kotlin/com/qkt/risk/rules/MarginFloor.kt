package com.qkt.risk.rules

import com.qkt.broker.Broker
import com.qkt.broker.MarginLevelProvider
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
 * report no margin level (paper, spot) pass everything. An entry answers to the account it is
 * routed to ([Broker.marginVenueFor]), so on a multi-venue strategy each account has its own
 * reading, and its own count of missing ones: one venue's healthy read never excuses another's.
 */
class MarginFloor(
    private val broker: Broker,
    private val floorPct: BigDecimal,
) : RiskRule {
    private val missingReads = HashMap<Broker, Int>()

    init {
        require(floorPct.signum() > 0) { "floorPct must be > 0: $floorPct" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        if (isRiskReducing(request, positions)) return Decision.Approve
        val venue = broker.marginVenueFor(request.symbol)
        if ((venue as? MarginLevelProvider)?.supportsMarginLevel != true) return Decision.Approve
        val level = venue.marginLevel()
        if (level == null) {
            val missing = missingReads.getOrDefault(venue, 0) + 1
            missingReads[venue] = missing
            return if (missing >= MISSING_READ_REJECT_THRESHOLD) {
                Decision.Reject("margin level unavailable after $missing reads — new exposure blocked")
            } else {
                Decision.Approve
            }
        }
        missingReads.remove(venue)
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

    private companion object {
        const val MISSING_READ_REJECT_THRESHOLD = 3
    }
}
