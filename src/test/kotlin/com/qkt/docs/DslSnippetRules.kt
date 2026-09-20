package com.qkt.docs

import com.qkt.docs.DslSnippetShapes.ACTIONS
import com.qkt.docs.DslSnippetShapes.CLAUSES
import com.qkt.docs.DslSnippetShapes.code

/** Turns condition, action and clause snippets into complete `WHEN ... THEN` rules. */
internal object DslSnippetRules {
    /** A condition-only snippet (`WHEN a > 0` lines with no `THEN`) gets a `THEN` on each rule. */
    fun withThen(lines: List<String>): List<String> {
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

    fun actionRules(lines: List<String>): List<String> {
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

    fun clauseRules(
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

    val WHEN_START = Regex("^\\s*WHEN\\b")
    val THEN_WORD = Regex("\\bTHEN\\b")
    val ACTION_START = Regex("^(${ACTIONS.joinToString("|")})\\b")
    val CLAUSE_START = Regex("^(${CLAUSES.joinToString("|")})\\b")
}
