package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst

/**
 * Actions the generic CFD action-parity harness cannot drive: an option structure selects its contracts
 * from a stored option chain and has no live venue before phase 44. It is covered end to end on real
 * chain data by `StructureBacktestTest`.
 */
internal val CHAIN_ACTIONS = setOf("OpenStructure")

/** Every action the action-parity harness must cover: all of them but [CHAIN_ACTIONS]. */
internal val PARITY_ACTIONS: List<String> =
    ActionAst::class.java.permittedSubclasses.map { it.simpleName } - CHAIN_ACTIONS
