package com.qkt.dsl.compile

import com.qkt.persistence.PersistedTierState
import java.math.BigDecimal

/** The persisted tier definitions, in tier order, as a restored [StackEngine] holds them. */
internal fun PersistedTierState.resolvedTiers(): List<ResolvedStackTier> =
    tiers
        .sortedBy { it.index }
        .map {
            ResolvedStackTier(
                mfeThreshold = it.mfeThreshold,
                withinMs = it.withinMs,
                stackQuantity = it.stackQuantity,
                slDistance = it.slDistance,
                tpDistance = it.tpDistance,
                maeRecoverDistance = it.maeRecoverDistance,
            )
        }

/** Indices of tiers that fired before the restart. */
internal fun PersistedTierState.firedIndices(): Set<Int> = tiers.filter { it.fired }.map { it.index }.toSet()

/** Leg id each fired tier opened, by tier index. */
internal fun PersistedTierState.firedLegIds(): Map<Int, String> =
    tiers.mapNotNull { t -> t.firedLegId?.let { t.index to it } }.toMap()

/** Indices of tiers whose `WITHIN` window lapsed before the restart. */
internal fun PersistedTierState.abandonedIndices(): Set<Int> = tiers.filter { it.abandoned }.map { it.index }.toSet()

/** Adverse extremes armed by MAE-recovery tiers, by tier index. */
internal fun PersistedTierState.armedAdverseExtremes(): Map<Int, BigDecimal> =
    tiers.mapNotNull { t -> t.armedAdverseExtreme?.let { t.index to it } }.toMap()
