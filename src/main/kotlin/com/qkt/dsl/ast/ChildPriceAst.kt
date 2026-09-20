package com.qkt.dsl.ast

/** The price of a bracket or OCO child: an absolute level, a distance, a percent, an R multiple or an armed trail. */
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

/** A `BRACKET` clause: an optional stop-loss and take-profit child. */
data class BracketAst(
    val stopLoss: ChildPriceAst? = null,
    val takeProfit: ChildPriceAst? = null,
)

/** An `OCO` clause: a stop child and a limit child where one fill cancels the other. */
data class OcoAst(
    val stop: ChildPriceAst,
    val limit: ChildPriceAst,
)
