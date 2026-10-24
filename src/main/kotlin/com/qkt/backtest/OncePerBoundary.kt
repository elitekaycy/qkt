package com.qkt.backtest

/**
 * Lets a book sampler take one sample per candle boundary. Each symbol closing a bar publishes its own candle event,
 * so a boundary where N symbols close arrives N times; sampling each would add N-1 zero returns at the same instant,
 * diluting the return variance N-fold and counting rebalance cadence in symbol-candles instead of bars.
 * A boundary at or before one already sampled is dropped too: live, a sparse symbol can close an older bar after a
 * newer boundary was sampled, and a sample stamped back in time would be one more near-zero return.
 */
class OncePerBoundary {
    private var lastEndMs = Long.MIN_VALUE

    /** True when [endMs] is later than every boundary seen so far, false for a repeat or an older one. */
    @Synchronized
    fun first(endMs: Long): Boolean {
        if (endMs <= lastEndMs) return false
        lastEndMs = endMs
        return true
    }
}
