package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.events.StructureOutcome
import com.qkt.strategy.StructureState
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes `structures.json`: one strategy's live option structures, whole, as a restart restores them. */
internal class StructuresFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        structures: List<PersistedStructure>,
    ) {
        val dto = StructuresDto(STATE_SCHEMA_VERSION, structures.map(StructureDto::of))
        runCatching { json.encodeToString(StructuresDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, STRUCTURES_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveStructures encode for $strategyId", e) }
    }

    fun load(strategyId: String): List<PersistedStructure> {
        val raw = writer.read(strategyId, STRUCTURES_FILE) ?: return emptyList()
        val dto =
            try {
                json.decodeFromString(StructuresDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadStructures parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadStructures schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.structures.map { it.toDomain() }
    }
}

private const val STRUCTURES_FILE = "structures.json"

@Serializable
private data class StructuresDto(
    val version: Int,
    val structures: List<StructureDto>,
)

@Serializable
private data class StructureDto(
    val id: String,
    val alias: String,
    val size: String,
    val state: String,
    val exit: String,
    val legs: List<StructureLegDto>,
) {
    fun toDomain() =
        PersistedStructure(
            id,
            alias,
            BigDecimal(size),
            StructureState.valueOf(state),
            StructureOutcome.valueOf(exit),
            legs.map { it.toDomain() },
        )

    companion object {
        fun of(s: PersistedStructure) =
            StructureDto(
                s.id,
                s.alias,
                s.size.toPlainString(),
                s.state.name,
                s.exit.name,
                s.legs.map(StructureLegDto::of),
            )
    }
}

@Serializable
private data class StructureLegDto(
    val symbol: String,
    val side: String,
    val openOrderId: String,
    val contractSize: String,
    val expiryMs: Long,
    val opened: String,
    val entryPrice: String? = null,
    val held: String,
    val realized: String,
    val openEnded: Boolean,
    val closing: Map<String, String> = emptyMap(),
) {
    fun toDomain() =
        PersistedStructureLeg(
            symbol,
            Side.valueOf(side),
            openOrderId,
            BigDecimal(contractSize),
            expiryMs,
            BigDecimal(opened),
            entryPrice?.let(::BigDecimal),
            BigDecimal(held),
            BigDecimal(realized),
            openEnded,
            closing.mapValues { BigDecimal(it.value) },
        )

    companion object {
        fun of(l: PersistedStructureLeg) =
            StructureLegDto(
                l.symbol,
                l.side.name,
                l.openOrderId,
                l.contractSize.toPlainString(),
                l.expiryMs,
                l.opened.toPlainString(),
                l.entryPrice?.toPlainString(),
                l.held.toPlainString(),
                l.realized.toPlainString(),
                l.openEnded,
                l.closing.mapValues { it.value.toPlainString() },
            )
    }
}
