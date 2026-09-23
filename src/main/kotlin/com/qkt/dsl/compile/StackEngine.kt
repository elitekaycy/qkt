package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.positions.MfeTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Phase 27: fires conditional bracketed stack orders when the parent leg's MFE crosses
 * configured thresholds within configured time windows.
 *
 * One [StackEngine] per parent PRIMARY leg. Constructed when the parent fills (so the
 * entry price is known) and destroyed when the parent closes. The engine maintains its
 * own [MfeTracker] and a per-tier "fired" / "abandoned" set so each tier fires at most
 * once per parent lifecycle.
 *
 * On each tick: update MFE, then for each unfired-unabandoned tier:
 *   - If MFE ≥ threshold AND elapsed ≤ within → fire (emit a [Signal.Submit])
 *   - Else if elapsed > within → mark abandoned (tier won't fire this parent lifecycle)
 *
 * The emit callback receives the [Signal.Submit] — the runtime decides how to dispatch
 * it (typically through the existing [com.qkt.app.OrderManager] route).
 */
class StackEngine(
    val parentLegId: String,
    val parentSymbol: String,
    val closeWatchIds: Set<String> = emptySet(),
    private val parentSide: Side,
    private val parentEntryPrice: BigDecimal,
    private val tiers: List<ResolvedStackTier>,
    private val clock: Clock,
    private val emit: (Signal) -> Unit,
    private val idGenerator: () -> String = { defaultId(parentLegId) },
    private val strategyId: String = "",
    private val persistor: com.qkt.persistence.StatePersistor = com.qkt.persistence.NoopStatePersistor(),
    private val primaryClientOrderId: String = parentLegId,
    initialFiredTierIndices: Set<Int> = emptySet(),
    initialFiredLegIds: Map<Int, String> = emptyMap(),
    initialAbandonedTierIndices: Set<Int> = emptySet(),
    initialArmedAdverseExtremes: Map<Int, BigDecimal> = emptyMap(),
    /**
     * The parent's ORIGINAL open time when restoring after a restart — MFE `WITHIN`
     * windows keep counting from the real open, not from the restart. Null (fresh
     * fill) anchors at now.
     */
    initialOpenedAtMs: Long? = null,
    /** The parent's `EXIT AFTER` hold, saved with the tier state so a restore can re-apply it. */
    private val exitAfterMs: Long? = null,
) {
    private val mfeTracker = MfeTracker(parentSide, parentEntryPrice)
    private val firedTierIndices: MutableSet<Int> = initialFiredTierIndices.toMutableSet()
    private val firedAtBy: MutableMap<Int, Long> = mutableMapOf()
    private val firedLegIdBy: MutableMap<Int, String> = initialFiredLegIds.toMutableMap()
    private val abandonedTierIndices: MutableSet<Int> = initialAbandonedTierIndices.toMutableSet()
    private val armedAdverseExtremeBy: MutableMap<Int, BigDecimal> = initialArmedAdverseExtremes.toMutableMap()
    private val openedAt: Long = initialOpenedAtMs ?: clock.now()

    init {
        // Persist the initial snapshot so a restart knows the window anchor even if no
        // tier has fired yet. Noop persistor (backtests) makes this free.
        if (strategyId.isNotBlank()) runCatching { persistTiers() }
    }

    /**
     * Advances every tier on one market tick. Excursion is measured on [price] (the mark);
     * a firing tier anchors its bracket at the price its market leg will actually fill at —
     * [ask] for a BUY, [bid] for a SELL — falling back to [price] when the quote is absent.
     */
    fun onTick(
        price: BigDecimal,
        bid: BigDecimal? = null,
        ask: BigDecimal? = null,
    ) {
        mfeTracker.onTick(price)
        val fillAnchor = if (parentSide == Side.BUY) ask ?: price else bid ?: price
        val mfe = mfeTracker.value()
        val mae = mfeTracker.mae()
        val adverseExtreme = mfeTracker.adverseExtremePrice()
        val elapsed = clock.now() - openedAt
        var firedAny = false
        var abandonedAny = false
        var armedAny = false
        for ((idx, tier) in tiers.withIndex()) {
            if (idx in firedTierIndices || idx in abandonedTierIndices) continue
            when {
                elapsed > tier.withinMs -> {
                    abandonedTierIndices += idx
                    abandonedAny = true
                }
                tier.maeRecoverDistance == null && mfe >= tier.mfeThreshold -> {
                    val (signal, stackLegId) = buildStackSignal(idx, tier, fillAnchor)
                    emit(signal)
                    firedTierIndices += idx
                    firedAtBy[idx] = clock.now()
                    firedLegIdBy[idx] = stackLegId
                    firedAny = true
                }
                tier.maeRecoverDistance != null && adverseExtreme != null -> {
                    if (mae >= tier.mfeThreshold) {
                        val previousExtreme = armedAdverseExtremeBy[idx]
                        if (previousExtreme == null || isDeeperAdverseExtreme(adverseExtreme, previousExtreme)) {
                            armedAdverseExtremeBy[idx] = adverseExtreme
                            armedAny = true
                        }
                    }
                    val armedExtreme = armedAdverseExtremeBy[idx]
                    if (armedExtreme != null && recoveredFrom(armedExtreme, price) >= tier.maeRecoverDistance) {
                        val (signal, stackLegId) = buildStackSignal(idx, tier, fillAnchor)
                        emit(signal)
                        firedTierIndices += idx
                        firedAtBy[idx] = clock.now()
                        firedLegIdBy[idx] = stackLegId
                        firedAny = true
                    }
                }
            }
        }
        if ((firedAny || abandonedAny || armedAny) && strategyId.isNotBlank()) {
            runCatching { persistTiers() }
        }
    }

    private fun isDeeperAdverseExtreme(
        candidate: BigDecimal,
        current: BigDecimal,
    ): Boolean =
        when (parentSide) {
            Side.BUY -> candidate < current
            Side.SELL -> candidate > current
        }

    private fun recoveredFrom(
        adverseExtreme: BigDecimal,
        currentPrice: BigDecimal,
    ): BigDecimal =
        when (parentSide) {
            Side.BUY -> currentPrice.subtract(adverseExtreme)
            Side.SELL -> adverseExtreme.subtract(currentPrice)
        }

    private fun persistTiers() {
        val persistedTiers =
            tiers.mapIndexed { idx, tier ->
                com.qkt.persistence.PersistedTier(
                    index = idx,
                    mfeThreshold = tier.mfeThreshold,
                    withinMs = tier.withinMs,
                    stackQuantity = tier.stackQuantity,
                    slDistance = tier.slDistance,
                    tpDistance = tier.tpDistance,
                    maeRecoverDistance = tier.maeRecoverDistance,
                    armedAdverseExtreme = armedAdverseExtremeBy[idx],
                    fired = idx in firedTierIndices,
                    firedAt = firedAtBy[idx],
                    firedLegId = firedLegIdBy[idx],
                    abandoned = idx in abandonedTierIndices,
                )
            }
        val state =
            com.qkt.persistence.PersistedTierState(
                primaryClientOrderId = primaryClientOrderId,
                tiers = persistedTiers,
                openedAtMs = openedAt,
                exitAfterMs = exitAfterMs,
            )
        persistor.savePendingStacks(strategyId, mapOf(parentLegId to state))
    }

    fun mfe(): BigDecimal = mfeTracker.value()

    fun mae(): BigDecimal = mfeTracker.mae()

    fun firedCount(): Int = firedTierIndices.size

    fun abandonedCount(): Int = abandonedTierIndices.size

    fun isTerminal(): Boolean = firedTierIndices.size + abandonedTierIndices.size == tiers.size

    private fun buildStackSignal(
        tierIdx: Int,
        tier: ResolvedStackTier,
        fillAnchor: BigDecimal,
    ): Pair<Signal, String> {
        val ts = clock.now()
        val stackLegId = idGenerator() + "-tier$tierIdx"
        return stackBracketSignal(stackLegId, parentSymbol, parentSide, tier, fillAnchor, ts) to stackLegId
    }

    private companion object {
        fun defaultId(parentLegId: String): String = "$parentLegId-stack"
    }
}
