package com.qkt.dsl.portfolio

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
                    val compiled = AstCompiler().compile(childAst)
                    val childStrategyId = "${ast.name}:${imp.alias}"
                    CompiledChild(
                        alias = imp.alias,
                        hold = imp.hold,
                        strategyId = childStrategyId,
                        compiled = compiled,
                        streams = childAst.streams.map { it.alias },
                        symbols = childAst.streams.map { it.symbol }.distinct(),
                        ast = childAst,
                    )
                }
            return PortfolioCompiled(ast, children)
        } finally {
            visiting.remove(path)
        }
    }
}
