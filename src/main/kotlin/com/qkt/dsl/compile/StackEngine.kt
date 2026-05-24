package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.MfeTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Phase 27 + Phase 37: one compiled `STACK_AT` tier. [mfeThreshold], [slDistance],
 * [tpDistance] are evaluated at compile time. Sizing is deferred to parent-fill time —
 * [resolveStackQuantity] takes the parent leg's filled quantity and returns the absolute
 * lot size for this tier. For literal-only sizing (no `ENTRY_QTY`) the lambda ignores
 * its argument and returns the constant.
 *
 * [slDistance] / [tpDistance] are in price units — the same units as the `BY` clause in
 * [com.qkt.dsl.ast.BracketAst].
 */
data class CompiledStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val resolveStackQuantity: (BigDecimal) -> BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)

/**
 * Phase 37: a [CompiledStackTier] with [CompiledStackTier.resolveStackQuantity] already
 * applied. Held by [StackEngine] from parent-fill time onward — the per-tick path reads
 * a plain [BigDecimal] and never re-evaluates the sizing expression.
 */
data class ResolvedStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)

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
) {
    private val mfeTracker = MfeTracker(parentSide, parentEntryPrice)
    private val firedTierIndices: MutableSet<Int> = initialFiredTierIndices.toMutableSet()
    private val firedAtBy: MutableMap<Int, Long> = mutableMapOf()
    private val firedLegIdBy: MutableMap<Int, String> = initialFiredLegIds.toMutableMap()
    private val abandonedTierIndices: MutableSet<Int> = mutableSetOf()
    private val openedAt: Long = clock.now()

    fun onTick(price: BigDecimal) {
        mfeTracker.onTick(price)
        val mfe = mfeTracker.value()
        val elapsed = clock.now() - openedAt
        var firedAny = false
        for ((idx, tier) in tiers.withIndex()) {
            if (idx in firedTierIndices || idx in abandonedTierIndices) continue
            when {
                mfe >= tier.mfeThreshold && elapsed <= tier.withinMs -> {
                    val (signal, stackLegId) = buildStackSignal(idx, tier, price)
                    emit(signal)
                    firedTierIndices += idx
                    firedAtBy[idx] = clock.now()
                    firedLegIdBy[idx] = stackLegId
                    firedAny = true
                }
                elapsed > tier.withinMs -> {
                    abandonedTierIndices += idx
                }
            }
        }
        if (firedAny && strategyId.isNotBlank()) {
            runCatching { persistTiers() }
        }
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
                    fired = idx in firedTierIndices,
                    firedAt = firedAtBy[idx],
                    firedLegId = firedLegIdBy[idx],
                )
            }
        val state =
            com.qkt.persistence.PersistedTierState(
                primaryClientOrderId = primaryClientOrderId,
                tiers = persistedTiers,
            )
        persistor.savePendingStacks(strategyId, mapOf(parentLegId to state))
    }

    fun mfe(): BigDecimal = mfeTracker.value()

    fun firedCount(): Int = firedTierIndices.size

    fun abandonedCount(): Int = abandonedTierIndices.size

    fun isTerminal(): Boolean = firedTierIndices.size + abandonedTierIndices.size == tiers.size

    /**
     * Build the stack signal: a [OrderRequest.Bracket] on the same symbol and side as
     * the parent, sized to [ResolvedStackTier.stackQuantity], with SL and TP computed
     * from the current price ± tier distance.
     */
    private fun buildStackSignal(
        tierIdx: Int,
        tier: ResolvedStackTier,
        currentPrice: BigDecimal,
    ): Pair<Signal, String> {
        val (sl, tp) =
            when (parentSide) {
                Side.BUY -> currentPrice.subtract(tier.slDistance) to currentPrice.add(tier.tpDistance)
                Side.SELL -> currentPrice.add(tier.slDistance) to currentPrice.subtract(tier.tpDistance)
            }
        val ts = clock.now()
        val stackLegId = idGenerator() + "-tier$tierIdx"
        val market =
            OrderRequest.Market(
                id = "$stackLegId-entry",
                symbol = parentSymbol,
                side = parentSide,
                quantity = tier.stackQuantity,
                timeInForce = TimeInForce.GTC,
                timestamp = ts,
            )
        val signal =
            Signal.Submit(
                OrderRequest.Bracket(
                    id = stackLegId,
                    symbol = parentSymbol,
                    side = parentSide,
                    quantity = tier.stackQuantity,
                    entry = market,
                    takeProfit = tp,
                    stopLoss = sl,
                    timeInForce = TimeInForce.GTC,
                    timestamp = ts,
                ),
            )
        return signal to stackLegId
    }

    private companion object {
        fun defaultId(parentLegId: String): String = "$parentLegId-stack"
    }
}
