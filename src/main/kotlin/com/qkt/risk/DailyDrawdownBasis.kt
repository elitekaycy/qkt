package com.qkt.risk

/** The day-start reference for daily drawdown: closed balance, or equity (includes open-position float). */
enum class DailyDrawdownBasis {
    BALANCE,
    EQUITY,
    ;

    companion object {
        fun fromConfig(value: String?): DailyDrawdownBasis =
            when (value?.lowercase()) {
                null, "balance" -> BALANCE
                "equity" -> EQUITY
                else -> throw IllegalArgumentException("unknown daily_dd_basis '$value' (valid: balance, equity)")
            }
    }
}
