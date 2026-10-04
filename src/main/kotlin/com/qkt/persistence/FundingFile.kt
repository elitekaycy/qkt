package com.qkt.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads and writes `funding.json`: what a session has booked of perpetual funding, as a restart resumes it. */
internal class FundingFile(
    private val writer: StateFileWriter,
) : FundingPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    override fun saveFunding(
        ownerId: String,
        funding: PersistedFunding,
    ) {
        runCatching { json.encodeToString(FundingDto.serializer(), FundingDto(STATE_SCHEMA_VERSION, funding.sinceMs, funding.booked)) }
            .onSuccess { writer.write(ownerId, FUNDING_FILE, it) }
            .onFailure { e -> writer.recordFailure("saveFunding encode for $ownerId", e) }
    }

    override fun loadFunding(ownerId: String): PersistedFunding? {
        val raw = writer.read(ownerId, FUNDING_FILE) ?: return null
        val dto =
            try {
                json.decodeFromString(FundingDto.serializer(), raw)
            } catch (e: SerializationException) {
                throw IllegalStateException("loadFunding parse failed for $ownerId", e)
            }
        require(dto.version == STATE_SCHEMA_VERSION) {
            "loadFunding schema mismatch for $ownerId: ${dto.version} != $STATE_SCHEMA_VERSION"
        }
        return PersistedFunding(dto.sinceMs, dto.booked)
    }

    @Serializable
    private data class FundingDto(
        val version: Int,
        val sinceMs: Long,
        val booked: Map<String, Long>,
    )
}

private const val FUNDING_FILE = "funding.json"
