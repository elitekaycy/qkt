package com.qkt.lsp

import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Closing a document forgets it and clears its diagnostics; a change event with no content is ignored. */
class QktTextDocumentServiceCloseTest {
    private val published = mutableListOf<PublishDiagnosticsParams>()
    private val client =
        object : LanguageClient {
            override fun telemetryEvent(any: Any?) {}

            override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                published += params
            }

            override fun showMessage(params: MessageParams?) {}

            override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
                CompletableFuture.completedFuture(null)

            override fun logMessage(params: MessageParams?) {}
        }
    private val documents = DocumentStore()
    private val service = QktTextDocumentService(documents).also { it.connect(client) }
    private val uri = "file:///s.qkt"

    @AfterEach
    fun stop() = service.shutdown()

    @Test
    fun `closing a document drops its text and publishes empty diagnostics`() {
        documents.put(uri, "STRATEGY s VERSION 1", null)

        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

        assertThat(documents.text(uri)).isNull()
        assertThat(published.single().uri).isEqualTo(uri)
        assertThat(published.single().diagnostics).isEmpty()
    }

    @Test
    fun `a change event without content leaves the stored text alone`() {
        documents.put(uri, "STRATEGY s VERSION 1", null)

        service.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2), emptyList()))

        assertThat(documents.text(uri)).isEqualTo("STRATEGY s VERSION 1")
        assertThat(published).isEmpty()
    }
}
