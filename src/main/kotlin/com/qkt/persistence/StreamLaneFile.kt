package com.qkt.persistence

import com.qkt.execution.OrderRequest
import com.qkt.persistence.orderrequest.OrderRequestDto
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes `<stream>-lane.json`: one continuous stream's lane, whole, as a restart resumes it. */
internal class StreamLaneFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        ownerId: String,
        lane: PersistedStreamLane,
    ) {
        runCatching { json.encodeToString(StreamLaneDto.serializer(), StreamLaneDto.of(lane)) }
            .onSuccess { writer.write(ownerId, symbolStateFileName(lane.stream, LANE_FILE), it) }
            .onFailure { e -> writer.recordFailure("saveStreamLane encode for $ownerId/${lane.stream}", e) }
    }

    fun load(
        ownerId: String,
        stream: String,
    ): PersistedStreamLane? {
        val raw = writer.read(ownerId, symbolStateFileName(stream, LANE_FILE)) ?: return null
        val dto =
            try {
                json.decodeFromString(StreamLaneDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadStreamLane parse failed for $ownerId/$stream", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadStreamLane schema mismatch for $ownerId/$stream: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.toDomain()
    }
}

private const val LANE_FILE = "lane.json"

@Serializable
private data class StreamLaneDto(
    val version: Int,
    val stream: String,
    val contractIndex: Int?,
    val strategies: List<StreamStrategyDto>,
    val orders: List<StreamOrderDto>,
    val holdings: List<ContractHoldingDto>,
    val legs: List<RollLegDto>,
    val cancelling: List<String>,
    val roll: StreamRollDto?,
) {
    fun toDomain() =
        PersistedStreamLane(
            stream,
            contractIndex,
            strategies.map { PersistedStreamStrategy(it.strategyId, BigDecimal(it.position), it.stopped) },
            orders.map { it.toDomain() },
            holdings.map {
                PersistedContractHolding(
                    it.strategyId,
                    it.contract,
                    BigDecimal(it.quantity),
                    BigDecimal(it.avgPrice),
                    it.openedAt,
                )
            },
            legs.map { it.toDomain() },
            cancelling,
            roll?.toDomain(),
        )

    companion object {
        fun of(l: PersistedStreamLane) =
            StreamLaneDto(
                STATE_SCHEMA_VERSION,
                l.stream,
                l.contractIndex,
                l.strategies.map { StreamStrategyDto(it.strategyId, it.position.toPlainString(), it.stopped) },
                l.orders.map(StreamOrderDto::of),
                l.holdings.map {
                    ContractHoldingDto(
                        it.strategyId,
                        it.contract,
                        it.quantity.toPlainString(),
                        it.avgPrice.toPlainString(),
                        it.openedAt,
                    )
                },
                l.legs.map(RollLegDto::of),
                l.cancelling,
                l.roll?.let(StreamRollDto::of),
            )
    }
}

@Serializable
private data class StreamStrategyDto(
    val strategyId: String,
    val position: String,
    val stopped: String?,
)

@Serializable
private data class ContractHoldingDto(
    val strategyId: String,
    val contract: String,
    val quantity: String,
    val avgPrice: String,
    val openedAt: Long?,
)

/** On-disk shape of a [PersistedStreamOrder]. */
@Serializable
internal data class StreamOrderDto(
    val request: OrderRequestDto,
    val venueId: String,
    val contractIndex: Int,
    val replacements: Int,
) {
    fun toDomain() = PersistedStreamOrder(request.toDomain(), venueId, contractIndex, replacements)

    companion object {
        fun of(o: PersistedStreamOrder) =
            StreamOrderDto(encodeRequest(o.request), o.venueId, o.contractIndex, o.replacements)

        /** [request] on disk; a lane only works the order shapes a contract takes, so any other is a fault. */
        fun encodeRequest(request: OrderRequest): OrderRequestDto =
            requireNotNull(OrderRequestDto.fromDomain(request)) {
                "a stream lane cannot persist ${request::class.simpleName} order ${request.id}"
            }
    }
}
