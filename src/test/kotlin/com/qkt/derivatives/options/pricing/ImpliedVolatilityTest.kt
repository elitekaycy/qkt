package com.qkt.derivatives.options.pricing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * Round trips across moneyness and volatility. A price with no time value above its no-arbitrage
 * floor (deep in the money at low volatility) carries no volatility information and may be unsolved.
 */
class ImpliedVolatilityTest {
    private val vols = listOf(0.05, 0.2, 0.8, 2.0)
    private val strikes = listOf(70.0, 100.0, 130.0)

    /** [solved] gives back [sigma], or the price was indistinguishable from its value at the volatility floor. */
    private fun assertRoundTrip(
        label: String,
        sigma: Double,
        solved: Double?,
        price: Double,
        priceAtFloor: Double,
    ) {
        if (solved == null) {
            assertThat(price - priceAtFloor).describedAs("$label unsolved with time value").isLessThan(1e-9)
        } else {
            assertThat(solved).describedAs(label).isCloseTo(sigma, within(1e-6))
        }
    }

    @Test
    fun `black scholes prices invert to the volatility that made them`() {
        for (right in OptionRight.entries) {
            for (sigma in vols) {
                for (k in strikes) {
                    val price = BlackScholes.value(right, 100.0, k, 0.75, 0.04, sigma, 0.01).price
                    val solved = ImpliedVolatility.blackScholes(right, price, 100.0, k, 0.75, 0.04, 0.01)
                    val floor = BlackScholes.value(right, 100.0, k, 0.75, 0.04, 1e-4, 0.01).price
                    assertRoundTrip("$right σ=$sigma K=$k", sigma, solved, price, floor)
                }
            }
        }
    }

    @Test
    fun `black 76 prices invert to the volatility that made them`() {
        for (right in OptionRight.entries) {
            for (sigma in vols) {
                for (k in strikes) {
                    val price = Black76.value(right, 100.0, k, 0.5, 0.03, sigma).price
                    val solved = ImpliedVolatility.black76(right, price, 100.0, k, 0.5, 0.03)
                    val floor = Black76.value(right, 100.0, k, 0.5, 0.03, 1e-4).price
                    assertRoundTrip("$right σ=$sigma K=$k", sigma, solved, price, floor)
                }
            }
        }
    }

    @Test
    fun `hull's call price gives back about twenty percent`() {
        assertThat(
            ImpliedVolatility.blackScholes(OptionRight.CALL, 4.76, 42.0, 40.0, 0.5, 0.10),
        ).isCloseTo(0.20, within(0.002))
    }

    @Test
    fun `prices outside the no-arbitrage bounds have no implied volatility`() {
        // The call is worth at least S - K·e^(-rT) = 42 - 40·e^(-0.05) ≈ 3.95 and at most 42.
        assertThat(ImpliedVolatility.blackScholes(OptionRight.CALL, 3.0, 42.0, 40.0, 0.5, 0.10)).isNull()
        assertThat(ImpliedVolatility.blackScholes(OptionRight.CALL, 42.5, 42.0, 40.0, 0.5, 0.10)).isNull()
        assertThat(ImpliedVolatility.black76(OptionRight.PUT, 25.0, 20.0, 20.0, 4.0 / 12, 0.09)).isNull()
    }

    @Test
    fun `an expired option has no implied volatility`() {
        assertThat(ImpliedVolatility.black76(OptionRight.CALL, 1.0, 20.0, 19.0, 0.0, 0.05)).isNull()
    }

    @Test
    fun `a short-dated far out of the money option still solves`() {
        val price = Black76.value(OptionRight.CALL, 100.0, 130.0, 7.0 / 365, 0.0, 1.5).price

        assertThat(
            ImpliedVolatility.black76(OptionRight.CALL, price, 100.0, 130.0, 7.0 / 365, 0.0),
        ).isCloseTo(1.5, within(1e-6))
    }
}
