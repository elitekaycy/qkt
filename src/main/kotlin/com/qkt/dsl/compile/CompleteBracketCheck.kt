package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen

/** Rejects a `BRACKET` missing its stop loss or take profit once `DEFAULTS` are merged in. */
internal fun validateCompleteBrackets(ast: StrategyAst) {
    ast.rules.filterIsInstance<WhenThen>().forEach { validateCompleteBracket(it.action, ast.defaults) }
    ast.schedules.forEach { validateCompleteBracket(it.action, ast.defaults) }
}

/** The per-action form of [validateCompleteBrackets], so a failure can be tagged with its rule. */
internal fun validateCompleteBracket(
    action: ActionAst,
    defaults: DefaultsBlock?,
) {
    fun validate(bracket: com.qkt.dsl.ast.BracketAst?) {
        if (bracket == null || (bracket.stopLoss != null && bracket.takeProfit != null)) return
        val missing =
            if (bracket.stopLoss == null) {
                "STOP LOSS"
            } else {
                "TAKE PROFIT"
            }
        error(
            "BRACKET requires both STOP LOSS and TAKE PROFIT; missing $missing after DEFAULTS merge",
        )
    }

    fun validateLatch(bracket: com.qkt.dsl.ast.LatchBracket?) {
        if (bracket == null || (bracket.stopLoss != null && bracket.takeProfit != null)) return
        val missing = if (bracket.stopLoss == null) "STOP LOSS" else "TAKE PROFIT"
        error("BRACKET requires both STOP LOSS and TAKE PROFIT; missing $missing")
    }

    fun walk(action: ActionAst) {
        when (action) {
            is Buy -> {
                validate(action.opts.bracket)
                action.opts.onFill.forEach(::walk)
                action.opts.exitHooks.onStop
                    .forEach(::walk)
                action.opts.exitHooks.onTakeProfit
                    .forEach(::walk)
                action.opts.exitHooks.onClose
                    .forEach(::walk)
            }
            is Sell -> {
                validate(action.opts.bracket)
                action.opts.onFill.forEach(::walk)
                action.opts.exitHooks.onStop
                    .forEach(::walk)
                action.opts.exitHooks.onTakeProfit
                    .forEach(::walk)
                action.opts.exitHooks.onClose
                    .forEach(::walk)
            }
            is Latch -> action.entries.forEach { validateLatch(it.bracket) }
            is Block -> action.actions.forEach(::walk)
            is OcoEntry -> {
                walk(action.leg1)
                walk(action.leg2)
            }
            else -> Unit
        }
    }

    walk(mergeDefaults(action, defaults))
}
