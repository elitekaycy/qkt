package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.NoOpTradeHistoryView
import com.qkt.pnl.StrategyPnLView
import com.qkt.pnl.TradeHistoryView
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
    /**
     * Per-instrument venue metadata (contract size, lot/price steps, stops level). Phase 30.
     *
     * Default [NoopInstrumentRegistry] returns null for every lookup — test code that
     * doesn't exercise `SIZING RISK`-style sizing or contract-size-aware PnL math can
     * keep ignoring this field. Production strategy loads wire a real registry
     * (`MT5InstrumentRegistry` live; `YamlInstrumentRegistry` backtest).
     */
    val instruments: InstrumentRegistry = NoopInstrumentRegistry,
    /**
     * Phase 25-followup ([#132](https://github.com/elitekaycy/qkt/issues/132)):
     * per-strategy trade history (last fill timestamp, last realized P&L, win/loss
     * streaks). Exposed to DSL via `ACCOUNT.last_trade_at`, `ACCOUNT.win_streak`, etc.
     *
     * Default [NoOpTradeHistoryView] keeps test code that doesn't exercise these
     * accessors working unchanged.
     */
    val tradeHistory: TradeHistoryView = NoOpTradeHistoryView(),
)
