package com.qkt.cli

import com.qkt.lsp.QktLanguageServer
import org.eclipse.lsp4j.launch.LSPLauncher

/**
 * `qkt lsp` — run the qkt Language Server, speaking the Language Server Protocol over
 * stdin/stdout. Editors launch this and get diagnostics, completion, and hover for
 * `.qkt` files.
 *
 * stdout is the protocol channel, so it must carry only LSP messages. We hand the real
 * stdout to the launcher and repoint [System.out] at stderr, so a stray `print` or a
 * log line anywhere else in the process cannot corrupt the stream.
 */
class LspCommand {
    fun run(): Int {
        val stdin = System.`in`
        val protocolOut = System.out
        System.setOut(System.err)

        val server = QktLanguageServer()
        val launcher = LSPLauncher.createServerLauncher(server, stdin, protocolOut)
        server.connect(launcher.remoteProxy)
        launcher.startListening().get()
        return ExitCodes.SUCCESS
    }
}
