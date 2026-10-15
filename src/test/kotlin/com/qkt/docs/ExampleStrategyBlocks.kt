package com.qkt.docs

import com.qkt.docs.DslReferenceBlocks.Block
import com.qkt.docs.DslReferenceBlocks.blocksIn
import com.qkt.docs.DslSnippetCompiler.compile
import com.qkt.docs.DslSnippetCompiler.writeStubChildren
import com.qkt.docs.DslSnippetShapes.Shape
import com.qkt.docs.DslSnippetShapes.firstWord
import com.qkt.docs.DslSnippetShapes.shape
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the complete `STRATEGY`/`PORTFOLIO` files out of the example pages under `docs/examples/`
 * and compiles them the way `qkt parse` would. A portfolio's `IMPORT`s resolve to the strategy
 * blocks on the same page whose fence title names that file; an import the page does not show
 * gets a stub child.
 */
internal object ExampleStrategyBlocks {
    data class Example(
        val block: Block,
        val title: String?,
    ) {
        val source: String get() = block.lines.joinToString("\n") + "\n"

        val isPortfolio: Boolean get() = firstWord(block.lines) == "PORTFOLIO"
    }

    fun examplePages(): List<Path> =
        Files.walk(EXAMPLES_DIR).use { paths ->
            paths.filter { it.toString().endsWith(".md") }.sorted().toList()
        }

    /** Every complete strategy or portfolio file on one page, with its fence title. */
    fun examplesIn(page: Path): List<Example> {
        val fences = Files.readAllLines(page)
        return blocksIn(page)
            .filter { it.marker == null && shape(it.lines) == Shape.File }
            .map { block -> Example(block, TITLE.find(fences[block.line - 1])?.groupValues?.get(1)) }
    }

    /** Returns null when the example compiles, otherwise the first error. */
    fun check(
        example: Example,
        siblings: List<Example>,
        tempDir: Path,
    ): String? {
        if (!example.isPortfolio) return compile(example.source, tempDir)
        val dir = Files.createTempDirectory(tempDir, "example")
        writeStubChildren(example.source, dir)
        for (sibling in siblings) {
            val name = sibling.title?.substringAfterLast('/') ?: continue
            if (!sibling.isPortfolio) Files.writeString(dir.resolve(name), sibling.source)
        }
        val path = dir.resolve(example.title?.substringAfterLast('/') ?: "portfolio.qkt")
        Files.writeString(path, example.source)
        val parsed = DslSnippetCompiler.parseAny(example.source)
        if (parsed is ParseResult.Failure) {
            return parsed.errors.first().let { "${it.line}:${it.col} — ${it.message}" }
        }
        return runCatching { PortfolioLoader.load(path) }.exceptionOrNull()?.let { it.message ?: "$it" }
    }

    val EXAMPLES_DIR: Path = Path.of("docs", "examples")
    val TITLE = Regex("title=\"([^\"]+)\"")
}
