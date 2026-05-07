package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.ConstantDecl
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.RuleAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.StrategyAst

@DslMarker
annotation class QktDsl

@QktDsl
class StrategyBuilder(
    private val name: String,
    private val version: Int,
) {
    private val streams: MutableList<StreamDecl> = mutableListOf()
    private val constants: MutableList<ConstantDecl> = mutableListOf()
    internal val lets: MutableList<LetDecl> = mutableListOf()
    private val rules: MutableList<RuleAst> = mutableListOf()

    fun stream(
        alias: String,
        broker: String,
        symbol: String,
        every: String,
    ): StreamRef {
        streams.add(StreamDecl(alias = alias, broker = broker, symbol = symbol, timeframe = every))
        return StreamRef(alias = alias)
    }

    internal fun addRule(rule: RuleAst) {
        rules.add(rule)
    }

    internal fun build(): StrategyAst =
        StrategyAst(
            name = name,
            version = version,
            streams = streams.toList(),
            constants = constants.toList(),
            lets = lets.toList(),
            defaults = null,
            rules = rules.toList(),
        )
}

fun strategy(
    name: String,
    version: Int,
    block: StrategyBuilder.() -> Unit,
): StrategyAst {
    val b = StrategyBuilder(name = name, version = version)
    b.block()
    return b.build()
}
