package com.qkt.dsl.compile

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
    val maeRecoverDistance: BigDecimal? = null,
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
    val maeRecoverDistance: BigDecimal? = null,
)
