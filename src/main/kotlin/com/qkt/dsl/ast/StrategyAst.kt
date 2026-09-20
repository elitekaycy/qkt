package com.qkt.dsl.ast

import java.math.BigDecimal

/**
 * Root of a parsed `.qkt` strategy: its name and version, the streams, series, baskets and
 * sync groups it reads, its constants, LETs and PARAMs, the DEFAULTS block, and the rules,
 * schedules and sequences it runs.
 */
data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val params: List<ParamDecl> = emptyList(),
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
    val syncGroups: List<SyncGroupDecl> = emptyList(),
    val schedules: List<ScheduleDecl> = emptyList(),
    val baskets: List<BasketDecl> = emptyList(),
    val series: List<SeriesDecl> = emptyList(),
    val sequences: List<SequenceDecl> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "StrategyAst.name must not be blank" }
        require(version >= 0) { "StrategyAst.version must be >= 0: $version" }
        val paramNames = params.map { it.name }
        require(paramNames.distinct().size == paramNames.size) { "duplicate PARAM name in: $paramNames" }
        val letNames = lets.map { it.name }.toSet()
        for (n in paramNames) require(n !in letNames) { "PARAM '$n' collides with a LET of the same name" }
    }
}

/** A named numeric constant declared by the strategy. */
data class ConstantDecl(
    val name: String,
    val value: BigDecimal,
) {
    init {
        require(name.isNotBlank()) { "ConstantDecl.name must not be blank" }
    }
}

/** A `LET name = expr` binding, inlined wherever the name is referenced. */
data class LetDecl(
    val name: String,
    val expr: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "LetDecl.name must not be blank" }
    }
}

/** A `PARAM name = literal` tunable, overridable per run and substituted before compile. */
data class ParamDecl(
    val name: String,
    val value: ExprAst,
) {
    init {
        require(name.isNotBlank()) { "ParamDecl.name must not be blank" }
        require(value is NumLit || value is BoolLit || value is StringLit) {
            "PARAM '$name' must be a literal value (number, true/false, or string)"
        }
    }
}
