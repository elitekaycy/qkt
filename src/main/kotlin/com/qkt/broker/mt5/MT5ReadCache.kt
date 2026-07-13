package com.qkt.broker.mt5

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived success cache and single-flight gate for shared MT5 client reads.
 *
 * A daemon may host many strategy-local brokers against one terminal. Their pollers need
 * independent state machines, but identical venue snapshots do not need independent HTTP calls.
 */
class MT5ReadCache(
    ttlMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Entry(
        val loadedAtNanos: Long,
        val value: String,
    )

    private val ttlNanos = ttlMs.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val generation = AtomicLong()

    /** Returns a fresh cached value or loads one while coalescing concurrent misses for [key]. */
    fun get(
        key: String,
        loader: () -> String?,
    ): String? {
        fresh(key, nanoTime())?.let { return it }
        val lock = locks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            fresh(key, nanoTime())?.let { return@synchronized it }
            val loadGeneration = generation.get()
            val loaded = loader() ?: return@synchronized null
            if (generation.get() == loadGeneration) {
                entries[key] = Entry(nanoTime(), loaded)
            }
            loaded
        }
    }

    /** Invalidates every snapshot, including reads that were already in flight. */
    fun clear() {
        generation.incrementAndGet()
        entries.clear()
    }

    private fun fresh(
        key: String,
        nowNanos: Long,
    ): String? {
        val entry = entries[key] ?: return null
        val age = nowNanos - entry.loadedAtNanos
        return entry.value.takeIf { age >= 0L && age <= ttlNanos }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    }
}
