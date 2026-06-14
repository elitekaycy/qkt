package com.qkt.lsp

import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.junit.jupiter.api.Test

class QktLanguageServerTest {
    @Test
    fun `initialize advertises full sync, completion with triggers, and hover`() {
        val server = QktLanguageServer()
        val caps = server.initialize(InitializeParams()).get().capabilities
        assertThat(caps.textDocumentSync.left).isEqualTo(TextDocumentSyncKind.Full)
        assertThat(caps.completionProvider).isNotNull()
        assertThat(caps.completionProvider.triggerCharacters).contains(".", "(")
        assertThat(caps.hoverProvider.left).isTrue()
    }

    @Test
    fun `shutdown completes with null result`() {
        val server = QktLanguageServer()
        assertThat(server.shutdown().get()).isNull()
    }
}
