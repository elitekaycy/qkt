package com.qkt.dsl.portfolio

import com.qkt.dsl.ast.PortfolioAllocationMethod
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.RegimeConditionalState
import com.qkt.dsl.ast.RegimeDefaultState
import com.qkt.dsl.ast.RegimeState
import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.Value
import java.math.BigDecimal

/**
 * A portfolio's `REGIMES` block compiled for the [PortfolioGate]: picks the first regime whose
 * condition holds (else the default) and, under `REGIME_WEIGHTED` allocation, the weight each
 * imported child gets in that regime.
 */
internal class PortfolioRegimes(
    private val ast: PortfolioAst,
    compiler: ExprCompiler,
) {
    private val regimeStates: List<Pair<RegimeState, CompiledExpr?>> =
        ast.regimes?.states.orEmpty().map { state ->
            val compiled =
                when (state) {
                    is RegimeConditionalState -> compiler.compile(state.cond, ruleAlias = null)
                    is RegimeDefaultState -> null
                }
            state to compiled
        } ?: emptyList()

    fun evaluate(ctx: EvalContext): Pair<String?, Map<String, BigDecimal>> {
        val allocate = ast.allocate ?: return null to emptyMap()
        val selected =
            regimeStates
                .firstOrNull { (state, compiled) ->
                    when (state) {
                        is RegimeDefaultState -> false
                        is RegimeConditionalState -> (compiled?.evaluate(ctx) as? Value.Bool)?.v == true
                    }
                }?.first ?: regimeStates.firstOrNull { it.first is RegimeDefaultState }?.first
        val name = selected?.name
        val entries = name?.let { allocate.entries[it] } ?: emptyMap()
        val weights =
            if (allocate.method == PortfolioAllocationMethod.REGIME_WEIGHTED) {
                val nonCash = entries.filterKeys { !it.equals("cash", ignoreCase = true) }
                ast.imports.associate { it.alias to (nonCash[it.alias] ?: BigDecimal.ZERO) }
            } else {
                emptyMap()
            }
        return name to weights
    }
}
