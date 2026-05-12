package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.MfeTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * One compiled `STACK_AT` tier. [com.qkt.dsl.ast.StackAtClause] expressions are
 * constant-evaluated at compile time so the per-tick path doesn't pay for expression
 * evaluation.
 *
 * [stackQuantity] is the absolute size of the stack leg in the action's sizing units
 * (lots for `SizeQty`), matching the spec on [com.qkt.dsl.ast.StackAtClause].
 * [slDistance] / [tpDistance] are in price units — the same units as the `BY` clause in
 * [com.qkt.dsl.ast.BracketAst].
 */
data class CompiledStackTier(
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
    private val parentSide: Side,
    private val parentEntryPrice: BigDecimal,
    private val tiers: List<CompiledStackTier>,
    private val clock: Clock,
    private val emit: (Signal) -> Unit,
    private val idGenerator: () -> String = { defaultId(parentLegId) },
) {
    private val mfeTracker = MfeTracker(parentSide, parentEntryPrice)
    private val firedTierIndices: MutableSet<Int> = mutableSetOf()
    private val abandonedTierIndices: MutableSet<Int> = mutableSetOf()
    private val openedAt: Long = clock.now()

    fun onTick(price: BigDecimal) {
        mfeTracker.onTick(price)
        val mfe = mfeTracker.value()
        val elapsed = clock.now() - openedAt
        for ((idx, tier) in tiers.withIndex()) {
            if (idx in firedTierIndices || idx in abandonedTierIndices) continue
            when {
                mfe >= tier.mfeThreshold && elapsed <= tier.withinMs -> {
                    emit(buildStackSignal(idx, tier, price))
                    firedTierIndices += idx
                }
                elapsed > tier.withinMs -> {
                    abandonedTierIndices += idx
                }
            }
        }
    }

    fun mfe(): BigDecimal = mfeTracker.value()

    fun firedCount(): Int = firedTierIndices.size

    fun abandonedCount(): Int = abandonedTierIndices.size

    fun isTerminal(): Boolean = firedTierIndices.size + abandonedTierIndices.size == tiers.size

    /**
     * Build the stack signal: a [OrderRequest.Bracket] on the same symbol and side as
     * the parent, sized to [CompiledStackTier.stackQuantity], with SL and TP computed
     * from the current price ± tier distance.
     */
    private fun buildStackSignal(
        tierIdx: Int,
        tier: CompiledStackTier,
        currentPrice: BigDecimal,
    ): Signal {
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
        return Signal.Submit(
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
    }

    private companion object {
        fun defaultId(parentLegId: String): String = "$parentLegId-stack"
    }
}
