package com.qkt.persistence

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `pending-stacks.json`: per primary leg, the stack tiers still waiting to
 * fire, already fired, or abandoned.
 */
internal class PendingStacksFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        perPrimary: Map<String, PersistedTierState>,
    ) {
        val dto =
            PendingStacksDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                perPrimary =
                    perPrimary.map { (primaryLegId, state) ->
                        PrimaryTierStateDto(
                            primaryLegId = primaryLegId,
                            primaryClientOrderId = state.primaryClientOrderId,
                            tiers = state.tiers.map { TierDto.fromDomain(it) },
                            openedAtMs = state.openedAtMs,
                        )
                    },
            )
        runCatching { json.encodeToString(PendingStacksDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, PENDING_STACKS_FILE, it) }
            .onFailure { e -> writer.recordFailure("savePendingStacks encode for $strategyId", e) }
    }

    fun load(strategyId: String): Map<String, PersistedTierState> {
        val raw = writer.read(strategyId, PENDING_STACKS_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(PendingStacksDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadPendingStacks parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadPendingStacks schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.perPrimary.associate { entry ->
            entry.primaryLegId to
                PersistedTierState(
                    primaryClientOrderId = entry.primaryClientOrderId,
                    tiers = entry.tiers.map { it.toDomain() },
                    openedAtMs = entry.openedAtMs,
                )
        }
    }
}

private const val PENDING_STACKS_FILE = "pending-stacks.json"

@Serializable
private data class PendingStacksDto(
    val version: Int,
    val strategyId: String,
    val perPrimary: List<PrimaryTierStateDto>,
)

@Serializable
private data class PrimaryTierStateDto(
    val primaryLegId: String,
    val primaryClientOrderId: String,
    val tiers: List<TierDto>,
    val openedAtMs: Long? = null,
)

@Serializable
private data class TierDto(
    val index: Int,
    val mfeThreshold: String,
    val withinMs: Long,
    val stackQuantity: String,
    val slDistance: String,
    val tpDistance: String,
    val maeRecoverDistance: String? = null,
    val armedAdverseExtreme: String? = null,
    val fired: Boolean,
    val firedAt: Long? = null,
    val firedLegId: String? = null,
    val abandoned: Boolean = false,
) {
    fun toDomain(): PersistedTier =
        PersistedTier(
            index = index,
            mfeThreshold = BigDecimal(mfeThreshold),
            withinMs = withinMs,
            stackQuantity = BigDecimal(stackQuantity),
            slDistance = BigDecimal(slDistance),
            tpDistance = BigDecimal(tpDistance),
            maeRecoverDistance = maeRecoverDistance?.let(::BigDecimal),
            armedAdverseExtreme = armedAdverseExtreme?.let(::BigDecimal),
            fired = fired,
            firedAt = firedAt,
            firedLegId = firedLegId,
            abandoned = abandoned,
        )

    companion object {
        fun fromDomain(t: PersistedTier): TierDto =
            TierDto(
                index = t.index,
                mfeThreshold = t.mfeThreshold.toPlainString(),
                withinMs = t.withinMs,
                stackQuantity = t.stackQuantity.toPlainString(),
                slDistance = t.slDistance.toPlainString(),
                tpDistance = t.tpDistance.toPlainString(),
                maeRecoverDistance = t.maeRecoverDistance?.toPlainString(),
                armedAdverseExtreme = t.armedAdverseExtreme?.toPlainString(),
                fired = t.fired,
                firedAt = t.firedAt,
                firedLegId = t.firedLegId,
                abandoned = t.abandoned,
            )
    }
}
