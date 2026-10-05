package com.qkt.persistence

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes `order-fills.json`: how much of each live, partly filled order has filled. */
internal class OrderFillsFile(
    private val writer: StateFileWriter,
) : OrderFillPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun saveOrderFills(
        strategyId: String,
        fills: Map<String, PersistedOrderFill>,
    ) {
        val orders =
            fills.mapValues { (_, f) ->
                OrderFillDto(f.filledQuantity.toPlainString(), f.avgFillPrice?.toPlainString(), f.positionTicket)
            }
        runCatching { json.encodeToString(OrderFillsDto.serializer(), OrderFillsDto(STATE_SCHEMA_VERSION, orders)) }
            .onSuccess { writer.write(strategyId, ORDER_FILLS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveOrderFills encode for $strategyId", e) }
    }

    override fun loadOrderFills(strategyId: String): Map<String, PersistedOrderFill> {
        val raw = writer.read(strategyId, ORDER_FILLS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(OrderFillsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadOrderFills parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadOrderFills schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.orders.mapValues { (_, f) ->
            PersistedOrderFill(BigDecimal(f.filled), f.avgPrice?.let(::BigDecimal), f.ticket)
        }
    }
}

private const val ORDER_FILLS_FILE = "order-fills.json"

@Serializable
private data class OrderFillsDto(
    val version: Int,
    val orders: Map<String, OrderFillDto>,
)

@Serializable
private data class OrderFillDto(
    val filled: String,
    val avgPrice: String? = null,
    val ticket: String? = null,
)
