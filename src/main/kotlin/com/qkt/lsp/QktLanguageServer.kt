package com.qkt.lsp

import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService

/**
 * The qkt Language Server. Declares which features the server supports and delegates
 * document and workspace requests to their services.
 *
 * Capabilities advertised: full-text document sync, completion (triggered after `.`
 * and `(` as well as on demand), and hover.
 */
class QktLanguageServer :
    LanguageServer,
    LanguageClientAware {
    private val documents = DocumentStore()
    private val textDocuments = QktTextDocumentService(documents)
    private val workspace = QktWorkspaceService()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities =
            ServerCapabilities().apply {
                setTextDocumentSync(TextDocumentSyncKind.Full)
                completionProvider = CompletionOptions(false, listOf(".", "("))
                setHoverProvider(true)
            }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any?> = CompletableFuture.completedFuture(null)

    override fun exit() {}

    override fun getTextDocumentService(): TextDocumentService = textDocuments

    override fun getWorkspaceService(): WorkspaceService = workspace

    override fun connect(client: LanguageClient) {
        textDocuments.connect(client)
    }
}
