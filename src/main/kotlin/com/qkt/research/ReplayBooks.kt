package com.qkt.research

import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.AccountingEngine
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.CommissionBook
import com.qkt.pnl.PerLotCommission
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker

/**
 * The marks, positions and money books one replay writes into: the price tracker, account and
 * per-strategy positions, the accounting engine, account and per-strategy PnL, and commissions.
 * Each is the single writer of its quantity for the run; PnL marks at [markTimestamp].
 */
internal class ReplayBooks(
    instruments: InstrumentRegistry,
    accountingConfig: AccountingConfig,
    markTimestamp: () -> Long,
) {
    val priceTracker = MarketPriceTracker()
    val strategyPositions = StrategyPositionTracker()
    val positions = strategyPositions.account
    val commissionBook = CommissionBook(PerLotCommission(instruments))
    val accounting = AccountingEngine(accountingConfig, priceTracker)
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
