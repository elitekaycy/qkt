package com.qkt.dsl.compile

import com.qkt.dsl.ast.Market
import com.qkt.dsl.ast.OrderTypeAst
import com.qkt.dsl.ast.TifAst
import com.qkt.execution.OrderRequest
import com.qkt.execution.withExpiresAt

/**
 * Compiles the `TIF GTD <until>` deadline of an order action, or returns null when the action
 * has no GTD. GTD on a MARKET order is rejected: it fills instantly and has nothing to expire.
 */
internal fun compileGtdDeadline(
    tif: TifAst?,
    orderType: OrderTypeAst,
    exprCompiler: ExprCompiler,
): CompiledExpr? {
    // Phase 38: compile the GTD deadline (if any) and reject GTD on Market actions.
    val gtdDeadlineExpr: CompiledExpr? =
        (tif as? com.qkt.dsl.ast.Gtd)?.let { exprCompiler.compile(it.until) }
    if (gtdDeadlineExpr != null && orderType === Market) {
        error(
            "TIF GTD is only valid on pending order types (LIMIT/STOP/IFTOUCHED/...); " +
                "MARKET orders fill instantly and have no expiry semantic.",
        )
    }
    return gtdDeadlineExpr
}

/**
 * Phase 38: evaluates the GTD deadline and stamps expiresAt on [request], propagating into
 * nested sub-requests for composite shapes (Bracket.entry, StandaloneOCO.leg1/leg2,
 * OTO.parent/children, ScaleOut.basis).
 */
internal fun stampGtdDeadline(
    request: OrderRequest,
    gtdDeadlineExpr: CompiledExpr,
    ctx: EvalContext,
): OrderRequest {
    val deadline =
        when (val r = gtdDeadlineExpr.evaluate(ctx)) {
            is Value.Num -> r.v.toLong()
            else ->
                error(
                    "TIF GTD expression evaluated to $r; expected a numeric epoch-millis timestamp",
                )
        }
    return request.withExpiresAt(deadline)
}
