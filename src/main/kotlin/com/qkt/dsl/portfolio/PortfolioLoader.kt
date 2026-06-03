package com.qkt.dsl.portfolio

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.WhenRun
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import java.nio.file.Files
import java.nio.file.Path

object PortfolioLoader {
    fun load(path: Path): PortfolioCompiled {
        val canonical = path.toAbsolutePath().normalize()
        val visiting = mutableSetOf<Path>()
        return loadPortfolio(canonical, visiting)
    }

    private fun loadPortfolio(
        path: Path,
        visiting: MutableSet<Path>,
    ): PortfolioCompiled {
        if (path in visiting) {
            error("cycle in portfolio import graph at ${visiting.joinToString(" -> ")} -> $path")
        }
        visiting.add(path)
        try {
            val text = Files.readString(path)
            val parsed = Parser(Lexer(text).tokenize()).parseFile()
            val ast =
                when (parsed) {
                    is ParseResult.Success -> {
                        when (val pf = parsed.value) {
                            is ParsedFile.PortfolioFile -> pf.ast
                            is ParsedFile.StrategyFile ->
                                error("expected PORTFOLIO at $path, got STRATEGY")
                        }
                    }
                    is ParseResult.Failure ->
                        error("parse error at $path: ${parsed.errors.joinToString { it.message }}")
                }

            val overridesByAlias: Map<String, Map<String, ExprAst>> =
                ast.rules
                    .mapNotNull { rule ->
                        val (alias, ov) =
                            when (rule) {
                                is WhenRun -> rule.alias to rule.overrides
                                is AlwaysRun -> rule.alias to rule.overrides
                            }
                        if (ov.isEmpty()) null else alias to ov
                    }.groupBy({ it.first }, { it.second })
                    .mapValues { (alias, list) ->
                        val distinct = list.distinct()
                        if (distinct.size > 1) error("conflicting OVERRIDE for alias '$alias'")
                        distinct.first()
                    }

            val children =
                ast.imports.map { imp ->
                    val childPath =
                        path.parent
                            .resolve(imp.path)
                            .toAbsolutePath()
                            .normalize()
                    if (childPath in visiting) {
                        error("cycle in portfolio import graph: $childPath imported by $path")
                    }
                    val childText = Files.readString(childPath)
                    val childParsed = Parser(Lexer(childText).tokenize()).parseFile()
                    val childAst =
                        when (childParsed) {
                            is ParseResult.Success -> {
                                when (val cf = childParsed.value) {
                                    is ParsedFile.StrategyFile -> cf.ast
                                    is ParsedFile.PortfolioFile ->
                                        error("nested PORTFOLIO not supported in v1: $childPath")
                                }
                            }
                            is ParseResult.Failure ->
                                error("parse error at $childPath: ${childParsed.errors.joinToString { it.message }}")
                        }
                    val overrides = overridesByAlias[imp.alias].orEmpty()
                    val declared = childAst.params.associateBy { it.name }
                    for ((key, value) in overrides) {
                        val decl = declared[key] ?: error("OVERRIDE: child '${imp.alias}' has no PARAM '$key'")
                        if (value::class != decl.value::class) {
                            error(
                                "OVERRIDE: PARAM '$key' of '${imp.alias}' is " +
                                    "${decl.value::class.simpleName}, got ${value::class.simpleName}",
                            )
                        }
                    }
                    val effectiveAst =
                        if (overrides.isEmpty()) {
                            childAst
                        } else {
                            childAst.copy(
                                params =
                                    childAst.params.map { p ->
                                        overrides[p.name]?.let { p.copy(value = it) } ?: p
                                    },
                            )
                        }
                    val compiled = AstCompiler().compile(effectiveAst)
                    val childStrategyId = "${ast.name}:${imp.alias}"
                    CompiledChild(
                        alias = imp.alias,
                        hold = imp.hold,
                        strategyId = childStrategyId,
                        compiled = compiled,
                        streams = effectiveAst.streams.map { it.alias },
                        symbols = effectiveAst.streams.map { it.symbol }.distinct(),
                        ast = effectiveAst,
                    )
                }
            return PortfolioCompiled(ast, children)
        } finally {
            visiting.remove(path)
        }
    }
}
