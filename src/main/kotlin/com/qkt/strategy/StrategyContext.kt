package com.qkt.strategy

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.BookBalanceView
import com.qkt.pnl.NoOpTradeHistoryView
import com.qkt.pnl.StrategyPnLView
import com.qkt.pnl.TradeHistoryView
import com.qkt.positions.StrategyPositionView
import com.qkt.risk.NoOpPacerView
import com.qkt.risk.PacerView
import com.qkt.risk.RiskView
import java.math.BigDecimal

/** Supplies the account-currency value of one unit of an instrument's quote currency. */
fun interface QuoteToAccountRateProvider {
    /** Returns a positive conversion rate for [symbol] at the deterministic [timestamp]. */
    fun rate(
        symbol: String,
        timestamp: Long,
        referencePrice: BigDecimal,
    ): BigDecimal

    companion object {
        /** Identity conversion used by contexts whose instruments are account-quoted. */
        val IDENTITY: QuoteToAccountRateProvider = QuoteToAccountRateProvider { _, _, _ -> BigDecimal.ONE }
    }
}

/**
 * Read-only view of active, risk-increasing entry orders owned by one strategy.
 *
 * Counts are symbol-scoped and exclude protective or otherwise risk-reducing exits.
 * The order manager supplies the lifecycle semantics, including cancellation and expiry.
 */
fun interface OpenOrderView {
    /** Returns the active entry-order count for [symbol]. */
    fun entryCountFor(symbol: String): Int

    companion object {
        /** Empty view used by contexts that are not attached to a trading pipeline. */
        val EMPTY: OpenOrderView = OpenOrderView { 0 }
    }
}

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
    /** Quote-to-account conversion used by every account-money sizing mode. */
    val quoteToAccountRate: QuoteToAccountRateProvider = QuoteToAccountRateProvider.IDENTITY,
    /**
     * Phase 25-followup ([#132](https://github.com/elitekaycy/qkt/issues/132)):
     * per-strategy trade history (last fill timestamp, last realized P&L, win/loss
     * streaks). Exposed to DSL via `ACCOUNT.last_trade_at`, `ACCOUNT.win_streak`, etc.
     *
     * Default [NoOpTradeHistoryView] keeps test code that doesn't exercise these
     * accessors working unchanged.
     */
    val tradeHistory: TradeHistoryView = NoOpTradeHistoryView(),
    /** Per-strategy pacing state exposed to DSL via `TRADES.*` and `COOLDOWN.*`. */
    val pacer: PacerView = NoOpPacerView(),
    /** Active entry-order state exposed to DSL via `OPEN_ORDERS.<stream>`. */
    val openOrders: OpenOrderView = OpenOrderView.EMPTY,
    /**
     * Balance of the portfolio book this strategy trades inside (CAPITAL + realized PnL
     * of every child); null outside a portfolio deploy. Read by `SIZING … RISK OF BOOK`.
     */
    val book: BookBalanceView? = null,
    /** Written by the pipeline as it processes each emitted signal; read by rule edge gating. */
    val submissions: SubmissionOutcomes = SubmissionOutcomes(),
)

/**
 * Running counts of how the engine disposed of this strategy's emitted signals.
 *
 * The pipeline bumps [accepted] for every signal that clears all pre-trade gates (or
 * needs none, like a cancel) and [suppressed] for every signal it drops before the
 * venue — portfolio gate inactive, book de-risk, risk reject, unpriceable. Rule edge
 * gating compares the counters around a fire: when the fire produced only suppressions
 * it re-arms the edge so the entry retries next bar instead of being silently lost.
 * Consumers that record nothing (tests, custom harnesses) keep plain edge semantics.
 * e.g. rule fires → risk engine rejects → suppressed++ → rule fires again next bar.
 */
class SubmissionOutcomes {
    var accepted: Long = 0
        private set
    var suppressed: Long = 0
        private set

    fun recordAccepted() {
        accepted++
    }

    fun recordSuppressed() {
        suppressed++
    }
}
