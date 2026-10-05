package com.qkt.app

import com.qkt.broker.closesFor
import com.qkt.bus.EventBus
import com.qkt.events.ContractSettled
import com.qkt.positions.StrategyPositionTracker

/**
 * Settles a venue's contract-level [ContractSettled] for the strategies that share one account: each
 * strategy holding the contract closes its own position at the settlement price (a fill with exit
 * reason `EXPIRY` that is no order's, as the backtest venue settles), even when the holdings net to
 * nothing at the venue. The venue's costs are shared by the size of each holding ([closesFor]).
 */
internal class ContractSettlement(
    private val bus: EventBus,
    private val positions: StrategyPositionTracker,
) {
    /** Settle for [strategyIds], the strategies this pipeline runs. */
    fun bind(strategyIds: List<String>) {
        bus.subscribe<ContractSettled> { e -> settle(e, strategyIds) }
    }

    private fun settle(
        event: ContractSettled,
        strategyIds: List<String>,
    ) {
        val holders =
            strategyIds.mapNotNull { id -> positions.positionFor(id, event.symbol)?.quantity?.let { id to it } }
        event.closesFor(holders).forEach(bus::publish)
    }
}
