package com.qkt.cli

import com.qkt.app.LiveSessionHandle
import com.qkt.app.OrderManager
import com.qkt.app.SessionPnl
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.PersistenceHealthDto
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.cli.observe.TradeDto
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The console line `qkt run` prints for every fill. */
internal fun tradeLogLine(
    trade: Trade,
    realized: BigDecimal,
): String =
    "[INFO] ${Instant.ofEpochMilli(trade.timestamp)} ${trade.side} ${trade.symbol} " +
        "qty=${trade.quantity.toPlainString()} px=${trade.price.toPlainString()} realized=${realized.toPlainString()}"

/** What `qkt run` does with every fill: print its console line and append it to the event ring. */
internal fun tradeRecorder(ring: EventRing): (Trade, BigDecimal, String) -> Unit =
    { trade, realized, _ ->
        println(tradeLogLine(trade, realized))
        ring.append("trade", tradeToJson(trade, realized))
    }

internal fun tradeToJson(
    trade: Trade,
    realized: BigDecimal,
) = buildJsonObject {
    put("timestamp", JsonPrimitive(Instant.ofEpochMilli(trade.timestamp).toString()))
    put("side", JsonPrimitive(trade.side.name))
    put("symbol", JsonPrimitive(trade.symbol))
    put("qty", JsonPrimitive(trade.quantity.toPlainString()))
    put("price", JsonPrimitive(trade.price.toPlainString()))
    put("realized", JsonPrimitive(realized.toPlainString()))
}

/** The pending stack layers `/status` reports, from the session's order manager. */
internal fun pendingStackLayers(infos: List<OrderManager.PendingStackLayerInfo>): List<PendingStackLayer> =
    infos.map {
        PendingStackLayer(
            stackId = it.stackId,
            layer = it.layer,
            triggerPrice = it.triggerPrice,
            side = it.side,
            quantity = it.quantity,
        )
    }

/** What a `qkt run` does on SIGTERM/SIGINT: stop the session, wait, close the observability server. */
internal fun gracefulShutdown(
    session: LiveSessionHandle,
    server: ObservabilityServer?,
    shutdownTimeoutMs: Long,
    flattenOnStop: Boolean,
) {
    System.err.println("[INFO] graceful shutdown initiated")
    session.stop()
    session.awaitTermination(Duration.ofMillis(shutdownTimeoutMs))
    if (flattenOnStop) {
        System.err.println("[INFO] flatten-on-stop requested (no-op in 12b paper mode)")
    }
    runCatching { server?.close() }
    System.err.println("[INFO] terminated; ${session.recentTrades().size} trades")
}

internal fun buildSnapshot(
    strategyName: String,
    strategyVersion: Int,
    startMs: Long,
    startedAt: String,
    trades: List<Trade>,
    pendingStackLayers: List<PendingStackLayer> = emptyList(),
    pnl: SessionPnl = SessionPnl.ZERO,
    persistenceHealth: com.qkt.persistence.PersistenceHealth = com.qkt.persistence.PersistenceHealth.DISABLED,
): StatusSnapshot {
    val now = System.currentTimeMillis()
    val last = trades.lastOrNull()
    return StatusSnapshot(
        strategy = strategyName,
        version = strategyVersion,
        uptimeMs = now - startMs,
        startedAt = startedAt,
        equity = pnl.equity,
        balance = pnl.balance,
        realized = pnl.realized,
        unrealized = pnl.unrealized,
        positions = emptyList<PositionDto>(),
        lastTrade =
            last?.let {
                TradeDto(
                    timestamp = Instant.ofEpochMilli(it.timestamp).toString(),
                    side = it.side.name,
                    symbol = it.symbol,
                    qty = it.quantity,
                    price = it.price,
                    realized = BigDecimal.ZERO,
                )
            },
        pendingStackLayers = pendingStackLayers,
        persistence =
            PersistenceHealthDto(
                enabled = persistenceHealth.enabled,
                totalWrites = persistenceHealth.totalWrites,
                slowWrites = persistenceHealth.slowWrites,
                failedWrites = persistenceHealth.failedWrites,
                consecutiveFailures = persistenceHealth.consecutiveFailures,
                failureEpisodes = persistenceHealth.failureEpisodes,
                queueSize = persistenceHealth.queueSize,
                callerRunsTotal = persistenceHealth.callerRunsTotal,
            ),
    )
}
