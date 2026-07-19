package com.qkt.dsl.ast

data class ActionOpts(
    val sizing: SizingAst? = null,
    val orderType: OrderTypeAst? = null,
    val tif: TifAst? = null,
    val bracket: BracketAst? = null,
    val oco: OcoAst? = null,
    val stack: StackAst? = null,
    /**
     * Phase 27: conditional bracketed stacks. Each clause fires once when its MFE
     * threshold is reached within its time window. Multiple clauses on one action are
     * independent of each other and of the parent's own bracket.
     */
    val stackAts: List<StackAtClause> = emptyList(),
    /**
     * OTO (one-triggers-other): child BUY/SELL actions placed only once this action's order
     * fills (`ON_FILL { ... }`). Each child may target a different stream, take the opposite
     * side, and price itself relative to the parent fill via the `entry` keyword. Empty for
     * the common case. Children are themselves [ActionAst] (BUY/SELL only), validated at compile.
     */
    val onFill: List<ActionAst> = emptyList(),
)

/**
 * One `STACK_AT` clause attached to a BUY/SELL action.
 *
 * Phase 27: MFE clauses fire when the parent leg's favorable excursion crosses
 * [mfeThreshold] within [withinDuration] of the parent's open. Recoil clauses set
 * [maeRecoverDistance], treating [mfeThreshold] as the MAE arming threshold; after
 * arming, the stack fires once price recovers by [maeRecoverDistance] from the worst
 * adverse extreme. Each stack has its own [bracket] and tracks independently as a
 * STACK leg in the [com.qkt.positions.LegBook].
 *
 * [sizing] uses the same `SizingAst` shape as the parent's sizing; the stack engine
 * resolves it at fire time using the parent's filled quantity as the reference. A
 * `SizeQty(0.30)` is a literal 0.30 lots; the "0.30 of main" pattern is expressed via
 * the regular sizing surface (no new sub-grammar).
 */
data class StackAtClause(
    val mfeThreshold: ExprAst,
    val withinDuration: DurationAst,
    val sizing: SizingAst,
    val bracket: BracketAst,
    val maeRecoverDistance: ExprAst? = null,
)

sealed interface SizingAst

data class SizeQty(
    val expr: ExprAst,
) : SizingAst

data class SizeNotional(
    val usd: ExprAst,
) : SizingAst

data class SizePctEquity(
    val frac: ExprAst,
) : SizingAst

data class SizePctBalance(
    val frac: ExprAst,
) : SizingAst

data class SizeRiskFrac(
    val frac: ExprAst,
) : SizingAst

data class SizeRiskAbs(
    val usd: ExprAst,
) : SizingAst

data class SizePositionFull(
    val stream: String,
) : SizingAst

sealed interface OrderTypeAst

data object Market : OrderTypeAst

data class Limit(
    val price: ExprAst,
) : OrderTypeAst

data class Stop(
    val price: ExprAst,
) : OrderTypeAst

data class StopLimit(
    val stopPrice: ExprAst,
    val limitPrice: ExprAst,
) : OrderTypeAst

data class TrailingBy(
    val distance: ExprAst,
) : OrderTypeAst

/** Trailing-stop distance in percentage points, so `1` means one percent. */
data class TrailingPct(
    val percent: ExprAst,
) : OrderTypeAst

sealed interface ChildPriceAst

data class ChildAt(
    val price: ExprAst,
) : ChildPriceAst

data class ChildBy(
    val distance: ExprAst,
    val ratchet: StopRatchetAst? = null,
) : ChildPriceAst

/** Engine-managed policy that tightens a `STOP LOSS BY` child without widening it. */
sealed interface StopRatchetAst

/** One direction-relative stop target applied after [mfeThreshold] is crossed. */
data class StopStepAst(
    val mfeThreshold: ExprAst,
    val profitDistance: ExprAst,
)

/** Ordered MFE milestones consumed once by an engine-managed stepped stop. */
data class SteppedStopAst(
    val steps: List<StopStepAst>,
) : StopRatchetAst

/** Fixed-interval distance decay for an engine-managed stop. */
data class TimeTightenAst(
    val tightenBy: ExprAst,
    val interval: DurationAst,
    val floorDistance: ExprAst,
) : StopRatchetAst

/** Relative bracket child price in percentage points, so `1` means one percent. */
data class ChildPct(
    val percent: ExprAst,
) : ChildPriceAst

data class ChildRr(
    val multiplier: ExprAst,
) : ChildPriceAst

data class ChildArmedTrail(
    val trailDistance: ExprAst,
    val mfeThreshold: ExprAst,
) : ChildPriceAst

data class BracketAst(
    val stopLoss: ChildPriceAst? = null,
    val takeProfit: ChildPriceAst? = null,
)

data class OcoAst(
    val stop: ChildPriceAst,
    val limit: ChildPriceAst,
)

sealed interface TifAst

data object Gtc : TifAst

data object Ioc : TifAst

data object Fok : TifAst

data object Day : TifAst

data class Gtd(
    val until: ExprAst,
) : TifAst
