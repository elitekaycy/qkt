package com.qkt.backtest

import com.qkt.execution.Trade
import java.math.BigDecimal

data class TradeRecord(
    val trade: Trade,
    val realized: BigDecimal,
    val strategyId: String,
    val riskUsd: BigDecimal? = null,
    val nativeRealized: BigDecimal? = null,
    val nativeCurrency: String? = null,
    val accountRealized: BigDecimal? = null,
    val accountCurrency: String? = null,
    val fxRate: BigDecimal? = null,
    val fxRateTimestamp: Long? = null,
    val fxSource: String? = null,
)
