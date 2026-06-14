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
 * and correct for the small files qkt strategies are.
 */
class QktTextDocumentService(
    private val documents: DocumentStore,
) : TextDocumentService {
    private var client: LanguageClient? = null

    /** Called once the server is connected, so we can push diagnostics to the editor. */
    fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        refresh(doc.uri, doc.text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        // Full-sync: the (single) change event carries the entire document text.
        val text = params.contentChanges.lastOrNull()?.text ?: return
        refresh(params.textDocument.uri, text)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
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

    /** Parse the latest [text] for [uri], remember the AST, and push diagnostics to the editor. */
    private fun refresh(
        uri: String,
        text: String,
    ) {
        val analysis = DiagnosticsRunner.analyze(text)
        documents.put(uri, text, analysis.parsed)
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, analysis.diagnostics))
    }
}
