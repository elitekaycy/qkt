package com.qkt.pnl

import java.math.BigDecimal

/**
 * Read-only balance of the portfolio book a strategy trades inside: the declared
 * portfolio CAPITAL plus the cumulative net realized PnL of every child. Backing it
 * with realized-only (balance-style) keeps the number deterministic and identical
 * between live and backtest — open positions never move it.
 *
 * Bound onto [com.qkt.strategy.StrategyContext.book] by portfolio deploy paths only;
 * a standalone strategy has no book and the field stays null.
 * e.g. a $50k book that has realized +$1,200 across its children reports 51200.
 */
fun interface BookBalanceView {
    /** Current book balance in account currency. */
    fun balance(): BigDecimal
}
