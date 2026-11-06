package com.qkt.connector.gateway

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger

/**
 * Warnings about one thing (an order being retried) logged to [log] at most once per [everyMs], the next one
 * saying how many were held back since, so a retry loop paced against a rate limit cannot flood the log.
 */
internal class PacedWarnings(
    private val log: Logger,
    private val everyMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(
        var lastMs: Long = -1,
        var held: Int = 0,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Logs [format] with [args] for [key] unless one was logged within [everyMs]; then counts it held back. */
    fun warn(
        key: String,
        format: String,
        vararg args: Any?,
    ) {
        val entry = entries.computeIfAbsent(key) { Entry() }
        val held =
            synchronized(entry) {
                val t = now()
                if (entry.lastMs >= 0 && t - entry.lastMs < everyMs) {
                    entry.held++
                    return
                }
                entry.lastMs = t
                entry.held.also { entry.held = 0 }
            }
        if (held == 0) log.warn(format, *args) else log.warn("$format ({} more like it held back)", *args, held)
    }

    /** Forgets [key], once what was being retried is done. */
    fun forget(key: String) {
        entries.remove(key)
    }
}
