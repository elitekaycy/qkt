package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.persistence.orderrequest.OrderRequestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `pending-orders.json`: working orders keyed by client order id, including
 * pre-fill brackets and OTO wrappers whose entry-to-child arming must survive a restart.
 */
internal class PendingOrdersFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        orders: Map<String, OrderRequest>,
    ) {
        // Composite shapes with dedicated recovery channels are filtered upstream by
        // [com.qkt.app.OrderManager]. Pre-fill Brackets and OTO wrappers are retained here
        // because their entry-to-child arming state must survive a restart.
        val entries = orders.mapNotNull { (cid, req) -> OrderRequestDto.fromDomain(req)?.let { cid to it } }
        val dto =
            PendingOrdersDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                orders = entries.map { (cid, req) -> PendingOrderEntryDto(clientOrderId = cid, request = req) },
            )
        runCatching { json.encodeToString(PendingOrdersDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PENDING_ORDERS_FILE, it) }
            .onFailure { e -> writer.recordFailure("savePendingOrders encode for $strategyId", e) }
    }

    fun load(strategyId: String): Map<String, OrderRequest> {
        val raw = writer.read(strategyId, PENDING_ORDERS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(PendingOrdersDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadPendingOrders parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadPendingOrders schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.orders.associate { it.clientOrderId to it.request.toDomain() }
    }
}

private const val PENDING_ORDERS_FILE = "pending-orders.json"

@Serializable
private data class PendingOrdersDto(
    val version: Int,
    val strategyId: String,
    val orders: List<PendingOrderEntryDto>,
)

@Serializable
private data class PendingOrderEntryDto(
    val clientOrderId: String,
    val request: OrderRequestDto,
)
