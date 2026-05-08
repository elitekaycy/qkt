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
        val ONE_DAY = TimeWindow(86_400_000L)

        fun parse(spec: String): TimeWindow {
            require(spec.length >= 2) { "TimeWindow spec must be like '1m', '5m', '1h': '$spec'" }
            val unit = spec.last()
            val n =
                spec.dropLast(1).toLongOrNull()
                    ?: error("TimeWindow spec must start with a positive integer: '$spec'")
            require(n > 0) { "TimeWindow count must be > 0: '$spec'" }
            val unitMs: Long =
                when (unit) {
                    's' -> 1_000L
                    'm' -> 60_000L
                    'h' -> 3_600_000L
                    'd' -> 86_400_000L
                    else -> error("Unknown TimeWindow unit '$unit' in '$spec'; expected s/m/h/d")
                }
            return TimeWindow(n * unitMs)
        }
    }

    fun windowStartFor(timestamp: Long): Long = (timestamp / durationMs) * durationMs

    fun windowEndFor(timestamp: Long): Long = windowStartFor(timestamp) + durationMs
}
