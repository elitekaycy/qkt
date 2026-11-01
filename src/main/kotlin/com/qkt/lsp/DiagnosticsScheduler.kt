package com.qkt.lsp

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Coalesces document analysis so a burst of keystrokes costs one parse and compile instead of one
 * per keystroke.
 *
 * [schedule] replaces any analysis still pending for the same document and runs [analyze] once the
 * document has been quiet for [quietMs]. All analysis runs on one daemon thread, so the server
 * never uses more than one core for diagnostics however fast the user types.
 */
class DiagnosticsScheduler(
    private val quietMs: Long = DEFAULT_QUIET_MS,
    private val executor: ScheduledExecutorService = singleDaemonThread(),
) {
    private val pending = mutableMapOf<String, ScheduledFuture<*>>()

    /** Runs [analyze] for [uri] after [delayMs] of quiet, cancelling the previous pending run. */
    fun schedule(
        uri: String,
        delayMs: Long = quietMs,
        analyze: () -> Unit,
    ) {
        synchronized(pending) {
            pending.remove(uri)?.cancel(false)
            pending[uri] = executor.schedule(analyze, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Drops any analysis still pending for [uri]. */
    fun cancel(uri: String) {
        synchronized(pending) { pending.remove(uri)?.cancel(false) }
    }

    /** Stops the analysis thread; pending runs are dropped. */
    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        /** Quiet period before a changed document is re-analysed. */
        const val DEFAULT_QUIET_MS = 200L

        private fun singleDaemonThread(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "qkt-lsp-diagnostics").apply { isDaemon = true }
            }
    }
}
