package com.qkt.accounting

/** The kind of a trading cost, so reports can break total costs down by cause. */
enum class CostKind {
    COMMISSION,
    SWAP,
    FUNDING,
    BORROW,
    EXCHANGE_FEE,
    SPREAD_COST,
    TAX,
}

/** One cost a venue charged (or a backtest modelled), in its own currency, at [timestamp]. */
data class VenueCost(
    val kind: CostKind,
    val amount: MoneyAmount,
    val timestamp: Long,
)
