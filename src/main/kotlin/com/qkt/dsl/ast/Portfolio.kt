package com.qkt.dsl.ast

import java.math.BigDecimal

data class PortfolioAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val imports: List<ImportClause>,
    val rules: List<PortfolioRule>,
    val capital: BigDecimal? = null,
) {
    init {
        require(name.isNotBlank()) { "PortfolioAst.name must not be blank" }
        require(version >= 0) { "PortfolioAst.version must be >= 0: $version" }
        require(imports.isNotEmpty()) { "PORTFOLIO must have at least one IMPORT" }
        val aliases = imports.map { it.alias }
        require(aliases.distinct().size == aliases.size) {
            "PORTFOLIO aliases must be unique: $aliases"
        }
        val knownAliases = aliases.toSet()
        for (rule in rules) {
            val refAlias =
                when (rule) {
                    is WhenRun -> rule.alias
                    is AlwaysRun -> rule.alias
                }
            require(refAlias in knownAliases) {
                "PORTFOLIO rule references unknown alias '$refAlias'"
            }
        }
        val weights =
            rules.map { rule ->
                when (rule) {
                    is WhenRun -> rule.weight
                    is AlwaysRun -> rule.weight
                }
            }
        if (weights.any { it != null }) {
            require(weights.all { it != null }) {
                "PORTFOLIO: WEIGHT is all-or-none — every RUN must carry WEIGHT or none may"
            }
            require(capital != null) {
                "PORTFOLIO: CAPITAL is required on the header when any RUN carries WEIGHT"
            }
            for (w in weights.filterNotNull()) {
                require(w > BigDecimal.ZERO && w <= BigDecimal.ONE) {
                    "PORTFOLIO: each WEIGHT must be in (0, 1], got $w"
                }
            }
            val sum = weights.filterNotNull().fold(BigDecimal.ZERO) { acc, w -> acc.add(w) }
            require(sum <= BigDecimal.ONE) {
                "PORTFOLIO: total WEIGHT must sum to <= 1.0 (no implicit leverage), got $sum"
            }
        } else {
            require(capital == null) {
                "PORTFOLIO: CAPITAL declared but no RUN carries WEIGHT — nothing to allocate"
            }
        }
    }
}

data class ImportClause(
    val path: String,
    val alias: String,
    val hold: Boolean = false,
) {
    init {
        require(path.isNotBlank()) { "ImportClause.path must not be blank" }
        require(alias.isNotBlank()) { "ImportClause.alias must not be blank" }
    }
}

sealed interface PortfolioRule

data class WhenRun(
    val cond: ExprAst,
    val alias: String,
    val weight: BigDecimal? = null,
    val overrides: Map<String, ExprAst> = emptyMap(),
) : PortfolioRule

data class AlwaysRun(
    val alias: String,
    val weight: BigDecimal? = null,
    val overrides: Map<String, ExprAst> = emptyMap(),
) : PortfolioRule
