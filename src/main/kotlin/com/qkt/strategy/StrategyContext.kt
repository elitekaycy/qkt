package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.StrategyPnLView
import com.qkt.positions.StrategyPositionView
import com.qkt.risk.RiskView

/**
 * Read-only environment passed to every [Strategy] callback.
 *
 * Carries the injected [Clock] (so time access is deterministic), the trading
 * [TradingCalendar] (so session-aware logic works), and read-only views of position,
 * P&L, and risk state — strategies can inspect their own state but cannot mutate it
 * directly; mutation happens by emitting signals.
 */
data class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val positions: StrategyPositionView,
    val pnl: StrategyPnLView,
    val risk: RiskView,
)
