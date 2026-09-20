package com.qkt.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads and writes `sequences.json`: the stage, stage snapshots and last condition values of
 * every DSL sequence a strategy tracks, so a restart resumes a half-matched sequence.
 */
internal class SequencesFile(
    private val writer: StateFileWriter,
    private val json: Json,
) {
    fun save(
        strategyId: String,
        states: Map<String, PersistedSequenceState>,
    ) {
        val dto =
            SequencesDto(
                version = STATE_SCHEMA_VERSION,
                strategyId = strategyId,
                sequences =
                    states.values.map { state ->
                        SequenceStateDto(
                            name = state.name,
                            stage = state.stage,
                            snapshots =
                                state.snapshots.map {
                                    SequenceSnapshotDto(
                                        stage = it.stage,
                                        price = it.price.toPlainString(),
                                        timeMs = it.timeMs,
                                    )
                                },
                            lastValues = state.lastValues,
                            completePulse = state.completePulse,
                        )
                    },
            )
        runCatching { json.encodeToString(SequencesDto.serializer(), dto) }
            .onSuccess { writer.write(strategyId, SEQUENCES_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveSequences encode for $strategyId", e) }
    }

    fun load(strategyId: String): Map<String, PersistedSequenceState> {
        val raw = writer.read(strategyId, SEQUENCES_FILE) ?: return emptyMap()
        val dto =
            try {
                json.decodeFromString(SequencesDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadSequences parse failed for $strategyId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadSequences schema mismatch for $strategyId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return dto.sequences.associate { state ->
            state.name to
                PersistedSequenceState(
                    name = state.name,
                    stage = state.stage,
                    snapshots =
                        state.snapshots.map {
                            PersistedSequenceSnapshot(
                                stage = it.stage,
                                price = it.price.toBigDecimal(),
                                timeMs = it.timeMs,
                            )
                        },
                    lastValues = state.lastValues,
                    completePulse = state.completePulse,
                )
        }
    }
}

private const val SEQUENCES_FILE = "sequences.json"

@Serializable
private data class SequencesDto(
    val version: Int,
    val strategyId: String,
    val sequences: List<SequenceStateDto>,
)

@Serializable
private data class SequenceStateDto(
    val name: String,
    val stage: Int,
    val snapshots: List<SequenceSnapshotDto>,
    val lastValues: Map<String, Boolean> = emptyMap(),
    val completePulse: Boolean = false,
)

@Serializable
private data class SequenceSnapshotDto(
    val stage: String,
    val price: String,
    val timeMs: Long,
)
