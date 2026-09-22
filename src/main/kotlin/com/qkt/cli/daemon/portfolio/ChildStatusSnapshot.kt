package com.qkt.cli.daemon.portfolio

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.daemon.HaltStatus
import com.qkt.cli.daemon.buildSnapshot
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.StatusSnapshot
import java.time.Instant

/**
 * A portfolio child's `/status`, built from the same session reads as a standalone strategy's:
 * open positions, halt, stale and clock-skewed symbols and queue depth are keyed by the child's
 * [strategyId], while the snapshot is labelled with its display [childName].
 */
internal fun childStatusSnapshot(
    childName: String,
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
        childName,
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
        halt = HaltStatus.of(session, strategyId),
    )
}
