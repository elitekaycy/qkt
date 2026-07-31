package com.qkt.app

/** Source used for standalone live strategy equity and equity-based sizing. */
enum class LiveEquityBasis {
    /** Use the venue's account-equity snapshot when the broker exposes one. */
    VENUE,

    /** Use starting balance plus qkt-modeled realized and unrealized P&L. */
    MODELED,
    ;

    companion object {
        /** Parse `risk.live_equity_basis`; defaults to [VENUE] for compatibility. */
        fun fromConfig(raw: String?): LiveEquityBasis =
            when (raw?.trim()?.lowercase()) {
                null, "", "venue", "broker" -> VENUE
                "modeled", "modelled", "engine" -> MODELED
                else -> error("unknown live_equity_basis '$raw' (valid: venue, modeled)")
            }
    }
}
