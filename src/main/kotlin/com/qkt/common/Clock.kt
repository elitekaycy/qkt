package com.qkt.common

interface Clock {
    fun now(): Long
}

/**
 * A clock whose event-time is driven by the data being processed, not the wall clock — so a
 * deterministic run (backtest, or live-paper parity) advances "now" to each event's timestamp as
 * the engine processes it. Real venues use [SystemClock] (wall time) and are NOT mutable, so the
 * live engine's `advanceTo` is a no-op there.
 */
interface MutableClock : Clock {
    fun advanceTo(timestampMs: Long)
}

class SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

class FixedClock(
    var time: Long = 0L,
) : MutableClock {
    override fun now(): Long = time

    override fun advanceTo(timestampMs: Long) {
        time = timestampMs
    }
}
