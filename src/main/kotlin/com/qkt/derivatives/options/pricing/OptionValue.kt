package com.qkt.derivatives.options.pricing

/** Whether an option is the right to buy ([CALL]) or to sell ([PUT]) the underlying. */
enum class OptionRight { CALL, PUT }

/**
 * A model price and its sensitivities, per one unit of the underlying: [delta] and [gamma] to the
 * underlying price, [vega] to volatility (per 1.00, i.e. per 100 vol points), [theta] the change
 * in value as time passes, `−∂V/∂T` per year (Haug's convention; usually negative, but positive for a
 * deep in-the-money European put), [rho] to the interest rate (per 1.00). At expiry an at-the-money
 * option's delta is 0 by convention.
 */
data class OptionValue(
    val price: Double,
    val delta: Double,
    val gamma: Double,
    val vega: Double,
    val theta: Double,
    val rho: Double,
)
