package com.qkt.derivatives.options.pricing

import com.qkt.instrument.OptionRight
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every Greek against a central finite difference of the model's own price, over both rights, in and
 * out of the money, with a dividend yield, a negative rate, and both models — so a wrong sign or a
 * missing discount on any Greek fails here even where no published reference value exists.
 */
class GreeksFiniteDifferenceTest {
    private data class Case(
        val s: Double,
        val k: Double,
        val t: Double,
        val r: Double,
        val q: Double,
        val v: Double,
    )

    private val cases =
        listOf(
            Case(100.0, 80.0, 0.6, 0.05, 0.03, 0.25),
            Case(100.0, 125.0, 0.25, -0.01, 0.0, 0.4),
            Case(49.0, 50.0, 0.3846, 0.05, 0.02, 0.2),
            Case(60000.0, 64000.0, 7.0 / 365, 0.0, 0.0, 0.6),
        )

    private fun assertGreeks(
        label: String,
        value: OptionValue,
        price: (s: Double, t: Double, r: Double, v: Double) -> Double,
        c: Case,
    ) {
        val hs = c.s * 1e-4
        val delta = (price(c.s + hs, c.t, c.r, c.v) - price(c.s - hs, c.t, c.r, c.v)) / (2 * hs)
        val gamma = (price(c.s + hs, c.t, c.r, c.v) - 2 * value.price + price(c.s - hs, c.t, c.r, c.v)) / (hs * hs)
        val vega = (price(c.s, c.t, c.r, c.v + 1e-5) - price(c.s, c.t, c.r, c.v - 1e-5)) / 2e-5
        val theta = -(price(c.s, c.t + 1e-6, c.r, c.v) - price(c.s, c.t - 1e-6, c.r, c.v)) / 2e-6
        val rho = (price(c.s, c.t, c.r + 1e-6, c.v) - price(c.s, c.t, c.r - 1e-6, c.v)) / 2e-6
        assertClose("$label delta", value.delta, delta)
        assertClose("$label gamma", value.gamma, gamma)
        assertClose("$label vega", value.vega, vega)
        assertClose("$label theta", value.theta, theta)
        assertClose("$label rho", value.rho, rho)
    }

    private fun assertClose(
        label: String,
        actual: Double,
        expected: Double,
    ) {
        val scale = maxOf(1e-6, kotlin.math.abs(expected))
        assertThat(
            kotlin.math.abs(actual - expected) / scale,
        ).describedAs("$label: $actual vs $expected").isLessThan(1e-4)
    }

    @Test
    fun `black scholes greeks are the derivatives of its price`() {
        for (c in cases) {
            for (right in OptionRight.entries) {
                val value = BlackScholes.value(right, c.s, c.k, c.t, c.r, c.v, c.q)
                assertGreeks(
                    "BS $right $c",
                    value,
                    { s, t, r, v -> BlackScholes.value(right, s, c.k, t, r, v, c.q).price },
                    c,
                )
            }
        }
    }

    @Test
    fun `black 76 greeks are the derivatives of its price`() {
        for (c in cases) {
            for (right in OptionRight.entries) {
                val value = Black76.value(right, c.s, c.k, c.t, c.r, c.v)
                assertGreeks("B76 $right $c", value, { f, t, r, v -> Black76.value(right, f, c.k, t, r, v).price }, c)
            }
        }
    }
}
