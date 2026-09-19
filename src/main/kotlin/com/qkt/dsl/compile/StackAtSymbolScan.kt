package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Sell

/**
 * Symbols an action places `STACK_AT` entries on. Those symbols hold several positions at once,
 * so the runtime tracks them per position rather than netting them.
 */
internal fun collectStackAtSymbols(
    action: ActionAst,
    streams: Map<String, HubKey>,
): Set<String> {
    val out = mutableSetOf<String>()

    fun walk(a: ActionAst) {
        when (a) {
            is Buy ->
                if (a.opts.stackAts.isNotEmpty()) {
                    streams[a.stream]?.qktSymbol?.let { out.add(it) }
                }
            is Sell ->
                if (a.opts.stackAts.isNotEmpty()) {
                    streams[a.stream]?.qktSymbol?.let { out.add(it) }
                }
            is Block -> a.actions.forEach { walk(it) }
            is OcoEntry -> {
                walk(a.leg1)
                walk(a.leg2)
            }
            else -> {} // other actions don't carry stackAts
        }
    }
    walk(action)
    return out
}
