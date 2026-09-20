package com.qkt.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `pnl.json`: the strategy's cumulative realized PnL.
 */
internal class PnlFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        state: PersistedPnl,
    ) {
        val dto =
            PnlDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                realized = state.realized.toPlainString(),
            )
        runCatching { json.encodeToString(PnlDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PNL_FILE, it) }
            .onFailure { e -> writer.recordFailure("savePnl encode for $strategyId", e) }
    }

    fun load(strategyId: String): PersistedPnl? {
        val raw = writer.read(strategyId, PNL_FILE) ?: return null
        val dto =
            try {
                json.decodeFromString(PnlDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadPnl parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadPnl schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return PersistedPnl(realized = dto.realized.toBigDecimal())
    }
}

private const val PNL_FILE = "pnl.json"

@Serializable
private data class PnlDto(
    val version: Int,
    val strategyId: String,
    val realized: String,
)
