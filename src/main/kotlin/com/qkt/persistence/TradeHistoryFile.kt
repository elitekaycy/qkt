package com.qkt.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `trade-history.json`: the closed-trade outcomes (time, PnL, symbol) that
 * trade-count and loss-streak conditions read after a restart.
 */
internal class TradeHistoryFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        state: PersistedTradeHistory,
    ) {
        val dto =
            TradeHistoryDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                outcomes =
                    state.outcomes.map {
                        TradeOutcomeDto(
                            timestamp = it.timestamp,
                            pnl = it.pnl.toPlainString(),
                            symbol = it.symbol,
                        )
                    },
            )
        runCatching { json.encodeToString(TradeHistoryDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, TRADE_HISTORY_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveTradeHistory encode for $strategyId", e) }
    }

    fun load(strategyId: String): PersistedTradeHistory? {
        val raw = writer.read(strategyId, TRADE_HISTORY_FILE) ?: return null
        val dto =
            try {
                json.decodeFromString(TradeHistoryDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadTradeHistory parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadTradeHistory schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return PersistedTradeHistory(
            outcomes =
                dto.outcomes.map {
                    PersistedTradeOutcome(
                        timestamp = it.timestamp,
                        pnl = it.pnl.toBigDecimal(),
                        symbol = it.symbol,
                    )
                },
        )
    }
}

private const val TRADE_HISTORY_FILE = "trade-history.json"

@Serializable
private data class TradeHistoryDto(
    val version: Int,
    val strategyId: String,
    val outcomes: List<TradeOutcomeDto>,
)

@Serializable
private data class TradeOutcomeDto(
    val timestamp: Long,
    val pnl: String,
    val symbol: String,
)
