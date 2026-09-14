package com.qkt.docs

import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir

/**
 * Keeps the DSL reference honest: every ```qkt block in `docs/reference/dsl/` must parse and compile
 * exactly as `qkt parse` would (#1127).
 *
 * A block is checked in the shape it is written. A complete `STRATEGY`/`PORTFOLIO` file is compiled as-is.
 * A snippet is wrapped in the smallest strategy that holds it: stream aliases it mentions are declared,
 * `LET` lines go before `RULES`, a bare action gets a `WHEN ... THEN`, an expression becomes a `LET`,
 * and a portfolio snippet gets stub child files. Names that the surrounding prose defines elsewhere
 * (`signal`, `regime_changed`) are declared as a number, a boolean or a string, whichever compiles.
 *
 * An HTML comment on the line above a fence opts a block out:
 * - `<!-- qkt-doc: grammar -->` a syntax sketch with placeholders such as `<expr>`; not parsed.
 * - `<!-- qkt-doc: illegal -->` an example of an error; it must fail to parse.
 * - `<!-- qkt-doc: skip <reason> -->` a real example that cannot be checked yet; the reason names the issue.
 */
class DslReferenceCodeBlocksTest {
    @TestFactory
    fun `every qkt block in the DSL reference parses and compiles`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> {
        val blocks = referenceBlocks()
        assertThat(blocks).withFailMessage("found no ```qkt blocks under $REFERENCE_DIR").isNotEmpty()
        return blocks.map { block ->
            DynamicTest.dynamicTest("${block.file}:${block.line}") {
                when (block.marker) {
                    "grammar", "skip" -> Unit
                    "illegal" -> assertThat(checkBlock(block, tempDir)).isNotNull()
                    else -> {
                        val error =
                            assertTimeoutPreemptively(
                                Duration.ofSeconds(20),
                                ThrowingSupplier { checkBlock(block, tempDir) },
                            )
                        assertThat(error)
                            .withFailMessage(
                                "%s:%d does not compile: %s%n%s",
                                block.file,
                                block.line,
                                error,
                                block.lines.joinToString("\n"),
                            ).isNull()
                    }
                }
            }
        }
    }

    private data class Block(
        val file: String,
        val line: Int,
        val marker: String?,
        val lines: List<String>,
    )

    private fun referenceBlocks(): List<Block> =
        Files
            .list(REFERENCE_DIR)
            .use { files -> files.filter { it.toString().endsWith(".md") }.sorted().toList() }
            .flatMap { path -> blocksIn(path) }

