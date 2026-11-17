package com.qkt.dsl.sets

import com.qkt.dsl.ast.ForEachSet
import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst

/** The compiler's guard: every set must have been expanded into its members before a strategy compiles. */
object SymbolSetChecks {
    /** Fails naming the first filtered set still unresolved in [ast]; a picked list never reaches here unresolved. */
    fun requireExpanded(ast: StrategyAst) {
        val alias = firstUnresolved(ast) ?: return
        error(
            "symbol set '$alias' is a filtered universe, which only a catalog can resolve: run it as a backtest " +
                "(members come from instruments.yaml), deploy it on a gateway or MT5 account (members come from " +
                "the venue's listing at start) or list its members with qkt bot universe; " +
                "a picked list (VENUE:{A, B}) runs anywhere",
        )
    }

    /**
     * The message refusing a portfolio child's first filtered set, or null when [ast] has none: a portfolio does not
     * resolve its children's sets, in a backtest or live, so a child accepts picked lists only, for now.
     */
    fun portfolioChildRefusal(ast: StrategyAst): String? =
        firstUnresolved(ast)?.let { alias ->
            "symbol set '$alias' is a filtered universe, and portfolio children accept picked lists only, for now: " +
                "list its members (VENUE:{A, B}), or run the strategy on its own"
        }

    private fun firstUnresolved(ast: StrategyAst): String? {
        val unresolved =
            ast.streams.firstOrNull { it.broker == SET_BROKER }
                ?: ast.rules.filterIsInstance<ForEachSet>().firstOrNull()
                ?: return null
        return (unresolved as? com.qkt.dsl.ast.StreamDecl)?.alias ?: (unresolved as ForEachSet).set
    }
}
