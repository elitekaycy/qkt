package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.StrategyPositionView

data class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val positions: StrategyPositionView,
    val pnl: StrategyPnLView,
)
