package com.qkt.cli.daemon

import org.slf4j.LoggerFactory

/**
 * The daemon's own log, for lines written as `[WARN] ...`: they reach the daemon log file (`logs/main.log` in the
 * state directory) and stderr, where a bare `System.err` line reaches only stderr and is gone with the container
 * (#1642).
 */
internal object DaemonLog {
    private val log = LoggerFactory.getLogger("com.qkt.daemon")

    /** Logs [line] at the level its `[ERROR]`/`[WARN]`/`[INFO]` prefix names (INFO without one), prefix removed. */
    fun line(line: String) {
        when {
            line.startsWith("[ERROR] ") -> log.error(line.removePrefix("[ERROR] "))
            line.startsWith("[WARN] ") -> log.warn(line.removePrefix("[WARN] "))
            else -> log.info(line.removePrefix("[INFO] "))
        }
    }
}
