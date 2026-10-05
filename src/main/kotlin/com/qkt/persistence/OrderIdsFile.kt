package com.qkt.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes `order-ids.json`: the last sequence each of a strategy's order-id generators issued. */
internal class OrderIdsFile(
    private val writer: StateFileWriter,
) : OrderIdPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun saveOrderIdMarks(
        strategyId: String,
        marks: Map<String, Long>,
    ) {
        runCatching { json.encodeToString(OrderIdsDto.serializer(), OrderIdsDto(STATE_SCHEMA_VERSION, marks)) }
            .onSuccess { writer.write(strategyId, ORDER_IDS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveOrderIdMarks encode for $strategyId", e) }
    }

    override fun loadOrderIdMarks(strategyId: String): Map<String, Long> {
        val raw = writer.read(strategyId, ORDER_IDS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(OrderIdsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadOrderIdMarks parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadOrderIdMarks schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.marks
    }

    @Serializable
    private data class OrderIdsDto(
        val version: Int,
        val marks: Map<String, Long>,
    )
}

private const val ORDER_IDS_FILE = "order-ids.json"
