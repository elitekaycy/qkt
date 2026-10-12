package com.qkt.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

/**
 * Quiets engine INFO logs for the default `qkt backtest` view (#1370): the replay's per-order
 * chatter buries the result, while the result itself needs none of it. Warnings and errors still
 * print; `--verbose`/`--debug` keep today's full log. Returns a restore function — callers run it
 * in `finally`, since commands share the JVM with tests.
 */
internal object QuietBacktestLogs {
    fun silenceUnless(
        verbose: Boolean,
        debug: Boolean,
    ): () -> Unit {
        if (verbose || debug) return {}
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val previous = root.level
        root.level = Level.WARN
        return { root.level = previous }
    }
}
