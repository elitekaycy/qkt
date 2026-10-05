package com.qkt.research

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.accountingEngine
import com.qkt.accounting.margin.MaintenanceMargin
import com.qkt.accounting.margin.MarginDailySampler
import com.qkt.accounting.margin.MarginModel
import com.qkt.accounting.margin.OptionMargin
import com.qkt.backtest.StructureLog
import com.qkt.broker.continuous.ContractFillLog
import com.qkt.broker.continuous.RollLedger
import com.qkt.broker.exchange.SettlementLog
import com.qkt.broker.liquidation.MarginLiquidator
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.CommissionBook
import com.qkt.pnl.PerLotCommission
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker

/**
 * The marks, positions and money books one replay writes into: the price tracker, account and
 * per-strategy positions, the accounting engine, account and per-strategy PnL, commissions, the
 * futures roll ledger, the option settlement and structure logs, and the margin liquidator with its
 * log. Each is the single writer of its quantity for the run; PnL marks at [markTimestamp]. Futures
 * fees are not commissions here: the exchange simulator reports them on each fill, as a live venue
 * does.
 */
internal class ReplayBooks(
    val instruments: InstrumentRegistry,
    accountingConfig: AccountingConfig,
    markTimestamp: () -> Long,
) {
    val priceTracker = MarketPriceTracker()
    val strategyPositions = StrategyPositionTracker()
    val positions = strategyPositions.account
    val commissionBook = CommissionBook(PerLotCommission(instruments))
    val rolls = RollLedger()
    val contractFills = ContractFillLog()
    val settlements = SettlementLog()
    val structures = StructureLog()
    val accounting = accountingEngine(accountingConfig, priceTracker, instruments)
    private val margin = MarginModel(instruments, accounting)
    private val optionMargin = instruments.options()?.let { OptionMargin(instruments) }
    val marginDaily = MarginDailySampler(margin, priceTracker, positions, optionMargin)
    val liquidator = MarginLiquidator(MaintenanceMargin(margin, priceTracker, positions))
    val pnl = PnLCalculator(positions, priceTracker, instruments, accounting, markTimestamp = markTimestamp)
    val strategyPnL =
        StrategyPnL(
            strategyPositions,
            priceTracker,
            instruments,
            accounting = accounting,
            markTimestamp = markTimestamp,
        )
}
