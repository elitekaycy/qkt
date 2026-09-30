package com.qkt.editor

import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A small TextMate-like matcher over the generated grammar's `match` rules: rules are tried in
 * grammar order and the earliest match wins, ties going to the earlier rule. Regions
 * (`begin`/`end`) are matched on one line, as the lexer's single-line strings and comments allow.
 */
internal class TextMateProbe(
    grammarJson: String,
) {
    /** One rule: the scope it assigns (whole match or per capture group) and its compiled regex. */
    data class Rule(
        val name: String?,
        val captures: Map<Int, String>,
        val pattern: Pattern,
    )

    data class Token(
        val text: String,
        val scope: String,
    )

    val rules: List<Rule>

    /** Rules nested inside regions (string escapes), consulted by [matches] only. */
    private val nested: List<Rule>

    init {
        val root = Json.parseToJsonElement(grammarJson).jsonObject
        val repository = root.getValue("repository").jsonObject
        rules =
            root.getValue("patterns").jsonArray.flatMap { include ->
                val key =
                    include.jsonObject
                        .getValue("include")
                        .jsonPrimitive.content
                        .removePrefix("#")
                patternsOf(repository.getValue(key).jsonObject).map { toRule(it) }
            }
        nested =
            repository.values
                .flatMap { entry ->
                    patternsOf(entry.jsonObject).flatMap { patternsOf(it) }
                }.map { toRule(it) }
    }

    private fun patternsOf(rule: JsonObject): List<JsonObject> =
        rule["patterns"]?.jsonArray.orEmpty().map { it.jsonObject }

    private fun toRule(rule: JsonObject): Rule {
        val name = rule["name"]?.jsonPrimitive?.content
        val regex =
            rule["match"]?.jsonPrimitive?.content
                ?: (
                    rule.getValue("begin").jsonPrimitive.content + ".*?(" + rule.getValue("end").jsonPrimitive.content +
                        ")"
                )
        val captures =
            rule["captures"]?.jsonObject.orEmpty().map { (index, value) ->
                index.toInt() to
                    value.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
            }
        return Rule(name, captures.toMap(), Pattern.compile(regex))
    }

    /** The scopes a probe string receives, left to right, unscoped text omitted. */
    fun tokens(line: String): List<Token> {
        val out = mutableListOf<Token>()
        var pos = 0
        while (pos < line.length) {
            val best =
                rules
                    .mapNotNull { rule ->
                        val m = rule.pattern.matcher(line)
                        if (m.find(pos) && m.end() > m.start()) rule to m.toMatchResult() else null
                    }.minByOrNull { (_, m) -> m.start() } ?: break
            val (rule, m) = best
            if (rule.name != null) out += Token(m.group(), rule.name)
            for ((index, scope) in rule.captures) {
                m.group(index)?.let { out += Token(it, scope) }
            }
            pos = m.end()
        }
        return out
    }

    /** The scope the first token of [probe] gets, or null when nothing matches. */
    fun scopeOf(probe: String): String? = tokens(probe).firstOrNull()?.scope

    /** True when any rule carrying [scope] matches somewhere in [probe]. */
    fun matches(
        scope: String,
        probe: String,
    ): Boolean =
        (rules + nested).any { rule ->
            (rule.name == scope || scope in rule.captures.values) && rule.pattern.matcher(probe).find()
        }
}
