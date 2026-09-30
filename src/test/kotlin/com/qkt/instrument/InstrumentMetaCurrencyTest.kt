package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InstrumentMetaCurrencyTest {
    private fun meta(currency: String?) =
        InstrumentMeta(
            qktSymbol = "CME:ESZ6",
            contractSize = BigDecimal("50"),
            volumeStep = BigDecimal.ONE,
            volumeMin = BigDecimal.ONE,
            volumeMax = null,
            pointSize = BigDecimal("0.25"),
            digits = 2,
            tradeStopsLevelPoints = 0,
            currency = currency,
        )

    @Test
    fun `currency defaults to absent`() {
        assertThat(meta(null).currency).isNull()
        assertThat(meta(null).derivative).isNull()
    }

    @Test
    fun `currency must be an upper-case code`() {
        assertThatThrownBy { meta("usd") }.hasMessageContaining("currency")
        assertThatThrownBy { meta("") }.hasMessageContaining("currency")
        assertThat(meta("USDT").currency).isEqualTo("USDT")
    }
}
