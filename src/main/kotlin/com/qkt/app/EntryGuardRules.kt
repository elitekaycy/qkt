package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.MarginLevelProvider
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

    /** Every enabled entry guard: the margin floor over [broker], then the measured-usage window. */
    fun rules(
        broker: Broker,
        symbols: List<String>,
    ): List<RiskRule> {
        warnWhereFloorCannotApply(broker, symbols)
        return marginRules(broker) + measuredRules()
    }

    /**
     * Says once per venue, at startup, that the floor protects nothing there: a venue that reports no margin level
     * approves every entry (see [com.qkt.risk.rules.MarginFloor]), and an operator who set a floor could assume otherwise.
     */
    private fun warnWhereFloorCannotApply(
        broker: Broker,
        symbols: List<String>,
    ) {
        if (marginFloorPct.signum() <= 0) return
        symbols
            .groupBy(broker::marginVenueFor)
            .filterKeys { (it as? MarginLevelProvider)?.supportsMarginLevel != true }
            .forEach { (venue, onVenue) ->
                log.warn(
                    "risk.margin_floor_pct {} cannot apply on venue {}: it reports no margin level, so entries on " +
                        "{} are not checked against the floor (risk.margin_floor_pct: 0 turns the floor off)",
                    marginFloorPct.toPlainString(),
                    venue.name,
                    onVenue.joinToString(", "),
                )
            }
    }

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
