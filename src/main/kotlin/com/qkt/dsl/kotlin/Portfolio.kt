package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.PortfolioRule
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenRun

@QktDsl
class PortfolioBuilder internal constructor(
    private val name: String,
    private val version: Int,
) {
    private val streams: MutableList<StreamDecl> = mutableListOf()
    private val imports: MutableList<ImportClause> = mutableListOf()
    private val rules: MutableList<PortfolioRule> = mutableListOf()

    fun stream(
        alias: String,
        broker: String,
        symbol: String,
        every: String,
    ): StreamRef {
        streams.add(StreamDecl(alias = alias, broker = broker, symbol = symbol, timeframe = every))
        return StreamRef(alias = alias)
    }

    fun import(
        path: String,
        alias: String,
        hold: Boolean = false,
    ) {
        imports.add(ImportClause(path = path, alias = alias, hold = hold))
    }

    fun rules(block: PortfolioRulesBuilder.() -> Unit) {
        val rb = PortfolioRulesBuilder()
        rb.block()
        rules.addAll(rb.build())
    }

    internal fun build(): PortfolioAst =
        PortfolioAst(
            name = name,
            version = version,
            streams = streams.toList(),
            imports = imports.toList(),
            rules = rules.toList(),
        )
}

@QktDsl
class PortfolioRulesBuilder internal constructor() {
    private val rules: MutableList<PortfolioRule> = mutableListOf()

    fun whenRun(
        cond: ExprAst,
        child: String,
    ) {
        rules.add(WhenRun(cond, child))
    }

    fun run(child: String) {
        rules.add(AlwaysRun(child))
    }

    internal fun build(): List<PortfolioRule> = rules.toList()
}

fun portfolio(
    name: String,
    version: Int,
    block: PortfolioBuilder.() -> Unit,
): PortfolioAst {
    val b = PortfolioBuilder(name, version)
    b.block()
    return b.build()
}
