package com.qkt.lsp

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService

/**
 * Handles the per-document LSP requests for `.qkt` files: text synchronization plus
 * the language features (diagnostics, completion, hover).
 *
 * Document sync is full-text (the whole file arrives on every change), which is simple
 * and correct for the small files qkt strategies are. Text is stored as it arrives, so
 * completion and hover always see the latest edit; the parse and compile behind the
 * diagnostics run on [scheduler], once per pause in typing rather than per keystroke.
 */
class QktTextDocumentService(
    private val documents: DocumentStore,
    private val scheduler: DiagnosticsScheduler = DiagnosticsScheduler(),
) : TextDocumentService {
    private var client: LanguageClient? = null

    /** Called once the server is connected, so we can push diagnostics to the editor. */
    fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        documents.put(doc.uri, doc.text, null)
        analyzeLater(doc.uri, doc.text, delayMs = 0)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Full-sync: the (single) change event carries the entire document text.
        val text = params.contentChanges.lastOrNull()?.text ?: return
        val uri = params.textDocument.uri
        documents.put(uri, text, null)
        analyzeLater(uri, text)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        scheduler.cancel(uri)
        documents.remove(uri)
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {}

    override fun completion(
        position: CompletionParams,
    ): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val uri = position.textDocument.uri
        val text = documents.text(uri) ?: ""
        val items =
            CompletionProvider.complete(
                text = text,
                line = position.position.line,
                character = position.position.character,
                lastGoodAst = documents.lastGoodAst(uri),
            )
        val result: Either<List<CompletionItem>, CompletionList> = Either.forLeft(items)
        return CompletableFuture.completedFuture(result)
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val uri = params.textDocument.uri
        val text = documents.text(uri) ?: ""
        val hover =
            HoverProvider.hover(
                text = text,
                line = params.position.line,
                character = params.position.character,
                lastGoodAst = documents.lastGoodAst(uri),
            )
        return CompletableFuture.completedFuture(hover)
    }

    /** Stops the diagnostics thread. */
    fun shutdown() {
        scheduler.shutdown()
    }

    /**
     * Parse [text] for [uri] off the protocol thread, remember the AST, and push diagnostics,
     * unless a newer edit arrived while it ran.
     */
    private fun analyzeLater(
        uri: String,
        text: String,
        delayMs: Long = DiagnosticsScheduler.DEFAULT_QUIET_MS,
    ) {
        scheduler.schedule(uri, delayMs) {
            val analysis = DiagnosticsRunner.analyze(text)
            if (documents.putAnalysis(uri, text, analysis.parsed)) {
                client?.publishDiagnostics(PublishDiagnosticsParams(uri, analysis.diagnostics))
            }
        }
    }
}
