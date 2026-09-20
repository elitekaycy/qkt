package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal

/**
 * Compiles the numeric operands of an engine-managed stop (armed trail, stepped stop, time
 * tighten) for [ChildPriceResolver]: a static spec when every operand is a literal, otherwise a
 * dynamic one evaluated when the order is built.
 */
internal class StopSpecCompiler(
    private val exprCompiler: ExprCompiler,
) {
    /**
     * A stop spec whose numbers come from [operands]. All literals: built and validated now,
     * so a bad literal is still a compile error. Otherwise each operand is evaluated when the
     * order is built, the same moment `BY <expr>` is resolved, and the spec is fixed from then
     * on; an undefined operand (warm-up) or a value the spec rejects (a zero ATR) yields null
     * so the order is skipped instead of throwing inside the rule.
     */
    fun specFrom(
        operands: List<com.qkt.dsl.ast.ExprAst>,
        labels: List<String>,
        allowExpressions: Boolean,
        build: (List<BigDecimal>) -> StopLossSpec,
    ): CompiledStopLoss {
        if (operands.all { it is NumLit }) {
            return CompiledStopLoss.Static(build(operands.map { (it as NumLit).value }))
        }
        if (!allowExpressions) {
            val index = operands.indexOfFirst { it !is NumLit }
            error(
                "${labels[index]} must be a numeric literal in a STACK bracket; got ${operands[index]::class.simpleName}",
            )
        }
        val compiled = operands.map { exprCompiler.compile(it) }
        return CompiledStopLoss.Dynamic { ec, _, _ ->
            val values = compiled.map { it.evaluateNumber(ec) ?: return@Dynamic null }
            try {
                build(values)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun CompiledExpr.evaluateNumber(ec: EvalContext): BigDecimal? = (evaluate(ec) as? Value.Num)?.v
}
