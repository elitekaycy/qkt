package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.BookBalanceView
import com.qkt.pnl.StrategyPnL
import com.qkt.pnl.StrategyPnLViewImpl
import com.qkt.pnl.TradeHistory
import com.qkt.pnl.TradeHistoryViewImpl
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.StrategyPositionViewImpl
import com.qkt.risk.PacerLedger
import com.qkt.risk.PacerViewImpl
import com.qkt.risk.RiskState
import com.qkt.risk.RiskViewImpl
import com.qkt.strategy.Mode
import com.qkt.strategy.OpenOrderView
import com.qkt.strategy.QuoteToAccountRateProvider
import com.qkt.strategy.StrategyContext

/**
 * Builds the read-only [StrategyContext] each strategy evaluates against: per-strategy views of
 * positions, PnL, risk, trade history and the pacer over the pipeline's shared trackers, plus the
 * clock, calendar, market source and instrument registry. Pacer cooldowns resolve per strategy.
 */
internal class StrategyContextFactory(
    private val mode: Mode,
    private val clock: Clock,
    private val calendar: TradingCalendar,
    private val source: MarketSource,
    private val strategyPositions: StrategyPositionTracker,
    private val strategyPnL: StrategyPnL,
    private val riskState: RiskState,
    private val instruments: InstrumentRegistry,
    private val accounting: AccountingEngine,
    private val tradeHistory: TradeHistory,
    private val pacerLedger: PacerLedger,
    private val pacerCooldownDurationMs: Long?,
    private val pacerCooldownAfterConsecutive: Int,
    private val pacerCooldownDurationMsFor: ((String) -> Long?)?,
    private val pacerCooldownAfterConsecutiveFor: ((String) -> Int)?,
    private val orderManager: OrderManager,
    private val bookBalance: BookBalanceView?,
) {
    /** The context for [strategyId]. */
    fun create(strategyId: String): StrategyContext =
        StrategyContext(
            strategyId = strategyId,
            mode = mode,
            clock = clock,
            calendar = calendar,
            source = source,
            positions = StrategyPositionViewImpl(strategyPositions, strategyId),
            pnl = StrategyPnLViewImpl(strategyPnL, strategyId),
            risk = RiskViewImpl(riskState, strategyId),
            instruments = instruments,
            quoteToAccountRate =
                QuoteToAccountRateProvider { symbol, timestamp, referencePrice ->
                    accounting.quoteToAccountRate(symbol, timestamp, referencePrice)
                },
            tradeHistory = TradeHistoryViewImpl(tradeHistory, strategyId),
            pacer =
                PacerViewImpl(
                    pacerLedger,
                    strategyId,
                    pacerCooldownDurationMsFor?.invoke(strategyId) ?: pacerCooldownDurationMs,
                    pacerCooldownAfterConsecutiveFor?.invoke(strategyId) ?: pacerCooldownAfterConsecutive,
                ),
            openOrders = OpenOrderView { symbol -> orderManager.activeEntryOrderCount(strategyId, symbol) },
            book = bookBalance,
        )
}
