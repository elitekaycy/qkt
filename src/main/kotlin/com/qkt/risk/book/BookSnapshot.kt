package com.qkt.risk.book

import java.math.BigDecimal

/**
 * Aggregated book state at one sample instant — the input the book-risk layer reasons over.
 *
 * @property bookEquity capital + Σ child realized + Σ child unrealized.
 * @property exposure gross/net/per-symbol notional across all strategies.
 * @property perStrategyPnl each strategy's total (realized + unrealized) PnL, for the return series.
 */
data class BookSnapshot(
    val timestampMs: Long,
    val bookEquity: BigDecimal,
    val exposure: Exposure,
    val perStrategyPnl: Map<String, BigDecimal>,
)
