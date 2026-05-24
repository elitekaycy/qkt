package com.qkt.cli.daemon.logging

import org.slf4j.MDC

/**
 * Run [block] with [key] temporarily set to [value] in the SLF4J MDC, then restore the
 * key's prior value (or remove it if it was absent before).
 *
 * Bus subscribers and per-strategy callbacks (`onTrade`, `onSignal`) often need to attach
 * an MDC key so logs emitted inside the callback route through the per-strategy sift
 * appender. A naive `MDC.put` + `MDC.remove(key)` in a `finally` block has a subtle
 * regression: when the callback runs on a thread that already holds an outer MDC value
 * for the same key (e.g. the `qkt-live-engine` thread populated by [com.qkt.app.LiveSession]),
 * the `remove` wipes the outer value too. Subsequent log calls on that thread fall back
 * to the discriminator's default and the per-strategy log file goes silent. This helper
 * restores whatever was there before, preserving the outer scope.
 */
inline fun <R> withMdc(
    key: String,
    value: String,
    block: () -> R,
): R {
    val prev = MDC.get(key)
    MDC.put(key, value)
    try {
        return block()
    } finally {
        if (prev == null) MDC.remove(key) else MDC.put(key, prev)
    }
}
