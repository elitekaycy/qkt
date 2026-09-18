package com.qkt.cli

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A strategy's place in the promotion lifecycle, ordered draft to production; retired ranks below all. */
@Serializable
enum class PromotionState(
    val id: String,
) {
    @SerialName("draft")
    DRAFT("draft"),

    @SerialName("research")
    RESEARCH("research"),

    @SerialName("candidate")
    CANDIDATE("candidate"),

    @SerialName("paper")
    PAPER("paper"),

    @SerialName("shadow-live")
    SHADOW_LIVE("shadow-live"),

    @SerialName("small-capital")
    SMALL_CAPITAL("small-capital"),

    @SerialName("production")
    PRODUCTION("production"),

    @SerialName("retired")
    RETIRED("retired"),
    ;

    fun atLeast(required: PromotionState): Boolean = rank >= required.rank

    private val rank: Int
        get() =
            when (this) {
                DRAFT -> 0
                RESEARCH -> 1
                CANDIDATE -> 2
                PAPER -> 3
                SHADOW_LIVE -> 4
                SMALL_CAPITAL -> 5
                PRODUCTION -> 6
                RETIRED -> -1
            }

    companion object {
        fun fromId(raw: String?): PromotionState? {
            val normalized =
                raw
                    ?.trim()
                    ?.lowercase()
                    ?.replace('_', '-')
                    ?: return null
            return entries.firstOrNull { it.id == normalized }
        }
    }
}
