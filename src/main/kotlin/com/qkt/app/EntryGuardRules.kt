package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.CompositeBroker
import com.qkt.broker.MarginLevelProvider
import com.qkt.common.Clock
import com.qkt.derivatives.options.chain.OptionRootSymbol
import com.qkt.risk.RiskRule
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * The venues already told their margin floor cannot apply, by venue and floor, so an operator hears it once however
 * many strategies trade the venue. [PROCESS] is the one a running qkt shares across its sessions.
 */
internal class MarginFloorNotices {
    private val told = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /** Whether this is the first time [venue] is noted for [floor]. */
    fun first(
        venue: String,
        floor: BigDecimal,
    ): Boolean = told.add(venue to floor.stripTrailingZeros().toPlainString())

    companion object {
        val PROCESS = MarginFloorNotices()
    }
}

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
    private val notices: MarginFloorNotices = MarginFloorNotices.PROCESS,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** Every enabled entry guard: the margin floor over [broker], then the measured-usage window. */
    fun rules(
        broker: Broker,
        symbols: List<String>,
    ): List<RiskRule> {
        warnWhereFloorCannotApply(broker, symbols)
        warmMarginLevels(broker, symbols)
        return marginRules(broker) + measuredRules()
    }

    /**
     * Says once per venue, at startup, that the floor protects nothing there: a venue that reports no margin level
     * approves every entry (see [com.qkt.risk.rules.MarginFloor]), and an operator who set a floor could assume otherwise.
     * A symbol no venue trades (a chain or depth feed, read-only data) answers to the router, which is no venue: it has
     * no entry to check, so it is not named.
     */
    private fun warnWhereFloorCannotApply(
        broker: Broker,
        symbols: List<String>,
    ) {
        if (marginFloorPct.signum() <= 0) return
        symbols
            .groupBy { venueOf(broker, it) }
            .filterKeys { it !is CompositeBroker && (it as? MarginLevelProvider)?.supportsMarginLevel != true }
            .filterKeys { notices.first(it.name, marginFloorPct) }
            .forEach { (venue, onVenue) ->
                log.warn(
                    "risk.margin_floor_pct {} cannot apply on venue {}: {}, so no entry on this venue is checked " +
                        "against the floor, starting with {} (risk.margin_floor_pct: 0 turns the floor off)",
                    marginFloorPct.toPlainString(),
                    venue.name,
                    (venue as? MarginLevelProvider)?.marginFloorGap ?: "it reports no margin level",
                    onVenue.joinToString(", "),
                )
            }
    }

    /** Has each venue the floor will judge keep its level current, so its first entry finds a reading. */
    private fun warmMarginLevels(
        broker: Broker,
        symbols: List<String>,
    ) {
        if (marginFloorPct.signum() <= 0) return
        symbols.map { venueOf(broker, it) }.distinct().forEach { (it as? MarginLevelProvider)?.warmMarginLevel() }
    }

    /** The venue [symbol] answers to: a fed option root routes nowhere itself, its contracts trade on its venue. */
    private fun venueOf(
        broker: Broker,
        symbol: String,
    ): Broker {
        val root = OptionRootSymbol.parse(symbol).getOrNull() ?: return broker.marginVenueFor(symbol)
        val venue = root.root.substringBefore(':')
        val leaves = (broker as? CompositeBroker)?.allLeaves().orEmpty()
        val account = leaves.firstOrNull { it.name.equals(venue, ignoreCase = true) }
        return account?.marginVenueFor(symbol) ?: broker.marginVenueFor(symbol)
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
