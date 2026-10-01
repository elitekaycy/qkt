package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.Block
import com.qkt.dsl.ast.OPTIONS_BROKER
import com.qkt.dsl.ast.OcoEntry
import com.qkt.dsl.ast.OpenStructure
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen

/**
 * Validates a strategy's option structures:
 * - a structure's option root must be fed (`<alias> = OPTIONS:<VENUE>.<ROOT> EVERY …`): its legs are
 *   chosen when it fires, and only a fed root routes and prices every contract it may pick; an unfed
 *   root's legs would reach whatever broker serves unrouted symbols;
 * - a structure alias names no stream or basket, so `POSITION.<alias>` and `CLOSE <alias>` mean one thing;
 * - no rule opens one alias twice: an alias holds one live structure at a time.
 */
internal fun requireValidStructures(ast: StrategyAst) {
    val fed =
        ast.streams
            .filter { it.broker == OPTIONS_BROKER }
            .map { it.symbol.replaceFirst('.', ':') }
            .toSet()
    val named = ast.streams.map { it.alias }.toSet() + ast.baskets.map { it.alias }
    for (opens in actionsOf(ast).map(::structureOpens)) {
        for (open in opens) {
            if (open.root !in fed) {
                throw CompileError(
                    "structure ${open.alias} trades ${open.root}, which is not fed; add `chain = OPTIONS:" +
                        "${open.root.replaceFirst(':', '.')} EVERY <window>` to SYMBOLS",
                )
            }
            if (open.alias in named) throw CompileError("structure ${open.alias} has the name of a stream or basket")
        }
        opens.groupBy { it.alias }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw CompileError("one rule opens structure $it twice")
        }
    }
}

/** The aliases the strategy's `OPEN … = OPTIONS ON …` actions open. */
internal fun structureAliases(ast: StrategyAst): Set<String> =
    actionsOf(ast).flatMap(::structureOpens).map { it.alias }.toSet()

private fun actionsOf(ast: StrategyAst): List<ActionAst> =
    ast.rules.filterIsInstance<WhenThen>().map { it.action } + ast.schedules.map { it.action }

private fun structureOpens(action: ActionAst): List<OpenStructure> =
    when (action) {
        is OpenStructure -> listOf(action)
        is Block -> action.actions.flatMap(::structureOpens)
        is OcoEntry -> structureOpens(action.leg1) + structureOpens(action.leg2)
        else -> emptyList()
    }
