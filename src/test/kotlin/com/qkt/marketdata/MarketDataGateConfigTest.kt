package com.qkt.marketdata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarketDataGateConfigTest {
    private val default = MarketDataGateConfig.DEFAULT

    @Test
    fun `a non-positive minimum stale age is refused`() {
        assertThatThrownBy { default.copy(minStaleAgeMs = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("market_data.min_stale_age_ms must be positive")
    }

    @Test
    fun `an outlier sigma that is not a finite positive number is refused`() {
        assertThatThrownBy { default.copy(outlierSigma = Double.NaN) }
            .hasMessage("market_data.outlier_sigma must be a finite positive number")
        assertThatThrownBy { default.copy(outlierSigma = 0.0) }
            .hasMessage("market_data.outlier_sigma must be a finite positive number")
    }

    @Test
    fun `a negative clock skew allowance is refused`() {
        assertThatThrownBy { default.copy(maxClockSkewMs = -1L) }
            .hasMessage("market_data.max_clock_skew_ms must be non-negative")
    }

    @Test
    fun `a whole-number key given text is refused naming the key`() {
        assertThatThrownBy { MarketDataGateConfig.parse(mapOf("min_stale_age_ms" to "ten")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("market_data.min_stale_age_ms must be a whole number")
    }

    @Test
    fun `given keys override the defaults and the rest keep them`() {
        val parsed = MarketDataGateConfig.parse(mapOf("max_clock_skew_ms" to 250, "outlier_sigma" to "4.5"))

        assertThat(parsed.maxClockSkewMs).isEqualTo(250L)
        assertThat(parsed.outlierSigma).isEqualTo(4.5)
        assertThat(parsed.minStaleAgeMs).isEqualTo(default.minStaleAgeMs)
    }
}
