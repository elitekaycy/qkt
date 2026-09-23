package com.qkt.cli

/** Shown by the daemon and by a standalone backtest when `book_risk` limits are configured but not enforced. */
internal const val STANDALONE_BOOK_RISK_WARNING =
    "book_risk limits are configured but are enforced only for PORTFOLIO deployments; " +
        "strategies deployed individually are not bounded by them. Deploy as a " +
        "portfolio, or bound exposure inside the strategy."
