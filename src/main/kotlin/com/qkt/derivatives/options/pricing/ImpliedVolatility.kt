package com.qkt.derivatives.options.pricing

import com.qkt.instrument.OptionRight
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The volatility at which a model reproduces an option price. Prices at or outside the no-arbitrage
 * bounds (the discounted intrinsic value below, the discounted underlying or strike above) and
 * expired options have none, and the solver says so with null instead of inventing a number — never
 * 0. Inside the bounds the price is increasing in volatility, so Newton steps on vega are taken
 * inside a bisection bracket on `[1e-4, 10.0]` (short-dated crypto wings can trade above 500%), falling back to bisection when vega vanishes or a
 * step leaves the bracket; it stops when volatility is pinned to 1e-12 or returns null after 100
 * steps. A price within 1e-12 (of the bound's scale) of its floor is indistinguishable from it and
 * has no implied volatility either.
 */
object ImpliedVolatility {
    private const val LOW = 1e-4
    private const val HIGH = 10.0
    private const val SIGMA_TOLERANCE = 1e-12

    /** Prices this close to the no-arbitrage floor, relative to its scale, carry no volatility information. */
    private const val PRICE_RESOLUTION = 1e-12
    private const val MAX_STEPS = 100

    /** Black–Scholes implied volatility of [price] for a spot option, or null. */
    fun blackScholes(
        right: OptionRight,
        price: Double,
        spot: Double,
        strike: Double,
        years: Double,
        rate: Double,
        dividendYield: Double = 0.0,
    ): Double? {
        if (years <= 0.0) return null
        val underlying = spot * exp(-dividendYield * years)
        val strikeValue = strike * exp(-rate * years)
        return solve(price, bounds(right, underlying, strikeValue)) {
            BlackScholes.value(right, spot, strike, years, rate, it, dividendYield)
        }
    }

    /** Black-76 implied volatility of [price] for an option on a forward or futures price, or null. */
    fun black76(
        right: OptionRight,
        price: Double,
        forward: Double,
        strike: Double,
        years: Double,
        rate: Double,
    ): Double? {
        if (years <= 0.0) return null
        val discount = exp(-rate * years)
        return solve(price, bounds(right, forward * discount, strike * discount)) {
            Black76.value(right, forward, strike, years, rate, it)
        }
    }

    /** No-arbitrage price range given the discounted underlying and discounted strike. */
    private fun bounds(
        right: OptionRight,
        underlying: Double,
        strike: Double,
    ): ClosedFloatingPointRange<Double> =
        when (right) {
            OptionRight.CALL -> max(underlying - strike, 0.0)..underlying
            OptionRight.PUT -> max(strike - underlying, 0.0)..strike
        }

    private fun solve(
        target: Double,
        bounds: ClosedFloatingPointRange<Double>,
        value: (Double) -> OptionValue,
    ): Double? {
        val resolution = PRICE_RESOLUTION * bounds.endInclusive
        if (!(target - bounds.start > resolution && target < bounds.endInclusive)) return null
        var low = LOW
        var high = HIGH
        if (value(low).price > target || value(high).price < target) return null
        var sigma = 0.5
        repeat(MAX_STEPS) {
            val v = value(sigma)
            val error = v.price - target
            if (error == 0.0) return sigma
            if (error > 0) high = sigma else low = sigma
            val newton = if (v.vega > 0.0) sigma - error / v.vega else Double.NaN
            val next = if (newton > low && newton < high) newton else (low + high) / 2
            if (abs(next - sigma) < SIGMA_TOLERANCE || high - low < SIGMA_TOLERANCE) return next
            sigma = next
        }
        return null
    }
}
