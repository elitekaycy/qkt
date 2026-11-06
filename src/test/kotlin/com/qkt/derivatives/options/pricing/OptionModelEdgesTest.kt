package com.qkt.derivatives.options.pricing

import com.qkt.instrument.OptionRight
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OptionModelEdgesTest {
    @Test
    fun `an out-of-the-money call and an in-the-money put at expiry take their exercise values`() {
        val call = Black76.value(OptionRight.CALL, 95.0, 100.0, 0.0, 0.05, 0.3)
        val put = BlackScholes.value(OptionRight.PUT, 95.0, 100.0, 0.0, 0.05, 0.3)

        assertThat(call.price).isEqualTo(0.0)
        assertThat(call.delta).isEqualTo(0.0)
        assertThat(put.price).isEqualTo(5.0)
        assertThat(put.delta).isEqualTo(-1.0)
    }

    @Test
    fun `infinite time, rate or carry is refused by name`() {
        assertThatThrownBy { Black76.value(OptionRight.CALL, 20.0, 20.0, Double.POSITIVE_INFINITY, 0.1, 0.2) }
            .hasMessageContaining("years to expiry must be finite")
        assertThatThrownBy { Black76.value(OptionRight.PUT, 20.0, 20.0, 0.5, Double.NEGATIVE_INFINITY, 0.2) }
            .hasMessageContaining("rate must be finite")
        assertThatThrownBy {
            BlackScholes.value(OptionRight.PUT, 42.0, 40.0, 0.5, 0.1, 0.2, Double.POSITIVE_INFINITY)
        }.hasMessageContaining("carry")
        assertThatThrownBy { Black76.value(OptionRight.CALL, Double.NaN, 20.0, 0.5, 0.1, 0.2) }
            .hasMessageContaining("forward must be > 0")
    }
}
