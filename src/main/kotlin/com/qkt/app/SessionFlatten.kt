package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.BrokerPositionTicket
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.observe.insights.TicketAttribution
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * The engine-side flatten of a live session: close every position of the owning strategy.
 *
 * Flattening mutates the OrderManager and publishes closes, so it must run on the engine
 * thread — the HTTP control path enqueues [Inbound.Flatten] rather than touching engine
 * state from its own worker thread. On a ticketed venue the venue's own list leads, e.g. venue
 * ticket 9001 attributed to `gold` is closed by ticket even when the ledger holds no leg for it.
 * Its cancels are closing ones ([OrderManager.closePendingForSymbol]): the part of a bracket entry that
 * filled is closed with the position, and no stop or target is sent for it (#1328).
 */
internal class SessionFlatten(
    private val strategies: List<Pair<String, Strategy>>,
    private val clock: Clock,
    private val broker: Broker,
    private val ticketAttribution: TicketAttribution,
    private val pipeline: TradingPipeline,
    private val strategyPositions: StrategyPositionTracker,
    private val ids: IdGenerator,
    private val bus: EventBus,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)

    /** Set when the engine thread was interrupted during a venue read; restored once the flatten is done. */
    private var interrupted = false

    /**
     * Flatten, then sweep the venue's own list: a resting order whose placement response was
     * lost is not among the orders the engine knows, and must not outlive a flatten. Returns what
     * the flatten could not see or close, or null when nothing was left out.
     */
    fun flattenAndSweep(): String? {
        interrupted = false
        try {
            val gap = doFlatten()
            VerifiedFlatten(
                broker,
                ticketAttribution,
                clock,
                strategies.map { it.first },
                {},
            ).sweepRestingOrders()
            return gap
        } finally {
            // The interrupt was meant for the loop (a stop running out of patience), not this flatten.
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun doFlatten(): String? {
        val strategyId = strategies.firstOrNull()?.first ?: return null
        val now = clock.now()
        if (broker.supportsPositionTickets) {
            // Venue truth leads: every position the venue attributes to this strategy is
            // closed by ticket, through the ledger leg that owns it when there is one.
            val deployedIds = strategies.map { it.first }
            val venueTickets = readVenueTickets() ?: return closeLedgerTickets(strategyId, now)
            for (ticket in venueTickets) {
                val owner =
                    ticketAttribution.ownerOf(ticket.ticket)
                        ?: ticketAttribution.fromComment(ticket.comment, deployedIds)
                if (owner != strategyId) {
                    if (owner == null) {
                        log.error(
                            "flatten skipped unattributed ticket {} on {}; operator intervention required",
                            ticket.ticket,
                            ticket.symbol,
                        )
                    }
                    continue
                }
                pipeline.orderManager.closePendingForSymbol(ticket.symbol)
                val leg = strategyPositions.legBookFor(strategyId, ticket.symbol)?.legByTicket(ticket.ticket)
                val request =
                    if (leg != null) {
                        LegFlattener.closeLeg(strategyId, leg, ids.next(), now)
                    } else {
                        LegFlattener.closeTicket(strategyId, ticket, ids.next(), now)
                    }
                bus.publish(com.qkt.events.OrderEvent(request))
            }
            return null
        }
        for (leg in strategyPositions.allLegsFor(strategyId)) {
            if (broker.positionAccountingMode(leg.symbol) != com.qkt.broker.PositionAccountingMode.NETTING) {
                log.error(
                    "flatten cannot safely close {} on broker {} without position tickets; " +
                        "accounting mode is {}",
                    leg.symbol,
                    broker.name,
                    broker.positionAccountingMode(leg.symbol),
                )
                continue
            }
            pipeline.orderManager.closePendingForSymbol(leg.symbol)
            bus.publish(com.qkt.events.OrderEvent(LegFlattener.closeLeg(strategyId, leg, ids.next(), now)))
        }
        return null
    }

    /**
     * The venue's position list, read up to [VENUE_READ_ATTEMPTS] times. A failed or interrupted
     * read (a busy gateway, a stop interrupting the engine thread) is read again; null when every
     * attempt failed.
     */
    private fun readVenueTickets(): List<BrokerPositionTicket>? {
        for (attempt in 1..VENUE_READ_ATTEMPTS) {
            try {
                return broker.positionTickets()
            } catch (e: Exception) {
                if (Thread.interrupted() || e.isInterruption()) interrupted = true
                log.warn("flatten venue position read {}/{} failed: {}", attempt, VENUE_READ_ATTEMPTS, e.message)
            }
            if (attempt < VENUE_READ_ATTEMPTS) pause(VENUE_READ_BACKOFF_MS * attempt)
        }
        return null
    }

    /**
     * The venue could not be read: close every ledger leg pinned to a venue ticket, and report that
     * positions the ledger does not hold could not be looked for.
     */
    private fun closeLedgerTickets(
        strategyId: String,
        now: Long,
    ): String {
        val closed = mutableListOf<String>()
        val unpinned = mutableListOf<String>()
        for (leg in strategyPositions.allLegsFor(strategyId)) {
            val ticket = leg.brokerTicket
            if (ticket == null) {
                unpinned += "${leg.legId} (${leg.symbol})"
                continue
            }
            pipeline.orderManager.closePendingForSymbol(leg.symbol)
            bus.publish(com.qkt.events.OrderEvent(LegFlattener.closeLeg(strategyId, leg, ids.next(), now)))
            closed += ticket
        }
        return "venue positions unreadable after $VENUE_READ_ATTEMPTS reads on ${broker.name}; closed ledger " +
            "tickets $closed" + (if (unpinned.isEmpty()) "" else ", could not close legs without a ticket $unpinned") +
            "; positions the ledger does not hold were not checked — verify the venue"
    }

    private fun pause(ms: Long) {
        if (Thread.interrupted()) interrupted = true
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }

    private fun Throwable.isInterruption(): Boolean =
        generateSequence(this) { it.cause }.any { it is InterruptedException }

    private companion object {
        const val VENUE_READ_ATTEMPTS: Int = 3
        const val VENUE_READ_BACKOFF_MS: Long = 500L
    }
}
