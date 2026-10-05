package com.qkt.persistence

import java.math.BigDecimal

/**
 * On-disk shape of a strategy's lifetime PnL: the cumulative realized amount since
 * the strategy first deployed (not the daily figure — that lives in [PersistedRiskState]).
 */
data class PersistedPnl(
    val realized: BigDecimal,
)

/** On-disk shape of one non-zero closed-trade outcome used by streak accessors. */
data class PersistedTradeOutcome(
    val timestamp: Long,
    val pnl: BigDecimal,
    val symbol: String,
)

/** On-disk shape of the bounded closed-trade outcome buffer for one strategy. */
data class PersistedTradeHistory(
    val outcomes: List<PersistedTradeOutcome>,
)
