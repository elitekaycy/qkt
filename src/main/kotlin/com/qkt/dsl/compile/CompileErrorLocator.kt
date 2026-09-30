package com.qkt.dsl.compile

/**
 * Turns a [CompileError] into a source position for editors and `qkt parse`.
 *
 * The compiler tags an error with the line of the rule it was compiling; this locator finds
 * the identifier the message names inside that rule's text (from its `WHEN` to the next rule)
 * and points at it. Failing that it points at the rule's `WHEN`, then at the section keyword the
 * error belongs to, then at `1:1`. Every rule is one table row: a message pattern whose first
 * group is the identifier, plus the section keyword to fall back on.
 */
object CompileErrorLocator {
    /** A 1-based source position and the width of the token it names (1 when none was found). */
    data class Location(
        val line: Int,
        val col: Int,
        val width: Int,
    )

    /**
     * One message shape: [pattern]'s first group is the identifier to look for, [section] is the
     * keyword to fall back on, and [precededBy] narrows the search to the identifier following
     * one of those words (an order verb, for a read-only target), else any occurrence.
     */
    private class Rule(
        pattern: String,
        val section: String,
        val precededBy: String? = null,
    ) {
        val regex = Regex(pattern)
    }

    private const val ORDER_VERBS = "BUY|SELL|CLOSE|CANCEL|RESIZE|LATCH"

    private val ident = """([A-Za-z_][A-Za-z0-9_]*)"""

    /** Ordered by precedence: a portfolio child's error wraps the child's own message. */
    private val rules: List<Rule> =
        listOf(
            Rule("""^child '$ident'""", "IMPORT", precededBy = "AS"),
            Rule("""Unknown indicator: $ident""", "RULES"),
            Rule("""Unknown function: $ident""", "RULES"),
            Rule("""Unknown stream alias: $ident""", "SYMBOLS"),
            Rule("""Unknown SEQUENCE stream alias: $ident""", "SEQUENCE"),
            Rule("""Unknown basket constituent alias: $ident""", "SYMBOLS"),
            Rule("""Unknown stream field for [^:]+: $ident""", "RULES"),
            Rule("""Unknown reference: $ident""", "LET"),
            Rule("""Unknown LET reference: $ident""", "LET"),
            Rule("""Indicator $ident expects""", "RULES"),
            Rule("""Function $ident expects""", "RULES"),
            Rule("""LET '$ident' refers to itself""", "LET"),
            Rule("""^Duplicate LET name""", "LET"),
            Rule("""PARAM '$ident'""", "PARAM"),
            Rule("""Series '$ident' is read-only""", "RULES", precededBy = ORDER_VERBS),
            Rule("""BASKET '$ident'""", "SYMBOLS"),
            Rule("""SEQUENCE '$ident'""", "SEQUENCE"),
            Rule("""^BRACKET requires""", "BRACKET"),
            Rule("""^SIZING RISK""", "SIZING"),
            Rule("""^RESIZE cannot""", "RESIZE"),
            Rule("""^LATCH""", "LATCH"),
            Rule("""^SYMBOL placeholder""", "DEFAULTS"),
        )

    private val ruleStart = Regex("""^\s*(WHEN|FOR)\b""")

    /** Locates [error] in [source]; see the class docs for the search order. */
    fun locate(
        source: String,
        error: CompileError,
    ): Location {
        if (error.line != null && error.col != null) return Location(error.line, error.col, 1)
        val lines = source.lines()
        val rule = rules.firstOrNull { it.regex.containsMatchIn(error.message) }
        val identifier =
            rule
                ?.regex
                ?.find(error.message)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotEmpty() }
        val ruleLine = error.line?.takeIf { it in 1..lines.size }
        val whole = 1..lines.size
        if (ruleLine != null) {
            val inRule = ruleLine until nextRuleStart(lines, ruleLine)
            (identifier ?: rule?.section)?.let { findWord(lines, it, inRule, rule?.precededBy) }?.let { return it }
            return Location(ruleLine, lines[ruleLine - 1].indexOfFirstNonBlank() + 1, whenWidth(lines[ruleLine - 1]))
        }
        identifier?.let { findWord(lines, it, whole, rule?.precededBy) }?.let { return it }
        rule?.section?.let { findWord(lines, it, whole, null) }?.let { return it }
        return Location(1, 1, 1)
    }

    /** First line after [ruleLine] (1-based, exclusive bound) that opens another rule, else past EOF. */
    private fun nextRuleStart(
        lines: List<String>,
        ruleLine: Int,
    ): Int = ((ruleLine + 1)..lines.size).firstOrNull { ruleStart.containsMatchIn(lines[it - 1]) } ?: lines.size + 1

    /** First whole-word occurrence of [word] in [range]; with [precededBy], only one following such a word. */
    private fun findWord(
        lines: List<String>,
        word: String,
        range: IntRange,
        precededBy: String?,
    ): Location? {
        val prefix = precededBy?.let { """(?:$it)\s+""" } ?: """(?<![A-Za-z0-9_])"""
        val regex = Regex("""$prefix(${Regex.escape(word)})(?![A-Za-z0-9_])""")
        for (lineNo in range) {
            val m = regex.find(lines[lineNo - 1]) ?: continue
            return Location(
                lineNo,
                m.groups[1]
                    ?.range
                    ?.first
                    ?.plus(1) ?: 1,
                word.length,
            )
        }
        return precededBy?.let { findWord(lines, word, range, null) }
    }

    private fun whenWidth(line: String): Int =
        Regex("""^\s*([A-Za-z]+)""")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.length ?: 1

    private fun String.indexOfFirstNonBlank(): Int = indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
}
