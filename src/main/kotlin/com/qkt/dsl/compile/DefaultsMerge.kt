package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.BracketAst
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.ChildPriceAst
import com.qkt.dsl.ast.DefaultsBlock
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.kotlin.SYMBOL_PLACEHOLDER_NAME

fun mergeDefaults(
    action: ActionAst,
    defaults: DefaultsBlock?,
): ActionAst {
    if (defaults == null) return action
    return when (action) {
        is Buy -> Buy(action.stream, mergeOpts(action.opts, defaults, action.stream))
        is Sell -> Sell(action.stream, mergeOpts(action.opts, defaults, action.stream))
        is Block -> Block(action.actions.map { mergeDefaults(it, defaults) })
        is OcoEntry -> OcoEntry(mergeDefaults(action.leg1, defaults), mergeDefaults(action.leg2, defaults))
        else -> action
    }
}

/**
 * Rewrites the `SYMBOL` placeholder — valid only inside a DEFAULTS block — to the field reference
 * of the action's own stream, e.g. `SYMBOL` in `DEFAULTS { STOP_LOSS = BY ATR(SYMBOL, 14) }` becomes
 * `btc.candle` for a `BUY btc`. Non-placeholder refs pass through untouched.
 */
private fun symbolTransform(alias: String): ExprTransform =
    ExprTransform { ref ->
        if (ref.name == SYMBOL_PLACEHOLDER_NAME) StreamFieldRef(alias, "candle") else ref
    }

private fun mergeOpts(
    opts: ActionOpts,
    d: DefaultsBlock,
    alias: String,
): ActionOpts {
    val t = symbolTransform(alias)
    return ActionOpts(
        sizing = opts.sizing ?: d.sizing?.let { t.sizing(it) },
        orderType = opts.orderType ?: (d.orderType ?: d.trailing)?.let { t.orderType(it) },
        tif = opts.tif ?: d.tif?.let { t.tif(it) },
        bracket =
            mergeBracket(
                opts.bracket,
                d.stopLoss?.let { t.childPrice(it) },
                d.takeProfit?.let { t.childPrice(it) },
            ),
        oco = opts.oco,
    )
}

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
