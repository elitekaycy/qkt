package com.qkt.derivatives.options.pricing

import com.qkt.instrument.OptionRight
import kotlin.math.exp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** Reference values from Hull, *Options, Futures, and Other Derivatives*, worked examples. */
class OptionModelsTest {
    @Test
    fun `black scholes prices hull's call and put`() {
        val call = BlackScholes.value(OptionRight.CALL, 42.0, 40.0, 0.5, 0.10, 0.20)
        val put = BlackScholes.value(OptionRight.PUT, 42.0, 40.0, 0.5, 0.10, 0.20)

        assertThat(call.price).isCloseTo(4.76, within(0.005))
        assertThat(put.price).isCloseTo(0.81, within(0.005))
    }

    @Test
    fun `black scholes greeks match hull's worked example`() {
        val call = BlackScholes.value(OptionRight.CALL, 49.0, 50.0, 0.3846, 0.05, 0.20)

        assertThat(call.price).isCloseTo(2.40, within(0.005))
        assertThat(call.delta).isCloseTo(0.522, within(0.0005))
        assertThat(call.gamma).isCloseTo(0.066, within(0.0005))
        assertThat(call.vega).isCloseTo(12.1, within(0.05))
        assertThat(call.theta).isCloseTo(-4.31, within(0.005))
        assertThat(call.rho).isCloseTo(8.91, within(0.005))
    }

    @Test
    fun `black 76 prices hull's put on a futures contract`() {
        val put = Black76.value(OptionRight.PUT, 20.0, 20.0, 4.0 / 12, 0.09, 0.25)

        assertThat(put.price).isCloseTo(1.12, within(0.005))
        assertThat(put.rho).isCloseTo(-put.price * 4.0 / 12, within(1e-12))
    }

    @Test
    fun `put call parity holds in both models`() {
        for (strike in listOf(30.0, 50.0, 70.0)) {
            val c = BlackScholes.value(OptionRight.CALL, 50.0, strike, 0.7, 0.03, 0.35, dividendYield = 0.01).price
            val p = BlackScholes.value(OptionRight.PUT, 50.0, strike, 0.7, 0.03, 0.35, dividendYield = 0.01).price
            assertThat(c - p).isCloseTo(50.0 * exp(-0.01 * 0.7) - strike * exp(-0.03 * 0.7), within(1e-9))
            val fc = Black76.value(OptionRight.CALL, 50.0, strike, 0.7, 0.03, 0.35).price
            val fp = Black76.value(OptionRight.PUT, 50.0, strike, 0.7, 0.03, 0.35).price
            assertThat(fc - fp).isCloseTo(exp(-0.03 * 0.7) * (50.0 - strike), within(1e-9))
        }
    }

    @Test
    fun `at expiry an option is worth its intrinsic value with no time greeks`() {
        val itm = BlackScholes.value(OptionRight.CALL, 105.0, 100.0, 0.0, 0.05, 0.3)
        val otm = Black76.value(OptionRight.PUT, 105.0, 100.0, 0.0, 0.05, 0.3)

        assertThat(itm.price).isEqualTo(5.0)
        assertThat(itm.delta).isEqualTo(1.0)
        assertThat(listOf(itm.gamma, itm.vega, itm.theta, itm.rho)).containsOnly(0.0)
        assertThat(otm.price).isEqualTo(0.0)
        assertThat(otm.delta).isEqualTo(0.0)
    }

    @Test
    fun `inputs a model cannot price are refused by name`() {
        assertThatThrownBy {
            BlackScholes.value(
                OptionRight.CALL,
                0.0,
                40.0,
                0.5,
                0.1,
                0.2,
            )
        }.hasMessageContaining("spot")
        assertThatThrownBy { Black76.value(OptionRight.CALL, 20.0, -1.0, 0.5, 0.1, 0.2) }.hasMessageContaining("strike")
        assertThatThrownBy { Black76.value(OptionRight.CALL, 20.0, 20.0, -0.1, 0.1, 0.2) }.hasMessageContaining("years")
        assertThatThrownBy {
            Black76.value(
                OptionRight.CALL,
                20.0,
                20.0,
                0.5,
                0.1,
                0.0,
            )
        }.hasMessageContaining("volatility")
    }

    @Test
    fun `non-finite inputs are refused rather than priced as NaN`() {
        assertThatThrownBy {
            BlackScholes.value(OptionRight.CALL, 42.0, 40.0, 0.5, Double.NaN, 0.2)
        }.hasMessageContaining("rate")
        assertThatThrownBy {
            BlackScholes.value(OptionRight.CALL, 42.0, 40.0, 0.5, 0.1, 0.2, Double.NaN)
        }.hasMessageContaining("carry")
        assertThatThrownBy {
            Black76.value(OptionRight.CALL, 20.0, 20.0, 0.5, 0.1, Double.POSITIVE_INFINITY)
        }.hasMessageContaining("volatility")
        assertThatThrownBy {
            Black76.value(OptionRight.CALL, Double.POSITIVE_INFINITY, 20.0, 0.5, 0.1, 0.2)
        }.hasMessageContaining("underlying")
    }
}
