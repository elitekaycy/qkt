package com.qkt.docs

import com.qkt.docs.DslSnippetRules.actionRules
import com.qkt.docs.DslSnippetRules.clauseRules
import com.qkt.docs.DslSnippetRules.withThen

/** Classifies a reference block as a whole file, a portfolio snippet or a strategy snippet split into sections. */
internal object DslSnippetShapes {
    sealed interface Shape {
        data object File : Shape

        data object Portfolio : Shape

        data class Wrapped(
            val sections: Map<String, List<String>>,
        ) : Shape
    }

    fun shape(lines: List<String>): Shape {
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

    fun splitSections(lines: List<String>): Map<String, List<String>> {
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

    fun firstWord(lines: List<String>): String {
        val line = lines.map { code(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return WORD.find(line)?.value ?: line.take(1)
    }

    /** The code part of a line: a whole-line comment is empty, a trailing `-- comment` is dropped. */
    fun code(line: String): String = if (line.trim().startsWith("--")) "" else line.replace(TRAILING_COMMENT, "")

    val SECTIONS = setOf("DEFAULTS", "SYMBOLS", "PARAM", "LET", "SCHEDULE", "SEQUENCE", "RULES")
    val HEADER_KEPT = setOf("LET", "PARAM", "DEFAULTS", "SCHEDULE", "SEQUENCE")
    val RULE_OWNERS = setOf("RULES", "SCHEDULE", "SEQUENCE")
    val ACTIONS =
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
    val CLAUSES = setOf("BRACKET", "ORDER_TYPE", "STACK", "TIF", "EXPIRES", "WITHIN", "TIMES", "SIZING")
    val SECTION_START = Regex("^(DEFAULTS|SYMBOLS|PARAM|LET|SCHEDULE|SEQUENCE|RULES)\\b")
    val RULE_START = Regex("^(WHEN|FOR\\s+EACH)\\b")
    val RUN_WORD = Regex("\\bRUN\\b")
    val CASE_WORD = Regex("\\bCASE\\b")
    val END_WORD = Regex("\\bEND\\b")
    val WORD = Regex("[A-Za-z_]+")
    val TRAILING_COMMENT = Regex("\\s+--.*$")
}
