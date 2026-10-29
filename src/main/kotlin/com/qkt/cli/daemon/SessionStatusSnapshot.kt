package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.StatusSnapshot
import java.time.Instant

/**
 * A live session's `/status`, for a standalone strategy and a portfolio child alike: open positions,
 * halt, stale and clock-skewed symbols and queue depth are keyed by [strategyId], while the snapshot
 * is labelled with its display [name] (a child's `book_366/s1`).
 */
internal fun sessionStatusSnapshot(
    name: String,
    version: Int,
    strategyId: String,
    startMs: Long,
    startedAt: Instant,
    session: LiveSessionHandle,
): StatusSnapshot {
    val layers =
        session.pendingStackLayerInfos().map {
            PendingStackLayer(
                stackId = it.stackId,
                layer = it.layer,
                triggerPrice = it.triggerPrice,
                side = it.side,
                quantity = it.quantity,
            )
        }
    return buildSnapshot(
        name,
        version,
        startMs,
        startedAt.toString(),
        session.recentTrades(),
        layers,
        streamBrokers = session.streamBrokers(),
        pnl = session.pnlSnapshot(strategyId),
        inboundQueueDepth = session.inboundQueueDepth(),
        droppedTicks = session.droppedTicks,
        staleSymbols = session.staleSymbols().keys.sorted(),
        clockSkewedSymbols = session.clockSkewedSymbols(),
        openPositions = session.positionsFor(strategyId),
        persistenceHealth = session.persistenceHealth(),
        auditJournal = session.auditJournalHealth(),
        halt = HaltStatus.of(session, strategyId),
    )
}
