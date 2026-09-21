package com.qkt.app

import com.qkt.broker.Broker
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

    /**
     * Flatten, then sweep the venue's own list: a resting order whose placement response was
     * lost is not among the orders the engine knows, and must not outlive a flatten.
     */
    fun flattenAndSweep() {
        doFlatten()
        VerifiedFlatten(
            broker,
            ticketAttribution,
            clock,
            strategies.map { it.first },
            {},
        ).sweepRestingOrders()
    }

    private fun doFlatten() {
        val strategyId = strategies.firstOrNull()?.first ?: return
        val now = clock.now()
        if (broker.supportsPositionTickets) {
            // Venue truth leads: every position the venue attributes to this strategy is
            // closed by ticket, through the ledger leg that owns it when there is one.
            val deployedIds = strategies.map { it.first }
            for (ticket in broker.positionTickets()) {
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
                pipeline.orderManager.cancelPendingForSymbol(ticket.symbol)
                val leg = strategyPositions.legBookFor(strategyId, ticket.symbol)?.legByTicket(ticket.ticket)
                val request =
                    if (leg != null) {
                        LegFlattener.closeLeg(strategyId, leg, ids.next(), now)
                    } else {
                        LegFlattener.closeTicket(strategyId, ticket, ids.next(), now)
                    }
                bus.publish(com.qkt.events.OrderEvent(request))
            }
            return
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
            pipeline.orderManager.cancelPendingForSymbol(leg.symbol)
            bus.publish(com.qkt.events.OrderEvent(LegFlattener.closeLeg(strategyId, leg, ids.next(), now)))
        }
    }
}
