package com.qkt.persistence

import com.qkt.common.Side
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes `timed-exits.json`: the armed `EXIT AFTER` timers of one strategy. */
internal class TimedExitsFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        exits: List<PersistedTimeExit>,
    ) {
        val dto =
            TimedExitsDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                exits =
                    exits.map {
                        TimedExitDto(
                            id = it.id,
                            strategyId = it.strategyId,
                            symbol = it.symbol,
                            side = it.side.name,
                            quantity = it.quantity.toPlainString(),
                            legId = it.legId,
                            ticket = it.ticket,
                            deadlineMs = it.deadlineMs,
                            protectiveIds = it.protectiveIds,
                        )
                    },
            )
        runCatching { json.encodeToString(TimedExitsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, TIMED_EXITS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveTimedExits encode for $strategyId", e) }
    }

    fun load(strategyId: String): List<PersistedTimeExit> {
        val raw = writer.read(strategyId, TIMED_EXITS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(TimedExitsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadTimedExits parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadTimedExits schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.exits.map {
            PersistedTimeExit(
                id = it.id,
                strategyId = it.strategyId,
                symbol = it.symbol,
                side = Side.valueOf(it.side),
                quantity = BigDecimal(it.quantity),
                legId = it.legId,
                ticket = it.ticket,
                deadlineMs = it.deadlineMs,
                protectiveIds = it.protectiveIds,
            )
        }
    }
}

private const val TIMED_EXITS_FILE = "timed-exits.json"

@Serializable
private data class TimedExitsDto(
    val version: Int,
    val strategyId: String,
    val exits: List<TimedExitDto>,
)

@Serializable
private data class TimedExitDto(
    val id: String,
    val strategyId: String,
    val symbol: String,
    val side: String,
    val quantity: String,
    val legId: String? = null,
    val ticket: String? = null,
    val deadlineMs: Long,
    val protectiveIds: List<String> = emptyList(),
)
