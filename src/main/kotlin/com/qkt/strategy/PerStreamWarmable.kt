package com.qkt.strategy

import com.qkt.candles.TimeWindow

/** Identifies one warmup stream without collapsing same-symbol, different-window declarations. */
data class WarmupStream(
    /** Fully qualified qkt symbol, for example `EXNESS:XAUUSD`. */
    val symbol: String,
    /** Exact candle window whose historical bars satisfy this stream. */
    val window: TimeWindow,
) {
    init {
        require(symbol.isNotBlank()) { "WarmupStream.symbol must not be blank" }
    }
}

/**
 * Per-stream warmup spec — sibling to [Warmable] for strategies that need
 * different warmup windows on different streams (e.g. 1m gold + 1h gold).
 *
 * The single-spec [Warmable] interface stays as a legacy fallback for non-DSL
 * strategies. Callers should prefer [PerStreamWarmable] when both are available.
 */
interface PerStreamWarmable {
    /** Map from exact symbol/window stream identity to its required warmup spec. */
    val perStreamWarmup: Map<WarmupStream, WarmupSpec>
}
