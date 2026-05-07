package com.qkt.dsl.stdlib

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IndicatorRegistryTest {
    @Test
    fun `EMA RSI ATR are registered`() {
        assertThat(IndicatorRegistry.has("EMA")).isTrue()
        assertThat(IndicatorRegistry.has("RSI")).isTrue()
        assertThat(IndicatorRegistry.has("ATR")).isTrue()
    }

    @Test
    fun `EMA spec wants a numeric series and one period arg`() {
        val spec = IndicatorRegistry.spec("EMA")!!
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.NUMERIC_SERIES)
        assertThat(spec.arity).isEqualTo(2)
    }

    @Test
    fun `ATR spec wants a candle series and one period arg`() {
        val spec = IndicatorRegistry.spec("ATR")!!
        assertThat(spec.inputKind).isEqualTo(IndicatorInput.CANDLE_SERIES)
        assertThat(spec.arity).isEqualTo(2)
    }

    @Test
    fun `creating EMA returns an EMA indicator`() {
        val ind = IndicatorRegistry.create("EMA", listOf(java.math.BigDecimal("9")))
        assertThat(ind).isInstanceOf(com.qkt.indicators.catalog.EMA::class.java)
    }

    @Test
    fun `creating with the wrong arity throws`() {
        assertThatThrownBy { IndicatorRegistry.create("EMA", emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown indicator returns null spec`() {
        assertThat(IndicatorRegistry.spec("UNKNOWN")).isNull()
    }
}
