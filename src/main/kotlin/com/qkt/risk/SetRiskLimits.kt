package com.qkt.risk

import java.math.BigDecimal

/** The caps `risk.per_strategy.<name>.sets.<set>` may set, before the set's members are known. */
data class SetCaps(
    val maxOpenPositions: Int? = null,
    val maxPositionSize: BigDecimal? = null,
) {
    init {
        require(maxOpenPositions == null || maxOpenPositions > 0) {
            "SetCaps.maxOpenPositions must be > 0: $maxOpenPositions"
        }
        require(maxPositionSize == null || maxPositionSize.signum() > 0) {
            "SetCaps.maxPositionSize must be > 0: $maxPositionSize"
        }
    }
}

/**
 * Per-strategy caps over one symbol set's [members] (qkt symbols), design §3.6: at most [maxOpenPositions] of them
 * held or entering at once ([com.qkt.risk.rules.MaxSetOpenPositions]), and no member's net position past
 * [maxPositionSize] ([com.qkt.risk.rules.MaxSetPositionSize]). Orders on symbols outside the set are untouched.
 */
data class SetRiskLimits(
    val members: Set<String>,
    val maxOpenPositions: Int? = null,
    val maxPositionSize: BigDecimal? = null,
) {
    init {
        require(members.isNotEmpty()) { "SetRiskLimits needs at least one member symbol" }
        require(maxOpenPositions == null || maxOpenPositions > 0) {
            "SetRiskLimits.maxOpenPositions must be > 0: $maxOpenPositions"
        }
        require(maxPositionSize == null || maxPositionSize.signum() > 0) {
            "SetRiskLimits.maxPositionSize must be > 0: $maxPositionSize"
        }
    }
}
