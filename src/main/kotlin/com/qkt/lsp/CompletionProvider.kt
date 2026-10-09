package com.qkt.lsp

import com.qkt.dsl.parse.ParsedFile
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat

/**
 * Computes completion candidates for a cursor in a `.qkt` document.
 *
 * Works purely off the document text plus the last successfully parsed AST — qkt's AST
 * carries no source spans, so the cursor's immediate left context drives everything.
 * Right after a `.` it resolves the dotted owner ([MemberCompletion]); anywhere else it offers
 * the full vocabulary plus the symbols declared in this file. Candidates are returned
 * unfiltered: the editor narrows them by whatever the user has typed.
 */
object CompletionProvider {
    fun complete(
        text: String,
        line: Int,
        character: Int,
        lastGoodAst: ParsedFile?,
    ): List<CompletionItem> {
        val offset = Cursor.offset(text, line, character)
        val wordStart = Cursor.identStart(text, offset)
        val symbols = DocumentSymbols.of(lastGoodAst)
        if (text.getOrNull(wordStart - 1) != '.') return generalItems(symbols, scopeAt(text, line))
        val chain = MemberCompletion.ownerChain(text, wordStart - 1)
        val items = MemberCompletion.items(chain, symbols)
        if (items.isNotEmpty() || chain.size != 1) return items
        // Half-typed document: the AST is stale or missing, but the alias being typed is usually
        // still declared in the SYMBOLS text above. Retry against those before giving up (#1376).
        return MemberCompletion.items(chain, symbols, textualStreamAliases(text))
    }

    /**
     * Stream aliases read straight off the document's SYMBOLS block, for when the last good AST
     * predates the alias being typed. Only indented `alias = ...` lines under the SYMBOLS header
     * count, mirroring how the parser scopes declarations.
     */
    internal fun textualStreamAliases(text: String): Set<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("SYMBOLS") }
        if (start < 0) return emptySet()
        return lines.drop(start + 1)
            .takeWhile { it.isBlank() || it.startsWith(" ") || it.startsWith("\t") }
            .mapNotNull { Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=""").find(it)?.groupValues?.get(1) }
            .toSet()
    }

    /**
     * Which snippets fit the cursor's line, read from the section headers above it: an empty document
     * takes whole-file templates, SYMBOLS takes a stream line, the space between SYMBOLS and RULES takes
     * declarations, and RULES takes rules. A PORTFOLIO takes none. e.g. the line after `RULES` gets `rule`.
     */
    internal fun scopeAt(
        text: String,
        line: Int,
    ): QktSnippets.Scope? {
        val above = text.lines().take(line)
        if (above.all { it.isBlank() || it.trimStart().startsWith("#") }) return QktSnippets.Scope.FILE
        if (above.any { it.startsWith("PORTFOLIO") }) return null
        val header =
            above.lastOrNull { Regex("""^(STRATEGY|DEFAULTS|SYMBOLS|PARAM|LET|RULES|FOR)\b""").containsMatchIn(it) }
                ?: return null
        return when {
            header.startsWith("RULES") || header.startsWith("FOR") -> QktSnippets.Scope.RULES
            header.startsWith(
                "SYMBOLS",
            ) &&
                text.lines().getOrElse(line) { "" }.startsWith(" ") -> QktSnippets.Scope.SYMBOLS
            else -> QktSnippets.Scope.DECLARATION
        }
    }

    /** Everywhere else: the full vocabulary plus the symbols this document declares. */
    private fun generalItems(
        symbols: DocumentSymbols,
        scope: QktSnippets.Scope?,
    ): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        QktVocabulary.keywords.forEach { items += item(it, CompletionItemKind.Keyword) }
        QktVocabulary.indicators.forEach { items += item(it, CompletionItemKind.Function) }
        QktVocabulary.functions.forEach { items += item(it, CompletionItemKind.Function) }
        QktVocabulary.constants.forEach { items += item(it, CompletionItemKind.Constant) }
        symbols.names.forEach { items += item(it, CompletionItemKind.Variable) }
        QktSnippets.all.filter { it.scope == scope }.forEach { items += snippetItem(it) }
        return items
    }

    /** A strategy template offered as an inline snippet: the editor expands [Snippet.body]'s tab stops. */
    private fun snippetItem(snippet: QktSnippets.Snippet): CompletionItem =
        CompletionItem(snippet.prefix).apply {
            kind = CompletionItemKind.Snippet
            insertText = snippet.body.joinToString("\n")
            insertTextFormat = InsertTextFormat.Snippet
            detail = snippet.title
            setDocumentation(snippet.description)
        }

    private fun item(
        label: String,
        kind: CompletionItemKind,
    ): CompletionItem = CompletionItem(label).apply { this.kind = kind }
}
