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
    val syncGroups: List<SyncGroupDecl> = emptyList(),
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
    val warmupBars: Int? = null,
) {
    init {
        require(alias.isNotBlank()) { "StreamDecl.alias must not be blank" }
        require(broker.isNotBlank()) { "StreamDecl.broker must not be blank" }
        require(symbol.isNotBlank()) { "StreamDecl.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "StreamDecl.timeframe must not be blank" }
        if (warmupBars != null) require(warmupBars > 0) { "StreamDecl.warmupBars must be > 0 if set: $warmupBars" }
    }

    val qktSymbol: String get() = "$broker:$symbol"
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

/**
 * One declared sync group inside a `SYMBOLS` block. The engine evaluates the
 * strategy once per group-bar-window, with every member's bar in scope atomically.
 *
 * e.g. `SYNCHRONIZE gold silver WITHIN 200ms` parses to
 * `SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = 200)`.
 *
 * See `docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md` (#45).
 */
data class SyncGroupDecl(
    val aliases: List<String>,
    val timeoutMs: Long? = null,
) {
    init {
        require(aliases.size >= 2) {
            "SyncGroupDecl needs at least 2 aliases, got ${aliases.size}"
        }
        require(timeoutMs == null || timeoutMs > 0) {
            "SyncGroupDecl.timeoutMs must be positive when present: $timeoutMs"
        }
        require(aliases.toSet().size == aliases.size) {
            "SyncGroupDecl aliases must be unique: $aliases"
        }
    }
}
