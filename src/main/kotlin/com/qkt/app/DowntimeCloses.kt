package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.common.Clock
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.persistence.PersistedLeg
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Positions that closed at the venue while the daemon was down. The startup reconcile retires
 * each such leg here; what the venue realized on it is booked once the pipeline exists, and the
 * owning strategy's rule edges are cleared so its entry can fire again, e.g. a leg on ticket 9001
 * stopped out overnight for -42.10 books -42.10 of realized PnL at the next start.
 */
internal class DowntimeCloses(
    private val broker: Broker,
    private val clock: Clock,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    // Venue-realized amounts on legs that closed while down. Booked once the pipeline exists,
    // through the same accounted-event fold as a live execution, so every accumulator and
    // the audit trail see them.
    private val bootReconciled = ArrayList<BootReconciled>()

    // Strategies with a leg that closed while the daemon was down. Their persisted rule
    // edges still describe the bar that opened the position, so an entry condition that is
    // true again after the restart would show no rising edge and never fire (the same trap
    // as a stop --flatten inside the entry bar). Cleared once the strategies are bound.
    private val edgeResetStrategies = LinkedHashSet<String>()

    /** The reconcile retired [leg]: remember its strategy and the venue-realized amount, if any. */
    fun onLegRetired(
        strategyId: String,
        leg: PersistedLeg,
    ) {
        edgeResetStrategies += strategyId
        // The leg's position closed while the daemon was down. Book what the venue
        // realized on it (OUT deals of that position ticket) so lifetime PnL and the
        // equity curve do not silently lose the trade; a venue with no deal history
        // just retires the leg with a warning.
        val ticket = leg.brokerTicket
        val closing =
            runCatching { broker.deals(leg.openedAt - 1L, clock.now()) }
                .getOrDefault(emptyList())
                .filter { d -> d.positionTicket == ticket && d.entry != "IN" }
        if (closing.isEmpty()) {
            log.warn(
                "{}: leg {} (ticket {}) closed while down; no closing deal found in venue history, " +
                    "realized PnL not booked",
                strategyId,
                leg.legId,
                ticket,
            )
        } else {
            val realized =
                closing.fold(BigDecimal.ZERO) { acc, d ->
                    acc
                        .add(d.profit)
                        .add(d.commission)
                        .add(d.swap)
                        .add(d.fee ?: BigDecimal.ZERO)
                }
            bootReconciled += BootReconciled(strategyId, leg.legId, realized)
            log.warn(
                "{}: leg {} (ticket {}) closed while down; booked realized {} from {} closing deal(s)",
                strategyId,
                leg.legId,
                ticket,
                realized.toPlainString(),
                closing.size,
            )
        }
    }

    /** Clear the rule edges of every strategy that lost a position to a downtime close. */
    fun clearRuleEdges(strategies: List<Pair<String, Strategy>>) {
        for (strategyId in edgeResetStrategies) {
            val strategy = strategies.firstOrNull { it.first == strategyId }?.second as? DslCompiledStrategy ?: continue
            runCatching { strategy.clearRuleEdges() }
                .onSuccess {
                    log.warn(
                        "{}: rule edges cleared — a position closed while the daemon was down",
                        strategyId,
                    )
                }.onFailure { t -> log.warn("could not clear rule edges for {} after downtime close", strategyId, t) }
        }
    }

    /** Book every venue-realized amount through [pipeline]'s accounted-event fold. */
    fun bookInto(pipeline: TradingPipeline) {
        for (booked in bootReconciled) {
            pipeline.applyReconciledRealized(booked.strategyId, booked.realized, booked.legId)
        }
    }
}

/** A venue-realized amount on a leg that closed while the daemon was down. */
private class BootReconciled(
    val strategyId: String,
    val legId: String,
    val realized: BigDecimal,
)
