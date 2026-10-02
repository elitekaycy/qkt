package com.qkt.derivatives.options.pricing

import com.qkt.instrument.OptionRight
import kotlin.math.exp

/**
 * Black–Scholes–Merton for a European option on a spot asset paying a continuous [dividendYield]
 * (Hull, ch. 15/17): carry `b = r − q`, so rho is `T·K·e^(−rT)·N(d2)` for a call and
 * `−T·K·e^(−rT)·N(−d2)` for a put.
 *
 * ```kotlin
 * BlackScholes.value(OptionRight.CALL, spot = 42.0, strike = 40.0, years = 0.5, rate = 0.10, volatility = 0.20).price // 4.76
 * ```
 */
object BlackScholes {
    /** The option's price and Greeks; [years] 0 gives the exercise value. */
    fun value(
        right: OptionRight,
        spot: Double,
        strike: Double,
        years: Double,
        rate: Double,
        volatility: Double,
        dividendYield: Double = 0.0,
    ): OptionValue {
        require(spot > 0.0) { "option spot must be > 0: $spot" }
        return GeneralizedBlackScholes.value(
            right,
            spot,
            strike,
            years,
            rate,
            rate - dividendYield,
            volatility,
        ) { _, d2 ->
            val discountedStrike = years * strike * exp(-rate * years)
            when (right) {
                OptionRight.CALL -> discountedStrike * NormalDistribution.cdf(d2)
                OptionRight.PUT -> -discountedStrike * NormalDistribution.cdf(-d2)
            }
        }
    }
}
