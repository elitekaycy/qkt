package com.qkt.app

import com.qkt.events.BrokerEvent
import com.qkt.positions.StrategyPositionTracker

/** Books a venue position reconciliation [e] into the strategy position books, net per symbol. */
internal fun StrategyPositionTracker.reconcile(e: BrokerEvent.PositionReconciled) =
    reconcileNet(
        e.symbol,
        e.newQty,
        e.newAvgPx,
        openedAt = e.timestamp,
        source = e.source,
        ticket = e.ticket,
        strategyId = e.strategyId,
    )
