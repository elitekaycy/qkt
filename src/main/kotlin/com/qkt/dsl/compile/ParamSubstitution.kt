package com.qkt.dsl.compile

import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen

/**
 * Replaces every `PARAM` reference in a strategy with the param's literal default,
 * across conditions, LET right-hand sides, schedules, and rule actions (including the
 * expressions buried in SIZING, brackets, order types, TIF, OCO, and stack clauses).
 *
 * Runs before the compiler so the rest of compilation never sees a `PARAM` — a bare
 * `Ref("riskPct")` becomes the `NumLit` it was declared as. e.g. given
 * `PARAM riskPct = 0.01`, a `SIZING RISK $ (ACCOUNT.equity * riskPct)` is rewritten to
 * `... * 0.01` before [AstCompiler] runs.
 *
 * A strategy with no `PARAM` declarations is returned unchanged.
 */
object ParamSubstitution {
    fun apply(ast: StrategyAst): StrategyAst {
        val values: Map<String, ExprAst> = ast.params.associate { it.name to it.value }
        if (values.isEmpty()) return ast
        val t = ExprTransform { ref -> values[ref.name] ?: ref }
        return ast.copy(
            params = emptyList(),
            lets = ast.lets.map { it.copy(expr = t.expr(it.expr)) },
            rules =
                ast.rules.map { rule ->
                    when (rule) {
                        is WhenThen -> WhenThen(cond = t.expr(rule.cond), action = t.action(rule.action))
                    }
                },
            schedules = ast.schedules.map { it.copy(action = t.action(it.action)) },
            defaults = ast.defaults?.let { t.defaultsBlock(it) },
        )
    }
}
