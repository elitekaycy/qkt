package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.OPTIONS_BROKER
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen

/**
 * Refuses a structure whose option root is not fed (`<alias> = OPTIONS:<VENUE>.<ROOT> EVERY …`): its
 * legs are chosen when it fires, and only a fed root routes and prices every contract it may pick; an
 * unfed root's legs would reach whatever broker serves unrouted symbols.
 */
internal fun requireFedStructureRoots(ast: StrategyAst) {
    val fed =
        ast.streams
            .filter { it.broker == OPTIONS_BROKER }
            .map { it.symbol.replaceFirst('.', ':') }
            .toSet()

    fun walk(action: ActionAst) {
        when (action) {
            is OpenStructure ->
                if (action.root !in fed) {
                    throw CompileError(
                        "structure ${action.alias} trades ${action.root}, which is not fed; add `chain = OPTIONS:" +
                            "${action.root.replaceFirst(':', '.')} EVERY <window>` to SYMBOLS",
                    )
                }
            is Block -> action.actions.forEach(::walk)
            is OcoEntry -> {
                walk(action.leg1)
                walk(action.leg2)
            }
            else -> Unit
        }
    }
    ast.rules.filterIsInstance<WhenThen>().forEach { walk(it.action) }
    ast.schedules.forEach { walk(it.action) }
}
