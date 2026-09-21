package com.qkt.persistence

/** One strategy-scoped halt inside [PersistedRiskState]. */
data class PersistedStrategyHalt(
    val strategyId: String,
    val reason: String,
    val scope: String,
    val epochDay: Long,
    /** When the halt tripped, epoch ms; 0 for a halt recorded before this was tracked. */
    val haltedAtMs: Long = 0L,
)
