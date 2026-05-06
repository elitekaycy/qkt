package com.qkt.common.net

interface BackoffPolicy {
    fun nextDelayMs(attempt: Int): Long
}

class ExponentialBackoff(
    private val initialMs: Long = 1_000L,
    private val capMs: Long = 60_000L,
    private val multiplier: Double = 2.0,
) : BackoffPolicy {
    init {
        require(initialMs > 0L) { "initialMs must be > 0: $initialMs" }
        require(capMs > 0L) { "capMs must be > 0: $capMs" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0: $multiplier" }
    }

    override fun nextDelayMs(attempt: Int): Long {
        val raw = initialMs.toDouble() * Math.pow(multiplier, (attempt - 1).toDouble())
        return raw.toLong().coerceAtMost(capMs).coerceAtLeast(initialMs)
    }
}

class FixedDelayBackoff(
    private val delayMs: Long,
) : BackoffPolicy {
    init {
        require(delayMs > 0L) { "delayMs must be > 0: $delayMs" }
    }

    override fun nextDelayMs(attempt: Int): Long = delayMs
}
