package com.qkt.lsp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Render [QktSnippets.all] as the VS Code snippets file (`editor/vscode/snippets/qkt.json`): a JSON
 * object keyed by [QktSnippets.Snippet.title], each value carrying `prefix`, `body`, and `description`.
 * VS Code snippet syntax is identical to the LSP's, so the bodies are emitted verbatim. VS Code has
 * no notion of [QktSnippets.Scope], so every snippet is listed there.
 */
fun QktSnippets.toVscodeJson(): String {
    val map = LinkedHashMap<String, VscodeSnippet>()
    for (s in all) map[s.title] = VscodeSnippet(s.prefix, s.body, s.description)
    return VSCODE_JSON.encodeToString(MapSerializer(String.serializer(), VscodeSnippet.serializer()), map)
}

@Serializable
private data class VscodeSnippet(
    val prefix: String,
    val body: List<String>,
    val description: String,
)

@OptIn(ExperimentalSerializationApi::class)
private val VSCODE_JSON =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
