package com.qkt.persistence

import java.math.BigDecimal

/** On-disk shape of one tier of a stacking parent: its trigger, its sizing and whether it fired. */
data class PersistedTier(
    val index: Int,
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
    val maeRecoverDistance: BigDecimal? = null,
    val armedAdverseExtreme: BigDecimal? = null,
    val fired: Boolean,
    val firedAt: Long?,
    val firedLegId: String?,
    /** True when this tier's MFE window elapsed unfired — it must not fire after a restart. */
    val abandoned: Boolean = false,
)

/** On-disk shape of one stacking parent's tiers, keyed by its opening order. */
data class PersistedTierState(
    val primaryClientOrderId: String,
    val tiers: List<PersistedTier>,
    /**
     * When the parent leg opened (the engine's MFE-window anchor). Restored engines
     * keep counting their `WITHIN` windows from the ORIGINAL open, not the restart.
     * Null in pre-restore state files; restore falls back to "now" with a warning.
     */
    val openedAtMs: Long? = null,
    /** The parent's `EXIT AFTER` hold, re-applied to legs that fire after a restore; null without one. */
    val exitAfterMs: Long? = null,
)
