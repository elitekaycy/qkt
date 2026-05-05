package com.qkt.strategy

import com.qkt.candles.TimeWindow
import java.time.Duration
import java.time.Instant

sealed class WarmupSpec {
    object None : WarmupSpec()

    data class Bars(
        val window: TimeWindow,
        val count: Int,
    ) : WarmupSpec() {
        init {
            require(count > 0) { "WarmupSpec.Bars count must be > 0: $count" }
        }
    }

    data class Duration(
        val window: TimeWindow,
        val duration: java.time.Duration,
    ) : WarmupSpec() {
        init {
            require(!duration.isZero && !duration.isNegative) {
                "WarmupSpec.Duration must be positive: $duration"
            }
        }
    }

    data class Ticks(
        val duration: java.time.Duration,
    ) : WarmupSpec() {
        init {
            require(!duration.isZero && !duration.isNegative) {
                "WarmupSpec.Ticks duration must be positive: $duration"
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
fun WarmupSpec.windowMs(now: Instant): Long =
    when (this) {
        is WarmupSpec.None -> 0L
        is WarmupSpec.Bars -> window.durationMs * count
        is WarmupSpec.Duration -> duration.toMillis()
        is WarmupSpec.Ticks -> duration.toMillis()
    }
