package com.qkt.backtest

import com.qkt.execution.Trade
import com.qkt.positions.Position
import java.math.BigDecimal

data class TradeRecord(
    val trade: Trade,
    val realized: BigDecimal,
    val strategyId: String,
    val orderType: String? = null,
    val riskUsd: BigDecimal? = null,
    val stopLossPrice: BigDecimal? = null,
    val takeProfitPrice: BigDecimal? = null,
    val nativeRealized: BigDecimal? = null,
    val nativeCurrency: String? = null,
    /** Gross converted account-currency PnL before modeled and venue-reported fill costs. */
    val accountRealized: BigDecimal? = null,
    val accountCurrency: String? = null,
    val fxRate: BigDecimal? = null,
    val fxRateTimestamp: Long? = null,
    val fxSource: String? = null,
    val accountPositionBefore: Position? = null,
    val accountPositionAfter: Position? = null,
    val strategyPositionBefore: Position? = null,
    val strategyPositionAfter: Position? = null,
    val contractSize: BigDecimal? = null,
    /** True when the fill reduced strategy-owned exposure and therefore represents an exit outcome. */
    val reducedExposure: Boolean = true,
)
