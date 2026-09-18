package com.qkt.docs

import com.qkt.docs.DslSnippetShapes.code

/** Wraps a snippet in the smallest strategy or portfolio that declares every alias and name it uses. */
internal object DslSnippetHarness {
    enum class RefType(
        val expression: String,
    ) {
        NUMBER("btc.close"),
        BOOLEAN("btc.close > 0"),
        STRING("'x'"),
    }

    fun assemble(
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

    fun portfolioHarness(lines: List<String>): String {
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

    fun detectAliases(lines: List<String>): MutableList<String> {
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

    val RESERVED =
        setOf("ACCOUNT", "NOW", "STREAK", "EXIT", "POSITION", "SYMBOL", "SESSION", "BASKET", "account", "now")
    val IMPORT_START = Regex("^\\s*IMPORT\\b")
    val IMPORT_AS = Regex("\\bAS\\s+(\\w+)")
    val RUN_ALIAS = Regex("\\bRUN\\s+(\\w+)")
    val LET_NAME = Regex("(?:\\bLET\\s+|,\\s*)([A-Za-z_]\\w*)\\s*=(?!=)")
    val FIELD_ACCESS =
        Regex("\\b([A-Za-z_]\\w*)\\.(?:close|open|high|low|volume|price|bid|ask|spread|value|candle|tick)\\b")
    val POSITION_ACCESS = Regex("\\bPOSITION\\.([A-Za-z_]\\w*)")
    val ORDER_TARGET = Regex("\\b(?:BUY|SELL|CLOSE|CANCEL|RESIZE)\\s+([a-z_]\\w*)")
    val SYNCHRONIZE = Regex("\\bSYNCHRONIZE\\s+((?:[a-z_]\\w*\\s*)+)")
    val WITHIN_WORD = Regex("\\bWITHIN\\b")
    val IDENTIFIER = Regex("[a-z_]\\w*")
}
