package com.qkt.backtest

import com.qkt.execution.Trade
import java.math.BigDecimal

data class TradeRecord(
    val trade: Trade,
    val realized: BigDecimal,
    val strategyId: String,
)
