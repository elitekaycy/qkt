package com.qkt.app

import com.qkt.app.LiveSession.Companion.RECONCILE_READ_ATTEMPTS
import com.qkt.broker.Broker
import com.qkt.broker.BrokerPositionTicket
import com.qkt.common.Clock
import com.qkt.observe.insights.TicketAttribution
import com.qkt.persistence.PersistedLeg
import com.qkt.persistence.StatePersistor
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Strategy
import org.slf4j.LoggerFactory

/**
 * Three-way reconcile: persisted leg state + broker positions → attached LegBook
 * or refusal. Runs once at startup before the engine thread takes ticks, e.g. a persisted
 * BUY 0.10 leg the venue still holds is re-attached; one the venue no longer lists is retired;
 * a venue position nobody persisted throws [ReconcileException] unless mismatches are ignored.
 */
internal class StartupReconcile(
    private val strategies: List<Pair<String, Strategy>>,
    private val symbols: List<String>,
    private val persistor: StatePersistor,
    clock: Clock,
    private val ignoreMismatches: Boolean,
    private val reconcileReadBackoffMs: Long,
    ticketAttribution: TicketAttribution,
) {
    // Logged under the session's category so existing log filters keep matching.
    private val log = LoggerFactory.getLogger(LiveSession::class.java)
    private val scope = ReconcileTicketScope(ticketAttribution)
    private val adoptedLegs = AdoptedLegs(clock)

    /** Reconcile every (strategy, symbol); returns the count of legs adopted per strategy id. */
    fun run(
        strategyPositions: StrategyPositionTracker,
        broker: Broker,
        onLegRetired: (strategyId: String, leg: PersistedLeg) -> Unit = { _, _ -> },
    ): Map<String, Int> {
        val adoptedLegCounts = mutableMapOf<String, Int>()
        // Never reconcile against assumed state: a transient broker error that reads as
        // "no open positions" lets the session start flat while holding leveraged
        // positions. Retry with backoff; refuse to start without one clean read.
        var brokerByQktSymbol: Map<String, List<com.qkt.positions.Position>>? = null
        var lastReadError: Throwable? = null
        for (attempt in 1..RECONCILE_READ_ATTEMPTS) {
            val read = runCatching { broker.getOpenPositions() }
            val positions = read.getOrNull()
            if (positions != null) {
                brokerByQktSymbol = positions
                break
            }
            lastReadError = read.exceptionOrNull()
            log.warn(
                "reconcile: broker position read failed (attempt {}/{}): {}",
                attempt,
                RECONCILE_READ_ATTEMPTS,
                lastReadError?.message,
            )
            if (attempt < RECONCILE_READ_ATTEMPTS) Thread.sleep(reconcileReadBackoffMs * attempt)
        }
        if (brokerByQktSymbol == null) {
            throw ReconcileException(
                "broker position read failed $RECONCILE_READ_ATTEMPTS times — refusing to start " +
                    "on assumed state. Last error: ${lastReadError?.message}",
            )
        }
        // Venue tickets for adopting unmatched positions under ignore-mismatches. positionTickets()
        // is qkt-keyed and carries the broker ticket; getOpenPositions() above is ticketless, and a
        // leg adopted without its ticket can't be closed per-leg on a hedging account (#437).
        val brokerTicketRead = runCatching { broker.positionTickets() }
        if (broker.supportsPositionTickets && brokerTicketRead.isFailure) {
            log.warn(
                "reconcile: position-ticket read failed; retaining magic-global fail-closed behavior: {}",
                brokerTicketRead.exceptionOrNull()?.message,
            )
        }
        val brokerTickets = brokerTicketRead.getOrElse { emptyList() }
        val scopeByTicket =
            broker.supportsPositionTickets &&
                strategies.size == 1 &&
                brokerTicketRead.isSuccess &&
                scope.ticketSnapshotMatches(
                    brokerPositions = brokerByQktSymbol,
                    tickets = brokerTickets,
                )
        if (broker.supportsPositionTickets && strategies.size == 1 && brokerTicketRead.isSuccess && !scopeByTicket) {
            log.warn("reconcile: position and ticket snapshots differ; retaining magic-global fail-closed behavior")
        }
        val brokerTicketsBySymbol = brokerTickets.groupBy(BrokerPositionTicket::symbol)
        // The venue's full open-ticket set is what lets reconcile tell "closed while we were
        // down" from a real mismatch (#1079); only authoritative when the ticket read succeeded.
        val venueTickets: Set<String>? =
            if (broker.supportsPositionTickets && brokerTicketRead.isSuccess) {
                brokerTickets.map(BrokerPositionTicket::ticket).toSet()
            } else {
                null
            }
        val reconciler = com.qkt.persistence.LegBookReconciler(persistor)
        for ((strategyId, _) in strategies) {
            for (symbol in symbols) {
                val allTicketsForSymbol = brokerTicketsBySymbol[symbol].orEmpty()
                val ticketsForStrategy =
                    if (scopeByTicket) {
                        allTicketsForSymbol.filter { ticket -> scope.isPotentiallyOwnedBy(ticket, strategyId) }
                    } else {
                        allTicketsForSymbol
                    }
                if (scopeByTicket && ticketsForStrategy.size != allTicketsForSymbol.size) {
                    log.info(
                        "reconcile: excluded {} position(s) on {} clearly attributed to another strategy",
                        allTicketsForSymbol.size - ticketsForStrategy.size,
                        symbol,
                    )
                }
                val brokerForSymbol =
                    if (scopeByTicket) {
                        ticketsForStrategy.map(scope::ticketPosition)
                    } else {
                        brokerByQktSymbol[symbol] ?: emptyList()
                    }
                val outcome = reconciler.reconcile(strategyId, symbol, brokerForSymbol, venueTickets)
                when (outcome) {
                    is com.qkt.persistence.LegBookReconciler.Outcome.Attached -> {
                        outcome.retired.forEach { leg -> onLegRetired(strategyId, leg) }
                        // Rebuild the whole book from disk — the engine hasn't run yet, so use the
                        // persistor preload path rather than applyFill. preloadFromPersistor loads
                        // every leg regardless of role, so call it once per reconciled (strategy,
                        // symbol). The old per-leg PRIMARY gate skipped any book with no PRIMARY leg
                        // — every OCO/straddle book is INDEPENDENT legs — so those positions were
                        // left out of the tracker after a restart: POSITION.<stream> read 0 and the
                        // dsl bracket + winner-timeout were dead (#432).
                        strategyPositions.preloadFromPersistor(strategyId, symbol)
                    }
                    is com.qkt.persistence.LegBookReconciler.Outcome.Mismatch -> {
                        if (!ignoreMismatches) {
                            throw ReconcileException(
                                "$strategyId/$symbol: ${outcome.details}. " +
                                    "Pass --reconcile=ignore-mismatches to adopt the venue positions as independent legs.",
                            )
                        }
                        log.warn(
                            "Reconcile mismatch (ignored): {}/{} — {}",
                            strategyId,
                            symbol,
                            outcome.details,
                        )
                        val attachLegs = adoptedLegs.from(strategyId, symbol, ticketsForStrategy, brokerForSymbol)
                        for (leg in attachLegs) {
                            strategyPositions.addIndependentLeg(strategyId, leg)
                        }
                        adoptedLegCounts.merge(strategyId, attachLegs.size, Int::plus)
                    }
                    com.qkt.persistence.LegBookReconciler.Outcome.NothingPersisted -> {
                        // Clean state. Nothing to do.
                    }
                }
            }
        }
        return adoptedLegCounts
    }
}
