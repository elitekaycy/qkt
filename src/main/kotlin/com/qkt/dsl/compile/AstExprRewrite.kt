package com.qkt.dsl.compile

import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen

/**
 * [ast] with every expression a strategy evaluates (LETs, rules, schedules, sequence stages, defaults)
 * rewritten by [transform]; declarations are left as they are. The one walk the field expansions share, so a
 * section added to the AST is rewritten by all of them or by none.
 */
internal fun rewriteExprs(
    ast: StrategyAst,
    transform: ExprTransform,
): StrategyAst =
    ast.copy(
        lets = ast.lets.map { it.copy(expr = transform.expr(it.expr)) },
        rules =
            ast.rules.map { rule ->
                when (rule) {
                    is WhenThen ->
                        WhenThen(
                            cond = transform.expr(rule.cond),
                            action = transform.action(rule.action),
                            line = rule.line,
                        )
                }
            },
        schedules = ast.schedules.map { it.copy(action = transform.action(it.action)) },
        sequences =
            ast.sequences.map { sequence ->
                sequence.copy(
                    stages =
                        sequence.stages.map { stage ->
                            stage.copy(condition = transform.expr(stage.condition))
                        },
                )
            },
        defaults = ast.defaults?.let { transform.defaultsBlock(it) },
    )
