package com.qkt.execution

import com.qkt.dsl.ast.ChildAt

/**
 * True when this bracket's take profit is a relative expression (`BY`, `PCT`, `RR`) re-resolved from
 * the entry's fill. Its pre-fill value is only a placeholder, so an attach venue does not receive it
 * with the entry — the gateway would validate it against the live quote at execution, which a fast
 * market moves past a small distance — and the target is attached by position modify at fill.
 */
val OrderRequest.Bracket.hasFillAnchoredTarget: Boolean
    get() = takeProfitAst != null && takeProfitAst !is ChildAt
