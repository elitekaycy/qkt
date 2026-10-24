package com.qkt.backtest

/**
 * Lets a book sampler take one sample per candle boundary. Each symbol closing a bar publishes its own candle event,
 * so a boundary where N symbols close arrives N times; sampling each would add N-1 zero returns at the same instant,
 * diluting the return variance N-fold and counting rebalance cadence in symbol-candles instead of bars.
 * Boundaries arrive in time order, so remembering the last one is enough.
 */
class OncePerBoundary {
    private var lastEndMs = Long.MIN_VALUE

    /** True the first time [endMs] is seen, false for every later candle closing at the same boundary. */
    @Synchronized
    fun first(endMs: Long): Boolean {
        if (endMs == lastEndMs) return false
        lastEndMs = endMs
        return true
    }
}
