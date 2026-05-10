package com.qkt.cli.daemon.logging

import ch.qos.logback.classic.sift.MDCBasedDiscriminator
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Substitutes `/` with `__` in the strategy MDC value so per-strategy log file names
 * are filesystem-safe for child names like `mybook/trend`. Mirrors `StateDir.logFile`.
 */
class StrategyFilenameDiscriminator : MDCBasedDiscriminator() {
    init {
        key = "strategy_filename"
        defaultValue = "main"
    }

    override fun getDiscriminatingValue(e: ILoggingEvent): String {
        val raw = e.mdcPropertyMap?.get("strategy") ?: return defaultValue
        return raw.replace("/", "__")
    }
}
