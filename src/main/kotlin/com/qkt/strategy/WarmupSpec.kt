package com.qkt.strategy

import com.qkt.candles.TimeWindow
import java.time.Duration
import java.time.Instant

/**
 * How much historical data an indicator needs before it produces meaningful values.
 *
 * The warmer pre-feeds ticks/candles into the strategy before live signal evaluation
 * begins. [None] disables warmup; [Bars]/[Duration]/[Ticks] each express the warmup
 * window differently — pick whichever matches how you reason about the indicator.
 */
sealed class WarmupSpec {
    /** No warmup needed — the strategy is ready on the first live tick. */
    object None : WarmupSpec()

    /** Warm by N closed candles of size [window]. */
    data class Bars(
        val window: TimeWindow,
        val count: Int,
    ) : WarmupSpec() {
        init {
            require(count > 0) { "WarmupSpec.Bars count must be > 0: $count" }
        }
    }

    /** Warm by a wall-clock [duration] of candles aggregated over [window]. */
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

    /** Warm by a wall-clock [duration] of raw ticks — no candle aggregation. */
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

/** Returns the warmup window in milliseconds. `now` is reserved for future calendar-aware modes. */
@Suppress("UNUSED_PARAMETER")
fun WarmupSpec.windowMs(now: Instant): Long =
    when (this) {
        is WarmupSpec.None -> 0L
        is WarmupSpec.Bars -> window.durationMs * count
        is WarmupSpec.Duration -> duration.toMillis()
        is WarmupSpec.Ticks -> duration.toMillis()
    }
