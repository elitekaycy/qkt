package com.qkt.dsl.ast

/**
 * The options on a BUY/SELL action: sizing, order type, TIF, bracket, OCO, stack and STACK AT
 * tiers, ON_FILL children, exit hooks, the TIMES repeat count and the EXIT AFTER timed exit.
 * Every field is optional.
 */
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
    /** One-shot actions dispatched when the parent position exits. */
    val exitHooks: ExitHooksAst = ExitHooksAst(),
    /**
     * `TIMES <expr>`: emit this entry that many times in one evaluation. Each repetition is a
     * separate order with its own id and its own copy of every clause on the action (bracket,
     * stack, STACK_AT tiers), exactly as if the action had been written out N times separated
     * by `;`. The expression is evaluated when the rule fires, so the count can follow a
     * condition, an indicator, or the account: `TIMES 30`, `TIMES CASE WHEN strong THEN 5
     * ELSE 1 END`, `TIMES floor(ACCOUNT.balance / 25000)`. Fractions truncate; zero or a
     * negative value emits nothing; an undefined value (indicator warm-up) emits nothing.
     */
    val times: ExprAst? = null,
    /**
     * `EXIT AFTER <duration>`: close this entry's own leg at market once it has been open for
     * the duration, timed from its fill on the engine clock (checked every tick, not at bar
     * close). Also applies to each `STACK_AT` leg, timed from that leg's own fill.
     */
    val exitAfter: DurationAst? = null,
)

/** Exit-triggered child actions attached to one BUY/SELL action. */
data class ExitHooksAst(
    val onStop: List<ActionAst> = emptyList(),
    val onTakeProfit: List<ActionAst> = emptyList(),
    val onClose: List<ActionAst> = emptyList(),
) {
    /** True when at least one exit hook is declared. */
    fun isEmpty(): Boolean = onStop.isEmpty() && onTakeProfit.isEmpty() && onClose.isEmpty()
}

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
