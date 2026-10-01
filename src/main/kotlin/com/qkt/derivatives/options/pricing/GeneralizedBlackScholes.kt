package com.qkt.derivatives.options.pricing

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The generalized Black–Scholes model with cost of carry `b` (Haug, *The Complete Guide to Option
 * Pricing Formulas*, ch. 1): `b = r − q` prices options on a spot asset with yield `q`, `b = 0`
 * options on futures (Black-76). Prices and Greeks except rho; each model supplies its own rho
 * because it depends on whether carry moves with the rate.
 */
internal object GeneralizedBlackScholes {
    /** Price and Greeks with [rho] computed by the caller from the other figures. */
    fun value(
        right: OptionRight,
        underlying: Double,
        strike: Double,
        years: Double,
        rate: Double,
        carry: Double,
        volatility: Double,
        rho: (price: Double, d2: Double) -> Double,
    ): OptionValue {
        require(underlying > 0.0 && underlying.isFinite()) {
            "option underlying (spot or forward) must be finite and > 0: $underlying"
        }
        require(strike > 0.0) { "option strike must be > 0: $strike" }
        require(years >= 0.0) { "option years to expiry must be >= 0: $years" }
        require(volatility > 0.0 && volatility.isFinite()) { "option volatility must be finite and > 0: $volatility" }
        require(rate.isFinite()) { "option rate must be finite: $rate" }
        require(carry.isFinite()) { "option carry (rate less yield) must be finite: $carry" }
        require(years.isFinite()) { "option years to expiry must be finite: $years" }
        if (years == 0.0) return intrinsic(right, underlying, strike)
        val sqrtT = sqrt(years)
        val d1 = (ln(underlying / strike) + (carry + volatility * volatility / 2) * years) / (volatility * sqrtT)
        val d2 = d1 - volatility * sqrtT
        val carryDiscount = exp((carry - rate) * years)
        val rateDiscount = exp(-rate * years)
        val density = NormalDistribution.pdf(d1)
        val gamma = carryDiscount * density / (underlying * volatility * sqrtT)
        val vega = underlying * carryDiscount * density * sqrtT
        val decay = -underlying * carryDiscount * density * volatility / (2 * sqrtT)
        val n = NormalDistribution::cdf
        return when (right) {
            OptionRight.CALL -> {
                val price = underlying * carryDiscount * n(d1) - strike * rateDiscount * n(d2)
                val theta =
                    decay - (carry - rate) * underlying * carryDiscount * n(d1) - rate * strike * rateDiscount * n(d2)
                OptionValue(price, carryDiscount * n(d1), gamma, vega, theta, rho(price, d2))
            }
            OptionRight.PUT -> {
                val price = strike * rateDiscount * n(-d2) - underlying * carryDiscount * n(-d1)
                val theta =
                    decay + (carry - rate) * underlying * carryDiscount * n(-d1) + rate * strike * rateDiscount * n(-d2)
                OptionValue(price, carryDiscount * (n(d1) - 1), gamma, vega, theta, rho(price, d2))
            }
        }
    }

    /** At expiry: the exercise value, with delta 1 / −1 in the money and no time or rate sensitivity. */
    private fun intrinsic(
        right: OptionRight,
        underlying: Double,
        strike: Double,
    ): OptionValue {
        val (price, delta) =
            when (right) {
                OptionRight.CALL -> max(underlying - strike, 0.0) to if (underlying > strike) 1.0 else 0.0
                OptionRight.PUT -> max(strike - underlying, 0.0) to if (strike > underlying) -1.0 else 0.0
            }
        return OptionValue(price, delta, 0.0, 0.0, 0.0, 0.0)
    }
}
