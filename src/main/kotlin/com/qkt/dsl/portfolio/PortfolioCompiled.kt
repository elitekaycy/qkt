package com.qkt.dsl.portfolio

import com.qkt.dsl.StrategySource
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.DslCompiledStrategy

data class PortfolioCompiled(
    val ast: PortfolioAst,
    val children: List<CompiledChild>,
)

/** One imported child of a portfolio, compiled with its `OVERRIDE`s; [source] is the child's file as read. */
data class CompiledChild(
    val alias: String,
    val hold: Boolean,
    val strategyId: String,
    val compiled: DslCompiledStrategy,
    val streams: List<String>,
    val symbols: List<String>,
    val ast: StrategyAst,
    val source: StrategySource,
)
