package com.qkt.dsl.ast

import java.math.BigDecimal

data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
) {
    init {
        require(name.isNotBlank()) { "StrategyAst.name must not be blank" }
        require(version >= 0) { "StrategyAst.version must be >= 0: $version" }
    }
}

data class StreamDecl(
    val alias: String,
    val broker: String,
    val symbol: String,
    val timeframe: String,
) {
    init {
        require(alias.isNotBlank()) { "StreamDecl.alias must not be blank" }
        require(broker.isNotBlank()) { "StreamDecl.broker must not be blank" }
        require(symbol.isNotBlank()) { "StreamDecl.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "StreamDecl.timeframe must not be blank" }
    }
}

data class ConstantDecl(
    val name: String,
    val value: BigDecimal,
) {
    init {
        require(name.isNotBlank()) { "ConstantDecl.name must not be blank" }
    }
}

data class LetDecl(
    val name: String,
    val expr: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "LetDecl.name must not be blank" }
    }
}
