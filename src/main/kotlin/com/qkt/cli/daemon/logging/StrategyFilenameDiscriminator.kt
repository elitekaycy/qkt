package com.qkt.cli.daemon.logging

import ch.qos.logback.classic.sift.MDCBasedDiscriminator
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Substitutes portfolio separators with `__` in the strategy MDC value so canonical
 * child ids such as `mybook:trend` map to the local file used by `StateDir.logFile`.
 */
class StrategyFilenameDiscriminator : MDCBasedDiscriminator() {
    init {
        key = "strategy_filename"
        defaultValue = "main"
    }

    override fun getDiscriminatingValue(e: ILoggingEvent): String {
        val raw = e.mdcPropertyMap?.get("strategy") ?: return defaultValue
        return raw.replace("/", "__").replace(":", "__")
    }
}
