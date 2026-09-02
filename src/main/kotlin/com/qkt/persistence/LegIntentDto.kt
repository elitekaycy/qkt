package com.qkt.persistence

import com.qkt.execution.LegIntent
import com.qkt.positions.LegRole
import kotlinx.serialization.Serializable

/**
 * On-disk shape of a [LegIntent]. Absent (null) on a DTO means [LegIntent.Unplanned], which keeps
 * every file written before intents existed readable without a schema bump.
 */
@Serializable
internal data class LegIntentDto(
    val kind: String,
    val legId: String? = null,
    val role: String? = null,
    val parentLegId: String? = null,
    val ticket: String? = null,
    val partial: Boolean = false,
) {
    fun toDomain(): LegIntent =
        when (kind) {
            KIND_OPEN ->
                LegIntent.Open(
                    legId = requireNotNull(legId) { "Open intent DTO missing legId" },
                    role = LegRole.valueOf(requireNotNull(role) { "Open intent DTO missing role" }),
                    parentLegId = parentLegId,
                )
            KIND_CLOSE -> LegIntent.Close(legId = legId, ticket = ticket, partial = partial)
            KIND_NET -> LegIntent.Net
            else -> error("Unknown LegIntent kind: $kind")
        }

    companion object {
        private const val KIND_OPEN = "Open"
        private const val KIND_CLOSE = "Close"
        private const val KIND_NET = "Net"

        /** `null` for [LegIntent.Unplanned] so unplanned leaves serialize exactly as before. */
        fun from(intent: LegIntent): LegIntentDto? =
            when (intent) {
                is LegIntent.Open ->
                    LegIntentDto(
                        KIND_OPEN,
                        legId = intent.legId,
                        role = intent.role.name,
                        parentLegId = intent.parentLegId,
                    )
                is LegIntent.Close ->
                    LegIntentDto(KIND_CLOSE, legId = intent.legId, ticket = intent.ticket, partial = intent.partial)
                LegIntent.Net -> LegIntentDto(KIND_NET)
                LegIntent.Unplanned -> null
            }
    }
}
