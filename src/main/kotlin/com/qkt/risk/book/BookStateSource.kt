package com.qkt.risk.book

/**
 * Supplies aggregated [BookSnapshot]s to the book-risk layer. The only mode-specific seam: backtest
 * reads one engine's global state; live sums the child sessions. Both yield the same snapshot shape,
 * so the controller logic above is identical in both paths (Backtest=Live).
 */
interface BookStateSource {
    fun sample(timestampMs: Long): BookSnapshot
}
