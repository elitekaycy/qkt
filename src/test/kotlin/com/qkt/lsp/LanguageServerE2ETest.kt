package com.qkt.lsp

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Test

/**
 * Drives the real server through a full Language Server Protocol session over in-memory
 * pipes using actual JSON-RPC — no mocks. Proves the wiring an editor relies on:
 * initialize, didOpen (diagnostics pushed back), completion, hover, then didChange
 * re-publishing diagnostics. Kept untagged so it runs in the default `./gradlew test`.
 */
class LanguageServerE2ETest {
    private class RecordingClient : LanguageClient {
        val diagnostics = LinkedBlockingQueue<PublishDiagnosticsParams>()

        override fun telemetryEvent(any: Any?) {}

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            diagnostics.add(params)
        }

        override fun showMessage(params: MessageParams?) {}

        override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(params: MessageParams?) {}
    }

    private val validSource =
        """
        STRATEGY s VERSION 1

        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m

        RULES
            WHEN btc.close > 100
            THEN BUY btc SIZING 1
        """.trimIndent()

    private val brokenSource = validSource.replace("RULES", "RULE")

    @Test
    fun `full session yields diagnostics, completion, and hover over real json-rpc`() {
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient)

        val server = QktLanguageServer()
        val serverLauncher = LSPLauncher.createServerLauncher(server, serverIn, serverToClient)
        server.connect(serverLauncher.remoteProxy)

        val client = RecordingClient()
        val clientLauncher = LSPLauncher.createClientLauncher(client, clientIn, clientToServer)

        serverLauncher.startListening()
        clientLauncher.startListening()
        val proxy = clientLauncher.remoteProxy

        try {
            proxy.initialize(InitializeParams()).get(5, TimeUnit.SECONDS)
            proxy.initialized(InitializedParams())

            val uri = "file:///mem/strategy.qkt"
            proxy.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "qkt", 1, validSource)),
            )

            val onOpen = client.diagnostics.poll(5, TimeUnit.SECONDS)
            assertThat(onOpen).isNotNull()
            assertThat(onOpen.uri).isEqualTo(uri)
            assertThat(onOpen.diagnostics).isEmpty()

            // Expression position at the start of `btc` on the eighth line (0-based line 6).
            val completion =
                proxy.textDocumentService
                    .completion(CompletionParams(TextDocumentIdentifier(uri), Position(6, 9)))
                    .get(5, TimeUnit.SECONDS)
            val labels = completion.left.map { it.label }
            assertThat(labels).contains("ema", "WHEN", "btc")

            val hover =
                proxy.textDocumentService
                    .hover(HoverParams(TextDocumentIdentifier(uri), Position(6, 9)))
                    .get(5, TimeUnit.SECONDS)
            assertThat(hover.contents.right.value).contains("BTCUSDT")

            proxy.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent(brokenSource)),
                ),
            )

            val onChange = client.diagnostics.poll(5, TimeUnit.SECONDS)
            assertThat(onChange).isNotNull()
            assertThat(onChange.diagnostics).isNotEmpty()
            assertThat(onChange.diagnostics.first().source).isEqualTo("qkt")

            proxy.shutdown().get(5, TimeUnit.SECONDS)
            proxy.exit()
        } finally {
            // Close only the writer ends. Each reader then drains and sees a clean EOF and its
            // listener thread exits; closing the reader ends instead would interrupt the blocked
            // reads with a spurious "Pipe closed" IOException.
            clientToServer.close()
            serverToClient.close()
        }
    }
}
