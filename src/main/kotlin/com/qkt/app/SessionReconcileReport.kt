package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.observe.insights.TicketAttribution
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Strategy

/**
 * The operator's on-demand reconcile (`qkt reconcile`): what the engine believes the owning
 * strategy holds against what the venue reports, e.g. an engine leg on ticket 9001 the venue no
 * longer lists comes back as one delta. The engine side is read as an engine-thread snapshot;
 * the broker reads run on the caller's thread.
 */
internal class SessionReconcileReport(
    private val strategies: List<Pair<String, Strategy>>,
    private val symbols: List<String>,
    private val broker: Broker,
    private val ticketAttribution: TicketAttribution,
    private val strategyPositions: StrategyPositionTracker,
    private val strategyPnL: StrategyPnL,
    private val snapshot: EngineSnapshot,
) {
    /** Build the report now; a failed broker read is reported, never thrown. */
    fun reconcile(): ReconcileReport {
        val ownerId = strategies.firstOrNull()?.first.orEmpty()
        val engineState =
            snapshot.engineSnapshot {
                strategyPositions.allLegsFor(ownerId) to strategyPnL.equityFor(ownerId)
            }
        // positionTickets() carries the venue ticket, so the broker side can be scoped
        // to this strategy by attribution and keyed identically to the engine tracker.
        // getOpenPositions() is magic-global and ticketless, which made the old diff
        // double-count (prefixed vs bare key) and cry wolf on a shared account (#413).
        var brokerReadError: String? = null
        val brokerTickets =
            try {
                broker.positionTickets()
            } catch (e: Exception) {
                brokerReadError = e.message ?: e::class.simpleName ?: "unknown broker read failure"
                emptyList()
            }
        val accountingModes =
            symbols.associate { symbol ->
                symbol.substringAfter(":") to broker.positionAccountingMode(symbol)
            }
        return ReconcileReport(
            deltas =
                reconcileDeltas(
                    ownerId,
                    brokerTickets,
                    ticketAttribution,
                    engineState.first,
                    accountingModes,
                ),
            engineEquity = engineState.second,
            brokerEquity = runCatching { broker.accountEquity() }.getOrNull(),
            protectionDeltas = reconcileProtectionDeltas(ownerId, brokerTickets, ticketAttribution),
            brokerReadFailed = brokerReadError != null,
            brokerReadError = brokerReadError,
        )
    }
}
