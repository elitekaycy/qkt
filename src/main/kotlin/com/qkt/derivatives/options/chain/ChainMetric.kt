package com.qkt.derivatives.options.chain

/** An analytic of an option chain at a tenor, named in a `CHAIN:` stream by its [token]. */
enum class ChainMetric(
    val token: String,
) {
    /** At-the-money implied volatility, in percent. */
    ATM_IV("atm_iv"),

    /** 25-delta put implied volatility less 25-delta call implied volatility, in IV points. */
    SKEW_25D("skew_25d"),
    ;

    companion object {
        /** The metric spelled [token], or null. */
        fun of(token: String): ChainMetric? = entries.firstOrNull { it.token == token }
    }
}
