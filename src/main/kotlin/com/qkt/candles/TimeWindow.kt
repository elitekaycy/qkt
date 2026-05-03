package com.qkt.candles

@JvmInline
value class TimeWindow(
    val durationMs: Long,
) {
    init {
        require(durationMs > 0L) { "durationMs must be > 0: $durationMs" }
    }

    companion object {
        val ONE_SECOND = TimeWindow(1_000L)
        val ONE_MINUTE = TimeWindow(60_000L)
        val FIVE_MINUTES = TimeWindow(300_000L)
        val FIFTEEN_MINUTES = TimeWindow(900_000L)
        val ONE_HOUR = TimeWindow(3_600_000L)
    }

    fun windowStartFor(timestamp: Long): Long = (timestamp / durationMs) * durationMs

    fun windowEndFor(timestamp: Long): Long = windowStartFor(timestamp) + durationMs
}
