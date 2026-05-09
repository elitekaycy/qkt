package com.qkt.dsl.ast

data class PortfolioAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val imports: List<ImportClause>,
    val rules: List<PortfolioRule>,
) {
    init {
        require(name.isNotBlank()) { "PortfolioAst.name must not be blank" }
        require(version >= 0) { "PortfolioAst.version must be >= 0: $version" }
        require(imports.isNotEmpty()) { "PORTFOLIO must have at least one IMPORT" }
        val aliases = imports.map { it.alias }
        require(aliases.distinct().size == aliases.size) {
            "PORTFOLIO aliases must be unique: $aliases"
        }
        val paths = imports.map { it.path }
        require(paths.distinct().size == paths.size) {
            "PORTFOLIO import paths must be unique (no overrides in v1): $paths"
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
) : PortfolioRule

data class AlwaysRun(
    val alias: String,
) : PortfolioRule
