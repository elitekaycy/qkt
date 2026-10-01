package com.qkt.derivatives.options.pricing

import com.qkt.instrument.OptionRight

/**
 * Black (1976) for a European option on a futures or forward price (Hull, ch. 18): carry 0, so the
 * premium is the discounted Black–Scholes value on the forward and rho is `−T × price`.
 *
 * ```kotlin
 * Black76.value(OptionRight.PUT, forward = 20.0, strike = 20.0, years = 4.0 / 12, rate = 0.09, volatility = 0.25).price // 1.12
 * ```
 */
object Black76 {
    /** The option's price and Greeks; [years] 0 gives the exercise value. */
    fun value(
        right: OptionRight,
        forward: Double,
        strike: Double,
        years: Double,
        rate: Double,
        volatility: Double,
    ): OptionValue {
        require(forward > 0.0) { "option forward must be > 0: $forward" }
        return GeneralizedBlackScholes.value(right, forward, strike, years, rate, 0.0, volatility) { price, _ ->
            -years *
                price
        }
    }
}
