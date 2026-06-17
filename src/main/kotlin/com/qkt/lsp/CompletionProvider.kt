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
 * Right after `<alias>.` it offers that stream's fields; anywhere else it offers the full
 * vocabulary plus the symbols declared in this file. Candidates are returned unfiltered:
 * the editor narrows them by whatever the user has typed.
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
        return if (text.getOrNull(wordStart - 1) == '.') {
            val ownerStart = Cursor.identStart(text, wordStart - 1)
            val owner = text.substring(ownerStart, wordStart - 1)
            memberItems(owner, lastGoodAst)
        } else {
            generalItems(lastGoodAst)
        }
    }

    /** After `<alias>.`: stream fields if the owner is a known alias, otherwise nothing. */
    private fun memberItems(
        owner: String,
        ast: ParsedFile?,
    ): List<CompletionItem> =
        if (owner in streamAliases(ast)) {
            QktVocabulary.streamFields.map { item(it, CompletionItemKind.Field) }
        } else {
            emptyList()
        }

    /** Everywhere else: the full vocabulary plus the symbols this document declares. */
    private fun generalItems(ast: ParsedFile?): List<CompletionItem> {
        val items = mutableListOf<CompletionItem>()
        QktVocabulary.keywords.forEach { items += item(it, CompletionItemKind.Keyword) }
        QktVocabulary.indicators.forEach { items += item(it, CompletionItemKind.Function) }
        QktVocabulary.functions.forEach { items += item(it, CompletionItemKind.Function) }
        QktVocabulary.constants.forEach { items += item(it, CompletionItemKind.Constant) }
        documentSymbols(ast).forEach { items += item(it, CompletionItemKind.Variable) }
        QktSnippets.all.forEach { items += snippetItem(it) }
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

    private fun streamAliases(ast: ParsedFile?): Set<String> =
        when (ast) {
            is ParsedFile.StrategyFile ->
                ast.ast.streams
                    .map { it.alias }
                    .toSet()
            is ParsedFile.PortfolioFile ->
                ast.ast.streams
                    .map { it.alias }
                    .toSet()
            null -> emptySet()
        }

    private fun documentSymbols(ast: ParsedFile?): List<String> =
        when (ast) {
            is ParsedFile.StrategyFile ->
                ast.ast.streams.map { it.alias } +
                    ast.ast.lets.map { it.name } +
                    ast.ast.params.map { it.name }
            is ParsedFile.PortfolioFile ->
                ast.ast.streams.map { it.alias } + ast.ast.imports.map { it.alias }
            null -> emptyList()
        }

    private fun item(
        label: String,
        kind: CompletionItemKind,
    ): CompletionItem = CompletionItem(label).apply { this.kind = kind }
}
