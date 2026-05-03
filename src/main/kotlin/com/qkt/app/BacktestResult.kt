package com.qkt.app

import com.qkt.events.RiskRejectedEvent
import com.qkt.positions.Position
import java.math.BigDecimal

data class BacktestResult(
    val trades: List<TradeRecord>,
    val rejections: List<RiskRejectedEvent>,
    val finalPositions: Map<String, Position>,
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    val winRate: BigDecimal,
    val maxDrawdown: BigDecimal,
)
