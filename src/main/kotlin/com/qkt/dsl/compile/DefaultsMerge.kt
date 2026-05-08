package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.Sell

fun mergeDefaults(
    action: ActionAst,
    defaults: DefaultsBlock?,
): ActionAst {
    if (defaults == null) return action
    return when (action) {
        is Buy -> Buy(action.stream, mergeOpts(action.opts, defaults))
        is Sell -> Sell(action.stream, mergeOpts(action.opts, defaults))
        else -> action
    }
}

private fun mergeOpts(
    opts: ActionOpts,
    d: DefaultsBlock,
): ActionOpts =
    ActionOpts(
        sizing = opts.sizing ?: d.sizing,
        orderType = opts.orderType ?: d.orderType ?: d.trailing,
        tif = opts.tif ?: d.tif,
        bracket = mergeBracket(opts.bracket, d.stopLoss, d.takeProfit),
        oco = opts.oco,
    )

private fun mergeBracket(
    actionBracket: BracketAst?,
    defSL: ChildPriceAst?,
    defTP: ChildPriceAst?,
): BracketAst? {
    if (actionBracket == null) {
        if (defSL != null && defTP != null) return BracketAst(defSL, defTP)
        return null
    }
    return BracketAst(actionBracket.stopLoss ?: defSL, actionBracket.takeProfit ?: defTP)
}
