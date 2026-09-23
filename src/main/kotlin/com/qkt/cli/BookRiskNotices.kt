package com.qkt.cli

import com.qkt.risk.book.BookRiskConfig

/** Shown by the daemon and by a standalone backtest when `book_risk` limits are configured but not enforced. */
internal const val STANDALONE_BOOK_RISK_WARNING =
    "book_risk limits are configured but are enforced only for PORTFOLIO deployments; " +
        "strategies deployed individually are not bounded by them. Deploy as a " +
        "portfolio, or bound exposure inside the strategy."

/**
 * A standalone strategy is deployed live through StrategyHandle, which never builds a
 * BookRiskController, so `book_risk` bounds nothing there (catalog row A23). A standalone backtest
 * therefore does not enforce it either — enforcing it would reject orders the live deploy of the
 * same file sends — and says so when [config] declares limits.
 */
internal fun warnStandaloneBookRiskIgnored(config: BookRiskConfig?) {
    if (config?.limits != null) System.err.println("qkt: WARNING — $STANDALONE_BOOK_RISK_WARNING")
}
