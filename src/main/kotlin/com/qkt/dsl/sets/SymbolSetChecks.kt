package com.qkt.dsl.sets

import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst

/** The compiler's guard: every set must have been expanded into its members before a strategy compiles. */
object SymbolSetChecks {
    /** Fails naming the first filtered set still unresolved in [ast]; a picked list never reaches here unresolved. */
    fun requireExpanded(ast: StrategyAst) {
        val unresolved =
            ast.streams.firstOrNull { it.broker == SET_BROKER }
                ?: ast.rules.filterIsInstance<ForEachSet>().firstOrNull()
        if (unresolved == null) return
        val alias = (unresolved as? com.qkt.dsl.ast.StreamDecl)?.alias ?: (unresolved as ForEachSet).set
        error(
            "symbol set '$alias' is a filtered universe, which only a catalog can resolve: run it as a backtest " +
                "(members come from instruments.yaml), deploy it on a gateway or MT5 account (members come from " +
                "the venue's listing at start) or list its members with qkt bot universe; " +
                "a picked list (VENUE:{A, B}) runs anywhere",
        )
    }
}
