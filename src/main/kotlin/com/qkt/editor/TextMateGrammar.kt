package com.qkt.editor

import com.qkt.dsl.parse.KeywordCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Emits the TextMate grammar (`source.qkt`) for VS Code, IntelliJ and other TextMate hosts.
 * Rules are tried in [repositoryOrder]; the first rule matching at the earliest position wins,
 * so comments precede operators (`--`), the dotted-member rules precede keywords (`gold.close`
 * is a field, as the parser reads it), keywords precede the bare-alias rules, and the indicator
 * and function rules require a following `(` so aliases named like them stay plain.
 */
internal object TextMateGrammar {
    private const val IDENT = "[A-Za-z_][A-Za-z0-9_]*"

    /** Rule names in priority order; each is a key of [repository]. */
    val repositoryOrder: List<String> =
        listOf("comments", "strings", "numbers", "symbols-alias", "action-target", "fields", "members") +
            KeywordCategory.entries.map { "keyword-${it.name.lowercase().replace('_', '-')}" } +
            listOf("indicators", "functions", "constants", "broker-prefix", "alias", "operators")

    fun render(): String {
        val json =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
        return json.encodeToString(JsonObject.serializer(), grammar()) + "\n"
    }

    private fun grammar(): JsonObject =
        buildJsonObject {
            put("name", "qkt")
            put("scopeName", "source.qkt")
            putJsonArray("fileTypes") { add(kotlinx.serialization.json.JsonPrimitive("qkt")) }
            putJsonArray("patterns") { repositoryOrder.forEach { add(include(it)) } }
            putJsonObject("repository") { repository().forEach { (name, rules) -> put(name, rules) } }
        }

    private fun repository(): Map<String, JsonObject> =
        buildMap {
            put(
                "comments",
                rules(
                    match("comment.line.double-dash.qkt", "--.*$"),
                    match("comment.line.number-sign.qkt", "#.*$"),
                    blockComment(),
                ),
            )
            put("strings", rules(string("double", "\""), string("single", "'")))
            put(
                "numbers",
                rules(
                    match("constant.numeric.duration.qkt", "\\b\\d+[smhd]\\b"),
                    match("constant.numeric.qkt", "\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b"),
                ),
            )
            put("symbols-alias", rules(symbolsAlias()))
            put("action-target", rules(actionTarget()))
            for (category in KeywordCategory.entries) {
                val name = "keyword-${category.name.lowercase().replace('_', '-')}"
                put(name, rules(match(category.textMateScope, words(GrammarVocabulary.keywords(category)))))
            }
            put(
                "indicators",
                rules(
                    match(
                        "support.function.indicator.qkt",
                        words(GrammarVocabulary.indicators) + "(?=\\s*\\()",
                    ),
                ),
            )
            put(
                "functions",
                rules(
                    match(
                        "support.function.builtin.qkt",
                        words(GrammarVocabulary.functions) + "(?=\\s*\\()",
                    ),
                ),
            )
            put("constants", rules(match("constant.language.qkt", words(GrammarVocabulary.constants))))
            put("broker-prefix", rules(match("entity.name.namespace.qkt", "\\b($IDENT):(?=[A-Za-z0-9_])")))
            put(
                "fields",
                rules(match("support.variable.field.qkt", "(?<=\\.)" + words(GrammarVocabulary.memberFields))),
            )
            put("members", rules(match("variable.other.member.qkt", "(?<=\\.)$IDENT")))
            put("alias", rules(match("variable.other.alias.qkt", "\\b$IDENT(?=\\s*\\.)")))
            put("operators", rules(match("keyword.operator.qkt", "->|==|<>|!=|<=|>=|<|>|=|\\+|-|\\*|/|%")))
        }

    /** A case-insensitive whole-word alternation, mirroring the lexer's case-insensitive keyword match. */
    private fun words(names: List<String>): String = "(?i)\\b(" + names.joinToString("|") + ")\\b"

    /** The alias on a `SYMBOLS` line, `gold = EXNESS:XAUUSD`: an identifier assigned a `BROKER:SYMBOL`. */
    private fun symbolsAlias(): JsonObject =
        buildJsonObject {
            put("match", "^\\s*($IDENT)\\s*(?==\\s*$IDENT:)")
            putJsonObject("captures") { putJsonObject("1") { put("name", "variable.other.alias.qkt") } }
        }

    private fun actionTarget(): JsonObject =
        buildJsonObject {
            put("match", "(?i)\\b(" + GrammarVocabulary.aliasTakingActions.joinToString("|") + ")\\s+($IDENT)\\b")
            putJsonObject("captures") {
                putJsonObject("1") { put("name", KeywordCategory.ACTION.textMateScope) }
                putJsonObject("2") { put("name", "variable.other.alias.qkt") }
            }
        }

    private fun blockComment(): JsonObject =
        buildJsonObject {
            put("name", "comment.block.qkt")
            put("begin", "/\\*")
            put("end", "\\*/")
        }

    /** A single-line string: the lexer rejects a newline inside quotes and knows five escapes. */
    private fun string(
        kind: String,
        quote: String,
    ): JsonObject =
        buildJsonObject {
            put("name", "string.quoted.$kind.qkt")
            put("begin", quote)
            put("end", "$quote|$")
            putJsonArray("patterns") {
                add(match("constant.character.escape.qkt", "\\\\[\\\\'\"nt]"))
                add(match("invalid.illegal.escape.qkt", "\\\\."))
            }
        }

    private fun match(
        scope: String,
        regex: String,
    ): JsonObject =
        buildJsonObject {
            put("name", scope)
            put("match", regex)
        }

    private fun rules(vararg items: JsonObject): JsonObject =
        buildJsonObject { putJsonArray("patterns") { items.forEach { add(it) } } }

    private fun include(name: String): JsonObject = buildJsonObject { put("include", "#$name") }
}
