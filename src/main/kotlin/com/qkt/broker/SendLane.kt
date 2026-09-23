package com.qkt.broker

/**
 * The simulated venue's single order-send lane. A live MT5 gateway fronts one terminal and
 * places orders one after another, so a burst of market orders reaches the venue spread out
 * in time (measured on the Exness demo: ~140-160 ms between consecutive burst legs) and each
 * leg fills at a different quote.
 *
 * An order submitted at `now` is released at `now + latencyMs`, or at the previous release
 * plus [spacingMs] when that is later. With [spacingMs] = 0 every order is released at
 * `now + latencyMs` independently, which is the simulator's historical behavior.
 * Deterministic: it reads only the times it is given.
 */
internal class SendLane(
    private val latencyMs: Long,
    private val spacingMs: Long,
) {
    private var lastReleaseAt: Long? = null

    init {
        require(latencyMs >= 0L) { "latencyMs must be >= 0: $latencyMs" }
        require(spacingMs >= 0L) { "orderSpacingMs must be >= 0: $spacingMs" }
    }

    /** Reserves the lane for an order submitted at [now] and returns when it reaches the venue. */
    fun releaseAt(now: Long): Long {
        val earliest = now + latencyMs
        val previous = lastReleaseAt
        val release = if (spacingMs > 0L && previous != null) maxOf(earliest, previous + spacingMs) else earliest
        lastReleaseAt = release
        return release
    }
}
