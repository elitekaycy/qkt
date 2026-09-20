package com.qkt.research

import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentRegistry
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltRule
import com.qkt.risk.PacerLedger
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.RiskState
import com.qkt.risk.StrategyRiskLimits
import com.qkt.risk.StrategyRiskRuleFactory
import com.qkt.risk.book.BookRiskConfig
import com.qkt.risk.book.BookRiskController
import com.qkt.risk.rules.BookExposureLimit
import com.qkt.risk.rules.PreTradeControls
import java.math.BigDecimal

/**
 * A replay's risk stack, built the way a live deploy builds it: the [RiskState] (balance basis and
 * halt rules), per-strategy limits, the always-on pre-trade controls, the optional book-level
 * [BookRiskController] and the [RiskEngine] that walks them all. Constructed once, in that order.
 */
internal class ReplayRisk(
    rules: List<RiskRule>,
    haltRules: List<HaltRule>,
    strategyIds: List<String>,
    books: ReplayBooks,
    clock: FixedClock,
    bus: EventBus,
    instruments: InstrumentRegistry,
    startingBalance: BigDecimal,
    startingBalances: Map<String, BigDecimal>,
    dailyDdBasis: DailyDrawdownBasis,
    totalDdBasis: DrawdownBasis,
    strategyRiskLimits: Map<String, StrategyRiskLimits>,
    pacerLedger: PacerLedger,
    maxOrderQty: BigDecimal,
    maxOrderNotional: BigDecimal,
    priceCollarFrac: BigDecimal,
    candleWindow: TimeWindow?,
    calendar: TradingCalendar,
    bookRiskConfig: BookRiskConfig?,
) {
    // Mirror the live RiskState construction (balance basis + halt rules) so a
    // strategy that would halt live halts at the same point in its backtest.
    val riskState = RiskState(books.pnl, books.strategyPnL, clock, bus, startingBalance, dailyDdBasis)
    val bookRiskController: BookRiskController?
    val riskEngine: RiskEngine

    init {
        riskState.warmupComplete = true
        val strategyRuleSet =
            StrategyRiskRuleFactory.build(
                strategyIds = strategyIds,
                limitsByStrategy = strategyRiskLimits,
                strategyPositions = books.strategyPositions,
                pacerLedger = pacerLedger,
                clock = clock,
                totalDdBasis = totalDdBasis,
                startingBalance = startingBalance,
                startingBalances = startingBalances,
            )
        // The same always-on pre-trade controls live runs (#393): a backtest must show
        // the rejection a live deploy would produce, not sail past it.
        val preTradeRules =
            PreTradeControls.standard(
                prices = books.priceTracker,
                instruments = instruments,
                maxOrderQty = maxOrderQty,
                maxOrderNotional = maxOrderNotional,
                priceCollarFrac = priceCollarFrac,
                accounting = books.accounting,
            )
        val bookAnnualization =
            if (candleWindow != null) calendar.tradingPeriodsPerYear(candleWindow) else BigDecimal("252")
        bookRiskController =
            bookRiskConfig?.let {
                BookRiskController(it, it.capital ?: startingBalance, bookAnnualization)
            }
        val bookRules =
            bookRiskController?.let {
                listOf(
                    BookExposureLimit(it, books.priceTracker, instruments, books.accounting),
                )
            } ?: emptyList()
        riskEngine =
            RiskEngine(
                rules + strategyRuleSet.riskRules + preTradeRules + bookRules,
                haltRules + strategyRuleSet.haltRules,
                books.positions,
                riskState,
            )
    }
}
