package com.qkt.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Re-encodes the TextMate JSON grammar as a property-list `.tmLanguage`, the form Sublime Text
 * loads. The rules are the same; only the encoding differs.
 */
internal object PlistGrammar {
    fun render(textMateJson: String): String =
        buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine(
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">",
            )
            appendLine("<!-- Generated from the TextMate grammar by qkt editor grammar, format tmlanguage. -->")
            appendLine("<plist version=\"1.0\">")
            element(Json.parseToJsonElement(textMateJson), 0)
            appendLine("</plist>")
        }

    private fun StringBuilder.element(
        e: JsonElement,
        depth: Int,
    ) {
        val pad = "  ".repeat(depth)
        when (e) {
            is JsonObject -> {
                appendLine("$pad<dict>")
                for ((k, v) in e) {
                    appendLine("$pad  <key>${escape(k)}</key>")
                    element(v, depth + 1)
                }
                appendLine("$pad</dict>")
            }
            is JsonArray -> {
                appendLine("$pad<array>")
                e.forEach { element(it, depth + 1) }
                appendLine("$pad</array>")
            }
            is JsonPrimitive ->
                when {
                    e is JsonNull -> appendLine("$pad<string></string>")
                    e.isString -> appendLine("$pad<string>${escape(e.content)}</string>")
                    e.content == "true" || e.content == "false" -> appendLine("$pad<${e.content}/>")
                    else -> appendLine("$pad<integer>${e.content}</integer>")
                }
        }
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
