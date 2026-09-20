package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.Resize
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen

/** Rejects `RESIZE` on a stream whose positions any action protects with a `BRACKET`. */
internal fun validateResizeProtection(ast: StrategyAst) {
    val resizedStreams = mutableSetOf<String>()
    val bracketedStreams = mutableSetOf<String>()

    fun walk(action: ActionAst) {
        when (action) {
            is Resize -> resizedStreams.add(action.stream)
            is Buy -> {
                if (action.opts.bracket != null) bracketedStreams.add(action.stream)
                action.opts.onFill.forEach(::walk)
                action.opts.exitHooks.onStop
                    .forEach(::walk)
                action.opts.exitHooks.onTakeProfit
                    .forEach(::walk)
                action.opts.exitHooks.onClose
                    .forEach(::walk)
            }
            is Sell -> {
                if (action.opts.bracket != null) bracketedStreams.add(action.stream)
                action.opts.onFill.forEach(::walk)
                action.opts.exitHooks.onStop
                    .forEach(::walk)
                action.opts.exitHooks.onTakeProfit
                    .forEach(::walk)
                action.opts.exitHooks.onClose
                    .forEach(::walk)
            }
            is Latch ->
                action.entries
                    .filter { it.bracket != null }
                    .forEach { bracketedStreams.add(it.stream ?: action.stream) }
            is Block -> action.actions.forEach(::walk)
            is OcoEntry -> {
                walk(action.leg1)
                walk(action.leg2)
            }
            else -> Unit
        }
    }

    ast.rules
        .filterIsInstance<WhenThen>()
        .map { mergeDefaults(it.action, ast.defaults) }
        .forEach(::walk)
    ast.schedules
        .map { mergeDefaults(it.action, ast.defaults) }
        .forEach(::walk)

    val unsafe = resizedStreams.intersect(bracketedStreams)
    require(unsafe.isEmpty()) {
        "RESIZE cannot target bracket-managed positions; protective child quantities " +
            "would not track a resized parent. Remove RESIZE or BRACKET for: ${unsafe.sorted().joinToString()}"
    }
}
