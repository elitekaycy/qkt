package com.qkt.derivatives.options.pricing

/** Whether an option is the right to buy ([CALL]) or to sell ([PUT]) the underlying. */
enum class OptionRight { CALL, PUT }

/**
 * A model price and its sensitivities, per one unit of the underlying: [delta] and [gamma] to the
 * underlying price, [vega] to volatility (per 1.00, i.e. per 100 vol points), [theta] to time (per
 * year, negative as the option decays), [rho] to the interest rate (per 1.00).
 */
data class OptionValue(
    val price: Double,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
    val rho: Double,
)
