package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.risk.RiskRule
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * The two optional entry guards a live session adds to its risk engine: the pre-entry margin
 * floor (#398) and the measured-usage window (#399). Zero disables either, e.g. a floor of 200
 * with a 0h window yields just the [com.qkt.risk.rules.MarginFloor] rule.
 */
internal class EntryGuardRules(
    private val clock: Clock,
    private val marginFloorPct: BigDecimal,
    private val measuredUsageHours: Long,
    private val measuredUsageMaxQty: BigDecimal,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** The margin-floor rule over [broker], or none when the floor is zero. */
    fun marginRules(broker: Broker): List<RiskRule> =
        if (marginFloorPct.signum() > 0) {
            listOf(
                com.qkt.risk.rules
                    .MarginFloor(broker, marginFloorPct),
            )
        } else {
            emptyList()
        }

    /** The measured-usage rule, its window starting now, or none when the window is zero hours. */
    fun measuredRules(): List<RiskRule> =
        if (measuredUsageHours > 0L) {
            log.warn(
                "measured-usage window active for {}h: entries above {} reject " +
                    "(risk.measured_usage_hours: 0 opts out)",
                measuredUsageHours,
                measuredUsageMaxQty.toPlainString(),
            )
            listOf(
                com.qkt.risk.rules.MeasuredUsage(
                    clock = clock,
                    startedAtMs = clock.now(),
                    windowHours = measuredUsageHours,
                    maxQty = measuredUsageMaxQty,
                ),
            )
        } else {
            emptyList()
        }
}