    private fun blocksIn(path: Path): List<Block> {
        val lines = Files.readAllLines(path)
        val out = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            if (FENCE.matches(lines[i].trim())) {
                var previous = i - 1
                while (previous >= 0 && lines[previous].isBlank()) previous--
                val marker = if (previous >= 0) MARKER.find(lines[previous])?.groupValues?.get(1) else null
                var end = i + 1
                while (end < lines.size && !lines[end].trim().startsWith("```")) end++
                out += Block(path.fileName.toString(), i + 1, marker, lines.subList(i + 1, end))
                i = end
            }
            i++
        }
        return out
    }

    /** Returns null when the block compiles, otherwise the first error. */
    private fun checkBlock(
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

    private fun compileWrapped(
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

    private fun combinations(size: Int): List<List<RefType>> =
        (0 until size).fold(listOf(emptyList())) { acc, _ ->
            acc.flatMap { prefix ->
                RefType.entries.map { prefix + it }
            }
        }

    private fun compile(
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

    private fun writeStubChildren(
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

    private sealed interface Shape {
        data object File : Shape

        data object Portfolio : Shape

        data class Wrapped(
            val sections: Map<String, List<String>>,
        ) : Shape
    }

    private enum class RefType(
        val expression: String,
    ) {
        NUMBER("btc.close"),
        BOOLEAN("btc.close > 0"),
        STRING("'x'"),
    }

    private fun shape(lines: List<String>): Shape {
        val first = firstWord(lines)
        return when {
            first == "STRATEGY" || first == "PORTFOLIO" -> Shape.File
            first == "IMPORT" || first == "RUN" || lines.any { RUN_WORD.containsMatchIn(code(it)) } -> Shape.Portfolio
            first in SECTIONS || first == "SYNCHRONIZE" -> Shape.Wrapped(splitSections(lines))
            first == "WHEN" || first == "FOR" -> Shape.Wrapped(splitSections(withThen(lines)))
            first in ACTIONS -> Shape.Wrapped(mapOf("RULES" to actionRules(lines)))
            first in CLAUSES -> Shape.Wrapped(mapOf("RULES" to clauseRules(lines, first)))
            first == "CASE" -> Shape.Wrapped(mapOf("LET" to listOf("LET doc_expr =") + lines.map { "  $it" }))
            else ->
                Shape.Wrapped(
                    mapOf(
                        "LET" to
                            lines
                                .map { code(it).trim() }
                                .filter { it.isNotEmpty() }
                                .mapIndexed { index, expression -> "LET doc_e$index = $expression" },
                    ),
                )
        }
    }

    /** A condition-only snippet (`WHEN a > 0` lines with no `THEN`) gets a `THEN` on each rule. */
    private fun withThen(lines: List<String>): List<String> {
        if (lines.any { THEN_WORD.containsMatchIn(code(it)) }) return lines
        val groups = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        for (line in lines) {
            if (WHEN_START.containsMatchIn(code(line)) && current.any { code(it).isNotBlank() }) {
                groups += current
                current = mutableListOf()
            }
            current += line
        }
        if (current.isNotEmpty()) groups += current
        return groups.flatMap { group ->
            val last = group.indexOfLast { code(it).isNotBlank() }
            group.mapIndexed { index, line -> if (index == last) code(line).trimEnd() + " THEN LOG \"doc\"" else line }
        }
    }

    private fun actionRules(lines: List<String>): List<String> {
        val statements = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var depth = 0
        for (line in lines) {
            val c = code(line).trim()
            val startsAction = ACTION_START.containsMatchIn(c)
            val continues = current.lastOrNull()?.trimEnd()?.endsWith(";") == true
            if (depth == 0 && startsAction && current.any { code(it).isNotBlank() } && !continues) {
                statements += current
                current = mutableListOf()
            }
            current += line
            depth += c.count { it == '{' } - c.count { it == '}' }
        }
        if (current.isNotEmpty()) statements += current
        return statements.flatMap { statement ->
            val head = statement.indexOfFirst { code(it).isNotBlank() }
            val headLine = statement[head].trim()
            val startsWithThen = code(headLine).startsWith("THEN")
            val prefix = if (startsWithThen) "  WHEN btc.close > 0 " else "  WHEN btc.close > 0 THEN "
            listOf(prefix + headLine) + statement.drop(head + 1).map { "    " + it.trim() }
        }
    }

    private fun clauseRules(
        lines: List<String>,
        first: String,
    ): List<String> {
        val starts = lines.indices.filter { CLAUSE_START.containsMatchIn(code(lines[it]).trim()) }
        val oneClausePerLine = starts.size > 1 && starts.all { code(lines[it]).trim().substringBefore(' ') == first }
        return if (oneClausePerLine) {
            starts.flatMap { listOf("  WHEN btc.close > 0 THEN BUY btc SIZING 0.1", "    " + lines[it]) }
        } else {
            listOf("  WHEN btc.close > 0 THEN BUY btc SIZING 0.1") + lines.map { "    $it" }
        }
    }

    private fun splitSections(lines: List<String>): Map<String, List<String>> {
        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        var caseDepth = 0
        for (line in lines) {
            val c = code(line).trim()
            val section = SECTION_START.find(c)?.groupValues?.get(1)
            if (section != null) {
                caseDepth += CASE_WORD.findAll(c).count() - END_WORD.findAll(c).count()
                current = section
                val body = sections.getOrPut(section) { mutableListOf() }
                val rest = c.removePrefix(section).trim()
                when {
                    section in HEADER_KEPT -> body += line.trim()
                    rest.isNotEmpty() -> body += "  $rest"
                }
                continue
            }
            if (c.startsWith("SYNCHRONIZE") && current == null) current = "SYMBOLS"
            if (RULE_START.containsMatchIn(c) && current !in RULE_OWNERS && caseDepth == 0) current = "RULES"
            if (current == null) current = "RULES"
            sections.getOrPut(current) { mutableListOf() } += line
            caseDepth += CASE_WORD.findAll(c).count() - END_WORD.findAll(c).count()
        }
        return sections
    }

    private fun assemble(
        sections: Map<String, List<String>>,
        aliases: List<String>,
        refs: Map<String, RefType>,
    ): String {
        val out = mutableListOf("STRATEGY doc VERSION 1")
        out += sections["DEFAULTS"].orEmpty()
        out += "SYMBOLS"
        val declared = sections["SYMBOLS"].orEmpty().joinToString("\n")
        for (alias in aliases) {
            if (!Regex("(?m)^\\s*${Regex.escape(alias)}\\s*=").containsMatchIn(declared)) {
                out += "  $alias = BACKTEST:BTCUSD EVERY 1h"
            }
        }
        out += sections["SYMBOLS"].orEmpty()
        out += sections["PARAM"].orEmpty()
        out += sections["LET"].orEmpty()
        refs.forEach { (name, type) -> out += "LET $name = ${type.expression}" }
        out += sections["SCHEDULE"].orEmpty()
        out += sections["SEQUENCE"].orEmpty()
        out += "RULES"
        val rules = sections["RULES"].orEmpty()
        out += if (rules.any { code(it).isNotBlank() }) rules else listOf("  WHEN btc.close > 0 THEN LOG \"doc\"")
        // qkt does not compile a LET nothing reads, so reference every LET the snippet declares.
        val letText = sections["LET"].orEmpty().joinToString("\n") { code(it) }
        val letNames =
            LET_NAME
                .findAll(letText)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        if (letNames.isNotEmpty()) {
            out += "  WHEN " + letNames.joinToString(" AND ") { "$it IS NOT NULL" } + " THEN LOG \"doc\""
        }
        return out.joinToString("\n") + "\n"
    }

    private fun portfolioHarness(lines: List<String>): String {
        val imports = lines.filter { IMPORT_START.containsMatchIn(code(it)) }.toMutableList()
        val rest = lines.filterNot { IMPORT_START.containsMatchIn(code(it)) }
        val restText = rest.joinToString("\n") { code(it) }
        val importText = imports.joinToString("\n") { code(it) }
        val children = RUN_ALIAS.findAll(restText).map { it.groupValues[1] }.toSortedSet()
        val imported = IMPORT_AS.findAll(importText).map { it.groupValues[1] }.toSet()
        imports += (children - imported).map { "IMPORT '$it.qkt' AS $it" }
        val rules = rest.filter { code(it).isNotBlank() && !code(it).trim().startsWith("RULES") }
        return "PORTFOLIO doc VERSION 1\nSYMBOLS\n  btc = BACKTEST:BTCUSD EVERY 1h\n" +
            imports.joinToString("\n") + "\nRULES\n" + rules.joinToString("\n") { "    " + it.trim() } + "\n"
    }

    private fun detectAliases(lines: List<String>): MutableList<String> {
        val body = lines.joinToString("\n") { code(it) }
        val found = mutableSetOf<String>()
        FIELD_ACCESS.findAll(body).forEach { found += it.groupValues[1] }
        POSITION_ACCESS.findAll(body).forEach { found += it.groupValues[1] }
        ORDER_TARGET.findAll(body).forEach { found += it.groupValues[1] }
        SYNCHRONIZE.findAll(body).forEach { group ->
            IDENTIFIER.findAll(group.groupValues[1].split(WITHIN_WORD).first()).forEach { found += it.value }
        }
        val aliases = found.filter { it !in RESERVED && it != "btc" && it != it.uppercase() }.sorted()
        return (listOf("btc") + aliases).toMutableList()
    }

    private fun firstWord(lines: List<String>): String {
        val line = lines.map { code(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return WORD.find(line)?.value ?: line.take(1)
    }

    /** The code part of a line: a whole-line comment is empty, a trailing `-- comment` is dropped. */
    private fun code(line: String): String =
        if (line.trim().startsWith("--")) "" else line.replace(TRAILING_COMMENT, "")

    private companion object {
        private val REFERENCE_DIR: Path = Path.of("docs", "reference", "dsl")
        private val FENCE = Regex("```qkt(\\s.*)?")
        private val MARKER = Regex("<!--\\s*qkt-doc:\\s*([a-z-]+)\\b[^>]*-->")
        private val SECTIONS = setOf("DEFAULTS", "SYMBOLS", "PARAM", "LET", "SCHEDULE", "SEQUENCE", "RULES")
        private val HEADER_KEPT = setOf("LET", "PARAM", "DEFAULTS", "SCHEDULE", "SEQUENCE")
        private val RULE_OWNERS = setOf("RULES", "SCHEDULE", "SEQUENCE")
        private val ACTIONS =
            setOf(
                "BUY",
                "SELL",
                "CLOSE",
                "CLOSE_ALL",
                "CANCEL",
                "CANCEL_ALL",
                "FLATTEN",
                "LOG",
                "RESIZE",
                "OCO_ENTRY",
                "LATCH",
                "THEN",
            )
        private val CLAUSES = setOf("BRACKET", "ORDER_TYPE", "STACK", "TIF", "EXPIRES", "WITHIN", "TIMES", "SIZING")
        private val RESERVED =
            setOf("ACCOUNT", "NOW", "STREAK", "EXIT", "POSITION", "SYMBOL", "SESSION", "BASKET", "account", "now")
        private val SECTION_START = Regex("^(DEFAULTS|SYMBOLS|PARAM|LET|SCHEDULE|SEQUENCE|RULES)\\b")
        private val RULE_START = Regex("^(WHEN|FOR\\s+EACH)\\b")
        private val WHEN_START = Regex("^\\s*WHEN\\b")
        private val THEN_WORD = Regex("\\bTHEN\\b")
        private val RUN_WORD = Regex("\\bRUN\\b")
        private val CASE_WORD = Regex("\\bCASE\\b")
        private val END_WORD = Regex("\\bEND\\b")
        private val ACTION_START = Regex("^(${ACTIONS.joinToString("|")})\\b")
        private val CLAUSE_START = Regex("^(${CLAUSES.joinToString("|")})\\b")
        private val IMPORT_START = Regex("^\\s*IMPORT\\b")
        private val IMPORT_AS = Regex("\\bAS\\s+(\\w+)")
        private val IMPORT_PATH = Regex("IMPORT\\s+['\"]([^'\"]+)['\"]")
        private val RUN_ALIAS = Regex("\\bRUN\\s+(\\w+)")
        private val OVERRIDE = Regex("OVERRIDE\\s*\\{([^}]*)}")
        private val OVERRIDE_KEY = Regex("(\\w+)\\s*=")
        private val LET_NAME = Regex("(?:\\bLET\\s+|,\\s*)([A-Za-z_]\\w*)\\s*=(?!=)")
        private val FIELD_ACCESS =
            Regex("\\b([A-Za-z_]\\w*)\\.(?:close|open|high|low|volume|price|bid|ask|spread|value|candle|tick)\\b")
        private val POSITION_ACCESS = Regex("\\bPOSITION\\.([A-Za-z_]\\w*)")
        private val ORDER_TARGET = Regex("\\b(?:BUY|SELL|CLOSE|CANCEL|RESIZE)\\s+([a-z_]\\w*)")
        private val SYNCHRONIZE = Regex("\\bSYNCHRONIZE\\s+((?:[a-z_]\\w*\\s*)+)")
        private val WITHIN_WORD = Regex("\\bWITHIN\\b")
        private val IDENTIFIER = Regex("[a-z_]\\w*")
        private val UNKNOWN_ALIAS = Regex("Unknown stream alias: (\\w+)")
        private val UNKNOWN_REFERENCE = Regex("Unknown reference: (\\w+)")
        private val WORD = Regex("[A-Za-z_]+")
        private val TRAILING_COMMENT = Regex("\\s+--.*$")
    }
}
