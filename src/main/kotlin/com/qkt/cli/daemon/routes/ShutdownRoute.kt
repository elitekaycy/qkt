package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.sun.net.httpserver.HttpExchange

/**
 * `POST /shutdown` — answers 202, journals the request, then shuts the daemon down on
 * a background thread so the response can flush first.
 */
internal fun handleShutdown(
    ex: HttpExchange,
    stateDir: StateDir?,
    shutdown: () -> Unit,
) {
    respond(ex, 202, """{"status":"accepted"}""")
    OperatorJournal
        .from(stateDir, "http")
        ?.record("shutdown", target = "_daemon", affected = emptyList())
    // Trigger asynchronously so the response can flush before the server closes.
    Thread {
        try {
            Thread.sleep(50)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        runCatching { shutdown() }
    }.apply {
        isDaemon = true
        start()
    }
}
