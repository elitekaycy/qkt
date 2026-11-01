package com.qkt.lsp

import com.qkt.dsl.parse.ParsedFile
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of open documents: the current text plus the most recently
 * parsed AST.
 *
 * Keeping the last *successfully* parsed AST lets completion and hover keep working
 * while the user is mid-edit and the document does not currently parse. e.g. a doc
 * opens valid (AST stored); the user types a half-finished line (parse now fails);
 * symbol-alias completion still works off the retained AST.
 *
 * Safe to use from the protocol thread and the diagnostics thread at once.
 */
class DocumentStore {
    private data class Entry(
        val text: String,
        val lastGoodAst: ParsedFile?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Record [text] for [uri]. When [ast] is non-null (the text parsed) it becomes the
     * new last-good AST; when null (the text failed to parse) the previous last-good
     * AST is retained.
     */
    fun put(
        uri: String,
        text: String,
        ast: ParsedFile?,
    ) {
        entries.compute(uri) { _, prior -> Entry(text, ast ?: prior?.lastGoodAst) }
    }

    /**
     * Record [ast] as the result of analysing [text], but only while [text] is still the current
     * text of [uri]. Returns false when the document changed or closed in the meantime, so a stale
     * analysis never overwrites a newer edit.
     */
    fun putAnalysis(
        uri: String,
        text: String,
        ast: ParsedFile?,
    ): Boolean {
        var current = false
        entries.computeIfPresent(uri) { _, e ->
            current = e.text == text
            if (current) Entry(text, ast ?: e.lastGoodAst) else e
        }
        return current
    }

    /** Current text of [uri], or null if not open. */
    fun text(uri: String): String? = entries[uri]?.text

    /** Most recent successfully-parsed AST for [uri], or null if it has never parsed. */
    fun lastGoodAst(uri: String): ParsedFile? = entries[uri]?.lastGoodAst

    /** Drop [uri] from the store (on close). */
    fun remove(uri: String) {
        entries.remove(uri)
    }
}
