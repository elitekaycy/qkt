package com.qkt.cli.daemon

import com.qkt.cli.observe.PendingStackLayer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.cli.observe.TradeDto
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

internal fun signalToJson(sig: com.qkt.strategy.Signal) =
    buildJsonObject {
        when (sig) {
            is com.qkt.strategy.Signal.Buy -> {
                put("kind", JsonPrimitive("buy"))
                put("symbol", JsonPrimitive(sig.symbol))
                put("size", JsonPrimitive(sig.size.toPlainString()))
            }
            is com.qkt.strategy.Signal.Sell -> {
                put("kind", JsonPrimitive("sell"))
                put("symbol", JsonPrimitive(sig.symbol))
                put("size", JsonPrimitive(sig.size.toPlainString()))
            }
            is com.qkt.strategy.Signal.Submit -> {
                put("kind", JsonPrimitive("submit"))
                put("symbol", JsonPrimitive(sig.request.symbol))
                put("size", JsonPrimitive(sig.request.quantity.toPlainString()))
            }
            is com.qkt.strategy.Signal.CancelPendingForSymbol -> {
                put("kind", JsonPrimitive("cancel_stacks"))
                put("symbol", JsonPrimitive(sig.symbol))
            }
        }
    }

internal fun buildSnapshot(
    strategyName: String,
    strategyVersion: Int,
    startMs: Long,
    startedAt: String,
    trades: List<Trade>,
    pendingStackLayers: List<PendingStackLayer> = emptyList(),
): StatusSnapshot {
    val now = System.currentTimeMillis()
    val last = trades.lastOrNull()
    return StatusSnapshot(
        strategy = strategyName,
        version = strategyVersion,
        uptimeMs = now - startMs,
        startedAt = startedAt,
        equity = BigDecimal.ZERO,
        balance = BigDecimal.ZERO,
        realized = BigDecimal.ZERO,
        unrealized = BigDecimal.ZERO,
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
    )
}
