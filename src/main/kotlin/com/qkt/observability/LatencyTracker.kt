package com.qkt.observability

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-light, bounded-memory tracker for hot-path latency observations.
 *
 * The trading pipeline produces one observation per tick × strategy × stage.
 * At a few-thousand-ticks-per-second rate, allocating a list or taking a lock per
 * sample would dominate the cost of measuring. [LatencyTracker] holds a
 * fixed-size [AtomicLongArray] ring buffer of nanosecond observations and an
 * [AtomicInteger] write cursor — `observe(...)` is allocation-free and
 * lock-free.
 *
 * Percentiles in [snapshot] are computed by copying the buffer into a local
 * `LongArray`, sorting in-place, and reading positional values. That is O(n log n)
 * per snapshot, but snapshots are called from the operator-facing reporting path
 * (every few seconds) — never on the hot path itself.
 *
 * **Integration hook (deferred to issue follow-up):** the TradingPipeline will
 * create one tracker per `(strategyId, stage)` and call `observe(nanos)` after
 * each stage completes. Stages: `tick_to_candle_close`, `candle_close_to_signal`,
 * `signal_to_submission`, `submission_to_fill_ack`. The integration is gated by
 * a feature flag so the default pipeline path stays untouched until operators
 * opt in.
 */
class LatencyTracker(
    val capacity: Int = 1024,
) {
    init {
        require(capacity > 0) { "LatencyTracker capacity must be positive: $capacity" }
    }

    private val ring = AtomicLongArray(capacity)
    private val cursor = AtomicInteger(0)
    private val written = AtomicInteger(0)

    /** Record one observation in nanoseconds. Lock-free; safe to call from any thread. */
    fun observe(nanos: Long) {
        require(nanos >= 0) { "LatencyTracker.observe nanos must be >= 0: $nanos" }
        val idx = cursor.getAndIncrement()
        ring.set(idx % capacity, nanos)
        if (written.get() < capacity) {
            written.incrementAndGet()
        }
    }

    /** Number of observations currently in the ring (≤ [capacity]). */
    fun count(): Int = minOf(written.get(), capacity)

    /**
     * Snapshot of the current ring: count + p50/p95/p99/max in nanoseconds. Returns
     * a `Snapshot` with all zeroes when no observations have landed yet. Safe to
     * call from any thread; the snapshot is point-in-time and may interleave with
     * concurrent [observe] calls (a sample written during snapshotting may or may
     * not be included).
     */
    fun snapshot(): Snapshot {
        val n = count()
        if (n == 0) return Snapshot(count = 0, p50Nanos = 0, p95Nanos = 0, p99Nanos = 0, maxNanos = 0)
        val copy = LongArray(n) { ring.get(it) }
        copy.sort()
        return Snapshot(
            count = n,
            p50Nanos = copy.percentile(50),
            p95Nanos = copy.percentile(95),
            p99Nanos = copy.percentile(99),
            maxNanos = copy[n - 1],
        )
    }

    /** Clear all observations. Mainly useful in tests. */
    fun reset() {
        for (i in 0 until capacity) ring.set(i, 0)
        cursor.set(0)
        written.set(0)
    }

    private fun LongArray.percentile(pct: Int): Long {
        if (isEmpty()) return 0
        val idx = ((pct / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[idx]
    }

    data class Snapshot(
        val count: Int,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val p99Nanos: Long,
        val maxNanos: Long,
    ) {
        fun p50Micros(): Long = p50Nanos / 1_000

        fun p95Micros(): Long = p95Nanos / 1_000

        fun p99Micros(): Long = p99Nanos / 1_000

        fun maxMicros(): Long = maxNanos / 1_000
    }
}

/**
 * Inline helper: time a block and record into [tracker]. Returns the block's
 * result. Zero allocation when used at a call site that takes a lambda.
 */
inline fun <T> LatencyTracker.time(block: () -> T): T {
    val t0 = System.nanoTime()
    val r = block()
    observe(System.nanoTime() - t0)
    return r
}
