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
    Thread { shutdownAfterGrace(shutdown) }.apply {
        isDaemon = true
        start()
    }
}

/** Waits [grace] so the response can flush, then runs [shutdown]; an interrupt ends the wait early, not the shutdown. */
internal fun shutdownAfterGrace(
    shutdown: () -> Unit,
    grace: () -> Unit = { Thread.sleep(50) },
) {
    try {
        grace()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    runCatching { shutdown() }
}
