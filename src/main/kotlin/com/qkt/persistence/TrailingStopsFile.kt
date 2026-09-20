package com.qkt.persistence

import com.qkt.persistence.orderrequest.OrderRequestDto
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Reads and writes `trailing-stops.json`: engine-managed trailing stops with their arming state,
 * high-water mark, step progress and current stop level. Stops whose order shape cannot be
 * persisted are skipped with a warning.
 */
internal class TrailingStopsFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    // Logs under FileStatePersistor so existing log filters keep matching after the split.
    private val log = LoggerFactory.getLogger(FileStatePersistor::class.java)

    fun save(
        strategyId: String,
        stops: List<PersistedTrailingStop>,
    ) {
        val entries =
            stops.mapNotNull { stop ->
                val req = OrderRequestDto.fromDomain(stop.request)
                if (req == null) {
                    log.warn(
                        "saveTrailingStops: skipping non-persistable variant ${stop.request::class.simpleName} " +
                            "for $strategyId/${stop.clientOrderId}",
                    )
                    null
                } else {
                    TrailingStopDto(
                        clientOrderId = stop.clientOrderId,
                        brokerOrderId = stop.brokerOrderId,
                        strategyId = stop.strategyId,
                        request = req,
                        armed = stop.armed,
                        hwm = stop.hwm.toPlainString(),
                        stepIndex = stop.stepIndex,
                        elapsedIntervals = stop.elapsedIntervals,
                        stopLevel = stop.stopLevel?.toPlainString(),
                    )
                }
            }
        val dto = TrailingStopsDto(version = STATE_SCHEMA_VERSION, strategyId = strategyId, stops = entries)
        runCatching { json.encodeToString(TrailingStopsDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, TRAILING_STOPS_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveTrailingStops encode for $strategyId", e) }
    }

    fun load(strategyId: String): List<PersistedTrailingStop> {
        val raw = writer.read(strategyId, TRAILING_STOPS_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(TrailingStopsDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadTrailingStops parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadTrailingStops schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.stops.map {
            PersistedTrailingStop(
                clientOrderId = it.clientOrderId,
                brokerOrderId = it.brokerOrderId,
                strategyId = it.strategyId,
                request = it.request.toDomain(),
                armed = it.armed,
                hwm = BigDecimal(it.hwm),
                stepIndex = it.stepIndex,
                elapsedIntervals = it.elapsedIntervals,
                stopLevel = it.stopLevel?.let { value -> BigDecimal(value) },
            )
        }
    }
}

private const val TRAILING_STOPS_FILE = "trailing-stops.json"

@Serializable
private data class TrailingStopsDto(
    val version: Int,
    val strategyId: String,
    val stops: List<TrailingStopDto>,
)

@Serializable
private data class TrailingStopDto(
    val clientOrderId: String,
    val brokerOrderId: String? = null,
    val strategyId: String,
    val request: OrderRequestDto,
    val armed: Boolean,
    val hwm: String,
    val stepIndex: Int = 0,
    val elapsedIntervals: Long = 0L,
    val stopLevel: String? = null,
)
