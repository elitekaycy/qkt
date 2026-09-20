package com.qkt.persistence

import com.qkt.persistence.orderrequest.OrderRequestDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Reads and writes `oco-legs.json`: the working legs of standalone OCO groups and their sibling
 * ids. Legs whose order shape cannot be persisted are skipped with a warning.
 */
internal class OcoLegsFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    // Logs under FileStatePersistor so existing log filters keep matching after the split.
    private val log = LoggerFactory.getLogger(FileStatePersistor::class.java)

    fun save(
        strategyId: String,
        legs: List<PersistedOcoLeg>,
    ) {
        val entries =
            legs.mapNotNull { leg ->
                val req = OrderRequestDto.fromDomain(leg.request)
                if (req == null) {
                    log.warn(
                        "saveOcoLegs: skipping non-persistable variant ${leg.request::class.simpleName} " +
                            "for $strategyId/${leg.clientOrderId}",
                    )
                    null
                } else {
                    OcoLegDto(
                        clientOrderId = leg.clientOrderId,
                        brokerOrderId = leg.brokerOrderId,
                        strategyId = leg.strategyId,
                        request = req,
                        siblingIds = leg.siblingIds,
                    )
                }
            }
        val dto = OcoLegsDto(version = STATE_SCHEMA_VERSION, strategyId = strategyId, legs = entries)
        runCatching { json.encodeToString(OcoLegsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, OCO_LEGS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveOcoLegs encode for $strategyId", e) }
    }

    fun load(strategyId: String): List<PersistedOcoLeg> {
        val raw = writer.read(strategyId, OCO_LEGS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(OcoLegsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadOcoLegs parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadOcoLegs schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.legs.map {
            PersistedOcoLeg(
                clientOrderId = it.clientOrderId,
                brokerOrderId = it.brokerOrderId,
                strategyId = it.strategyId,
                request = it.request.toDomain(),
                siblingIds = it.siblingIds,
            )
        }
    }
}

private const val OCO_LEGS_FILE = "oco-legs.json"

@Serializable
private data class OcoLegsDto(
    val version: Int,
    val strategyId: String,
    val legs: List<OcoLegDto>,
)

@Serializable
private data class OcoLegDto(
    val clientOrderId: String,
    val brokerOrderId: String,
    val strategyId: String,
    val request: OrderRequestDto,
    val siblingIds: List<String>,
)
