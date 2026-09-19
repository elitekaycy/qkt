package com.qkt.docs

import com.qkt.docs.DslReferenceBlocks.Block
import com.qkt.docs.DslSnippetHarness.RefType
import com.qkt.docs.DslSnippetHarness.assemble
import com.qkt.docs.DslSnippetHarness.detectAliases
import com.qkt.docs.DslSnippetHarness.portfolioHarness
import com.qkt.docs.DslSnippetShapes.Shape
import com.qkt.docs.DslSnippetShapes.shape
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiles a reference block the way `qkt parse` would and reports the first error, retrying with
 * declared aliases and names.
 */
internal object DslSnippetCompiler {
    /** Returns null when the block compiles, otherwise the first error. */
    fun checkBlock(
        block: Block,
        tempDir: Path,
    ): String? {
        val lines = block.lines
        return when (val shape = shape(lines)) {
            Shape.File -> compile(lines.joinToString("\n") + "\n", tempDir)
            Shape.Portfolio -> compile(portfolioHarness(lines), tempDir)
            is Shape.Wrapped -> compileWrapped(shape.sections, lines, tempDir)
        }
    }

    fun compileWrapped(
        sections: Map<String, List<String>>,
        lines: List<String>,
        tempDir: Path,
    ): String? {
        val aliases = detectAliases(lines)
        val refs = linkedMapOf<String, RefType>()
        var error = compile(assemble(sections, aliases, refs), tempDir)
        repeat(8) {
            val current = error ?: return null
            UNKNOWN_ALIAS.find(current)?.groupValues?.get(1)?.takeIf { it !in aliases }?.let {
                aliases += it
                error = compile(assemble(sections, aliases, refs), tempDir)
                return@repeat
            }
            UNKNOWN_REFERENCE.find(current)?.groupValues?.get(1)?.takeIf { it !in refs }?.let {
                refs[it] = RefType.NUMBER
                error = compile(assemble(sections, aliases, refs), tempDir)
                return@repeat
            }
            if (refs.isNotEmpty()) {
                val names = refs.keys.toList()
                for (combination in combinations(names.size)) {
                    val attempt = LinkedHashMap(names.zip(combination).toMap())
                    if (compile(assemble(sections, aliases, attempt), tempDir) == null) return null
                }
            }
            return current
        }
        return error
    }

    fun combinations(size: Int): List<List<RefType>> =
        (0 until size).fold(listOf(emptyList())) { acc, _ ->
            acc.flatMap { prefix ->
                RefType.entries.map { prefix + it }
            }
        }

    fun compile(
        source: String,
        tempDir: Path,
    ): String? =
        when (val parsed = Dsl.parseAny(source)) {
            is ParseResult.Failure -> parsed.errors.first().let { "${it.line}:${it.col} — ${it.message}" }
            is ParseResult.Success ->
                when (val file = parsed.value) {
                    is ParsedFile.StrategyFile ->
                        runCatching { AstCompiler().compile(file.ast) }.exceptionOrNull()?.let { it.message ?: "$it" }
                    is ParsedFile.PortfolioFile -> {
                        val dir = Files.createTempDirectory(tempDir, "portfolio")
                        writeStubChildren(source, dir)
                        val path = dir.resolve("portfolio.qkt")
                        Files.writeString(path, source)
                        runCatching { PortfolioLoader.load(path) }.exceptionOrNull()?.let { it.message ?: "$it" }
                    }
                }
        }

    fun writeStubChildren(
        source: String,
        dir: Path,
    ) {
        val params =
            OVERRIDE
                .findAll(source)
                .flatMap { override -> OVERRIDE_KEY.findAll(override.groupValues[1]).map { it.groupValues[1] } }
                .toSortedSet()
        val paramLines = params.joinToString("") { "PARAM $it = 1\n" }
        for (match in IMPORT_PATH.findAll(source)) {
            val child = dir.resolve(match.groupValues[1])
            child.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                child,
                "STRATEGY stub VERSION 1\nSYMBOLS\n  btc = BACKTEST:BTCUSD EVERY 1h\n$paramLines" +
                    "RULES\n  WHEN btc.close > 0 THEN BUY btc SIZING 0.1\n",
            )
        }
    }

    val IMPORT_PATH = Regex("IMPORT\\s+['\"]([^'\"]+)['\"]")
    val OVERRIDE = Regex("OVERRIDE\\s*\\{([^}]*)}")
    val OVERRIDE_KEY = Regex("(\\w+)\\s*=")
    val UNKNOWN_ALIAS = Regex("Unknown stream alias: (\\w+)")
    val UNKNOWN_REFERENCE = Regex("Unknown reference: (\\w+)")
}
