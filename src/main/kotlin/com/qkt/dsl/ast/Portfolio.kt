package com.qkt.dsl.ast

import java.math.BigDecimal

data class PortfolioAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val imports: List<ImportClause>,
    val rules: List<PortfolioRule>,
    val capital: BigDecimal? = null,
    val regimes: RegimeBlock? = null,
    val allocate: AllocateBlock? = null,
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
        if (allocate != null) {
            require(capital != null) {
                "PORTFOLIO: CAPITAL is required when ALLOCATE is declared"
            }
            require(weights.all { it == null }) {
                "PORTFOLIO: ALLOCATE and per-RUN WEIGHT are mutually exclusive"
            }
            validateAllocateBlock(knownAliases)
        } else if (weights.any { it != null }) {
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
            require(capital == null || regimes != null) {
                "PORTFOLIO: CAPITAL declared but no RUN carries WEIGHT and no ALLOCATE block — nothing to allocate"
            }
        }
    }

    private fun validateAllocateBlock(knownAliases: Set<String>) {
        val allocateBlock = allocate ?: return
        if (allocateBlock.method == PortfolioAllocationMethod.REGIME_WEIGHTED) {
            val regimeBlock =
                requireNotNull(regimes) {
                    "PORTFOLIO: REGIME_WEIGHTED requires a REGIMES block"
                }
            val stateNames = regimeBlock.states.map { it.name }
            require(stateNames.distinct().size == stateNames.size) {
                "PORTFOLIO: regime state names must be unique: $stateNames"
            }
            val defaultCount = regimeBlock.states.count { it is RegimeDefaultState }
            require(defaultCount == 1) {
                "PORTFOLIO: REGIME_WEIGHTED requires exactly one DEFAULT state, got $defaultCount"
            }
        }
        for ((regimeName, entries) in allocateBlock.entries) {
            val sum = entries.values.fold(BigDecimal.ZERO) { acc, w -> acc.add(w) }
            require(sum <= BigDecimal.ONE) {
                "PORTFOLIO: regime '$regimeName' weights sum to $sum, must be <= 1.0"
            }
            for (alias in entries.keys) {
                if (alias.equals("cash", ignoreCase = true)) continue
                require(alias in knownAliases) {
                    "PORTFOLIO: regime '$regimeName' references unknown alias '$alias'"
                }
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

/** Allocation method declared in a portfolio `ALLOCATE` block. */
enum class PortfolioAllocationMethod { REGIME_WEIGHTED, }

/** A named regime detector: a list of mutually-exclusive states, one of which is the fallback. */
data class RegimeBlock(
    val name: String,
    val states: List<RegimeState>,
) {
    init {
        require(name.isNotBlank()) { "RegimeBlock.name must not be blank" }
        require(states.isNotEmpty()) { "RegimeBlock '$name' must declare at least one STATE" }
    }
}

/** One state inside a [RegimeBlock]. */
sealed interface RegimeState {
    val name: String
}

/** A state that is selected when its `WHEN` expression evaluates to true. */
data class RegimeConditionalState(
    override val name: String,
    val cond: ExprAst,
) : RegimeState {
    init {
        require(name.isNotBlank()) { "RegimeState.name must not be blank" }
    }
}

/** The fallback state used when no conditional state matches. */
data class RegimeDefaultState(
    override val name: String,
) : RegimeState {
    init {
        require(name.isNotBlank()) { "RegimeState.name must not be blank" }
    }
}

/**
 * Capital allocation instructions declared in a portfolio `ALLOCATE` block.
 *
 * @property method how to translate the current regime into per-child scaling
 * @property rebalanceEveryBars optional periodic rebalance cadence in bars (ignored for [REGIME_WEIGHTED])
 * @property entries regime name -> child alias -> target fraction of book capital;
 *   the special alias `cash` absorbs unallocated capital
 */
data class AllocateBlock(
    val method: PortfolioAllocationMethod,
    val rebalanceEveryDurationMs: Long? = null,
    val entries: Map<String, Map<String, BigDecimal>>,
) {
    init {
        require(rebalanceEveryDurationMs == null || rebalanceEveryDurationMs > 0) {
            "AllocateBlock.rebalanceEveryDurationMs must be > 0"
        }
    }
}
